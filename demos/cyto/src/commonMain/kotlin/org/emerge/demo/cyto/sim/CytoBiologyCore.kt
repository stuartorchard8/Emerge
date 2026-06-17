package org.emerge.demo.cyto.sim

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac

/**
 * The per-cell biology of the matter model (MORPHOGENESIS.md), operating on [CellWork] + the
 * environment [CytoMatterGrid]. Everything here is integer/`Frac` and PRNG-free, so it is deterministic
 * and matter is conserved by construction (atoms are only moved between cytoplasm, biomass, and the
 * reservoir — never minted).
 *
 * Chemistry is **dense and id-keyed**: a cell's mobile cytoplasm and locked biomass are [MoleculeStore]s
 * (id→count, held sorted ascending by [SpeciesRegistry] id). Because an id is a molecule's lexicographic
 * rank, every deterministic lex tie-break the old string code expressed as `minByOrNull { it.key }` (the
 * lex-smallest molecule containing a bond, ending in an atom, the leftmost-split degradation target …)
 * is here a plain **forward scan** over the store — same choice, no per-tick hashing or boxing.
 *
 * A tick runs in three phases over all cells: [runGenes] (each cell executes its genome — gated actions
 * powered by light quanta; sequential in EntityId order because Import draws from the shared reservoir),
 * then [diffuse] (cytoplasm spreads to connected neighbours, snapshot-based so it's order-independent),
 * then [finish] per cell (degradation, size from biomass, death/division decision).
 */
object CytoBiologyCore {

    // ── tunable knobs — VALUES LIVE IN CytoTuning (the single tuning sheet); these are local references ──
    const val BONDS_PER_FULL = CytoTuning.BONDS_PER_FULL      // also read by CytoLifecycleSystem
    private const val DEGRADE_PERIOD = CytoTuning.DEGRADE_PERIOD
    private const val DEATH_BIOMASS = CytoTuning.DEATH_BIOMASS
    private const val REPAIR_PER_OP = CytoTuning.REPAIR_PER_OP
    private val FLEX_STEP = CytoTuning.FLEX_STEP

    /** Phase 0 — passive cell↔environment exchange (FREE, down-gradient), **batched and fair**: per
     *  species, each cell wants to move ⌊(env − cyto)/2⌋ between itself and its reservoir grid-cell
     *  (signed — absorb when the env is richer, leak when the cell is), halving the gradient toward
     *  equilibrium. This is how a cell feeds for free on what's around it (and how an autotroph's surplus
     *  leaks out to feed heterotrophs); concentrating *against* the gradient is the job of the
     *  energy-costing Import/Export genes. Biomass is locked (doesn't exchange).
     *
     *  **Fairness (the fix):** every cell sharing a grid-cell computes its desired transfer against the
     *  *same snapshot* of that grid-cell, so the result is independent of cell order. Leakers always
     *  deposit in full; absorbers that would collectively overdraw the snapshot share it **proportionally
     *  to demand** (`⌊want · env / Σwant⌋`, the floor remainder handed out one-per-cell in the supplied
     *  order). The old per-cell sequential draw let the lowest-EntityId cell skim the reservoir first
     *  every tick, so a founder starved its own (higher-id, identical-genome) daughters — making resource
     *  access a function of birth order rather than genome. [ordered] is the canonical (ascending-EntityId)
     *  cell order; it only breaks ties in the proportional remainder, never who-goes-first for the bulk. */
    fun passiveEnvExchange(ordered: List<CellWork>, grid: CytoMatterGrid) {
        // Group cells by their grid-cell, preserving the canonical order for the remainder tiebreak.
        val byCell = LinkedHashMap<Int, MutableList<CellWork>>()
        for (w in ordered) {
            if (w.gridIndex < 0) continue
            byCell.getOrPut(w.gridIndex) { ArrayList() }.add(w)
        }
        for ((idx, cells) in byCell) {
            val species = HashSet<Int>()                    // union of grid-cell + every cell's cytoplasm
            for (i in 0 until grid.cellSize(idx)) species.add(grid.cellIdAt(idx, i))
            for (w in cells) for (i in 0 until w.cytoplasm.size) species.add(w.cytoplasm.idAt(i))
            for (sp in species) exchangeSpecies(idx, sp, cells, grid)
        }
    }

    /** Resolve one species' passive exchange for the cells sharing grid-cell [idx], against the snapshot
     *  `env`. Leakers deposit fully; absorbers split the snapshot proportionally when over-subscribed. */
    private fun exchangeSpecies(idx: Int, sp: Int, cells: List<CellWork>, grid: CytoMatterGrid) {
        val env = grid.count(idx, sp)
        val want = IntArray(cells.size)        // each absorber's desired draw against the shared snapshot
        var demand = 0L                        // Σ want over absorbers
        for (i in cells.indices) {
            val canHold = cells[i].handleable.canHold(sp)
            val cyto = cells[i].cytoplasm.count(sp)
            val t = (env - cyto) / 2           // signed, toward zero; +ve = into the cell
            if (t < 0 && !canHold) {           // METABOLIC LEAK: only passively dump what the cell can't
                // metabolise. A species the cell CAN use is retained (no down-gradient leak), so an Import
                // gene can build a reserve and the cell coasts on it instead of bleeding it straight back to
                // the reservoir. (Waste it can't use still leaks; the food web is fed by death + decay.)
                cells[i].cytoplasm.add(sp, t)
                grid.deposit(idx, sp, -t)
            } else if (t > 0 && canHold) {
                // SELECTIVE UPTAKE: only absorb a species the cell can metabolise; one it can't is left in
                // the reservoir (conservation-safe), keeping per-cell species bounded by the genome.
                want[i] = t
                demand += t
            }
        }
        if (demand == 0L) return
        val grant = IntArray(cells.size)
        if (demand <= env) {                   // not over-subscribed: everyone gets their full want
            for (i in cells.indices) grant[i] = want[i]
        } else {                               // over-subscribed: proportional floor + remainder
            var granted = 0
            for (i in cells.indices) {
                grant[i] = (want[i].toLong() * env / demand).toInt()
                granted += grant[i]
            }
            var leftover = env - granted       // < number of absorbers (Σ of dropped fractions)
            for (i in cells.indices) {         // hand the remainder out one-per-absorber, in canonical order
                if (leftover <= 0) break
                if (want[i] > 0) { grant[i]++; leftover-- }
            }
        }
        for (i in cells.indices) {
            if (grant[i] <= 0) continue
            cells[i].cytoplasm.add(sp, grant[i])
            grid.draw(idx, sp, grant[i])
        }
    }

    /** Phase 1 — execute one cell's genome. Each ACTIVE gene gets a flat **1/N share** (N = active-gene
     *  count) of every resource it touches — the cell's light quanta and each cytoplasm species it consumes
     *  — and performs its whole action for the tick in **one bulk step** (no per-quantum loop). Shares come
     *  from a tick-start snapshot, so they're disjoint: applying the genes in any order can never over-draw
     *  a pool (⇒ order-independent + matter-conserving), and a pool's `mod N` remainder just lingers unused
     *  this tick (negligible once quantities are large — this is why the world wants the big-number scale).
     *  The 1/N split is the genome-bloat tax: more simultaneously-active genes ⇒ each gets a thinner slice
     *  (inactive genes don't reserve a share — carrying them is taxed by mutation load instead). Import
     *  draws from the shared [grid], so this still runs in a fixed cell order across cells. */
    fun runGenes(work: CellWork, grid: CytoMatterGrid) {
        val genome = work.genome
        // Phase 1 — continuous metabolic genes, INTERPOLATED: each runs only for the portion of the tick
        // before its action would carry a gated quantity across its OWN condition threshold (selfGateCap),
        // so a growth gene fills exactly to its limit instead of overshooting in one bulk step. Computed
        // from the tick-start snapshot, so it stays order-independent. Division is excluded here.
        // Active genes are collected into the reused [CellWork.activeScratch] (their genome indices, in
        // genome order — same set/order the old `genome.filter` produced) so the pass allocates nothing.
        val active = work.activeScratch
        var n = 0
        for (i in genome.indices) {
            val g = genome[i]
            if (g.action.type != ActionType.Mitosis && isActive(g, work)) active[n++] = i
        }
        if (n > 0) {
            val snap = work.snapScratch.also { it.copyFrom(work.cytoplasm) }   // reused; immutable 1/n source
            val snapBiomass = totalBiomassBonds(work.biomass)
            val quantaShare = work.quanta / n
            for (j in 0 until n) applyGene(genome[active[j]], work, grid, snap, snapBiomass, n, quantaShare)
        }
        // Phase 2 — division resolved on the SETTLED state, as one atomic end-of-tick action (a clean
        // half-split is only sane atomically). The gate is RE-CHECKED here against the post-metabolism
        // state: a Mitosis gene armed at tick start but whose condition no longer holds now does NOT
        // divide. Funded from the settled cytoplasm (break the source bond to pay the bulk biomass/4 cost).
        if (!work.dividing) {
            var dn = 0
            for (i in genome.indices) {
                val g = genome[i]
                if (g.action.type == ActionType.Mitosis && gate(g.condition, work)) active[dn++] = i
            }
            if (dn > 0) {
                val snap = work.snapScratch.also { it.copyFrom(work.cytoplasm) }
                val quantaShare = work.quanta / dn
                for (j in 0 until dn) {
                    applyGene(genome[active[j]], work, grid, snap, totalBiomassBonds(work.biomass), dn, quantaShare)
                    if (work.dividing) break
                }
            }
        }
    }

    /** A gene is "active" — counting toward the [runGenes] bloat tax — when its condition holds AND its
     *  action isn't a guaranteed no-op this tick. So an always-on Repair gene with nothing damaged, or a
     *  flex gene already at its limit, costs the cell (and its neighbours' share of the genome) nothing. */
    private fun isActive(gene: Gene, work: CellWork): Boolean {
        if (!gate(gene.condition, work)) return false
        return when (gene.action.type) {
            ActionType.Repair -> hasConnectionDamage(work)
            ActionType.Contract -> canContract(work)
            else -> true
        }
    }

    /** Apply one active gene's whole action for the tick in a single bulk step (see [runGenes]). Each
     *  cytoplasm species the gene touches is capped at its 1/[n] share of the [snap]shot; a Light gene's
     *  energy is its [quantaShare], a BreakBond gene's energy is the bonds it cleaves (which also consume
     *  substrate and yield fragments). The op count [k] is computed up front from those caps and applied
     *  once — no loop. Matter-conserving: every per-op effect is the bulk of a conservative single op, and
     *  `k` never exceeds the snapshot share, so no pool goes negative. */
    /** Accumulate one consumed species [id] (one unit/op) into the [ids]/[per] scratch holding [cn] distinct
     *  entries, summing if [id] is already present; returns the new entry count. Allocation-free (no map). */
    private fun addConsume(ids: IntArray, per: IntArray, cn: Int, id: Int): Int {
        for (i in 0 until cn) if (ids[i] == id) { per[i]++; return cn }
        ids[cn] = id; per[cn] = 1; return cn + 1
    }

    private fun applyGene(gene: Gene, work: CellWork, grid: CytoMatterGrid, snap: MoleculeStore, snapBiomass: Int, n: Int, quantaShare: Int) {
        val src = gene.source
        val act = gene.action
        // Per-op cytoplasm consumption (action inputs + BreakBond substrate, SUMMED — so an overlap like
        // BreakBond(ab)+Convert(ab) eating 2 ab/op is counted correctly). Accumulated into the reused
        // per-work [consumeIds]/[consumePer] scratch (≤ 3 distinct species; addConsume sums collisions) so
        // there's no per-gene map allocation. Species resolved from the snapshot by forward scan
        // (lex-smallest == lowest id), so the choice is order-independent.
        val ids = work.consumeIds; val per = work.consumePer
        var cn = 0
        // BreakBond fuel: the lex-smallest molecule holding the bond, broken to release its quanta. Kept OUT
        // of the action-consume scan (tracked as [breakSpId]) because efficiency consumes it at the
        // bonds-broken rate ⌈k/gP1⌉, not 1/op; its overlap with an action input is summed back in below.
        var breakSpId = -1; var fragLId = -1; var fragRId = -1
        if (src is EnergySource.BreakBond) {
            val bondIdx = SpeciesRegistry.bondIndexOf(src.bond)
            breakSpId = firstWithBond(snap, bondIdx); if (breakSpId < 0) return
            fragLId = SpeciesRegistry.breakLeft(breakSpId, bondIdx); fragRId = SpeciesRegistry.breakRight(breakSpId, bondIdx)
            if (fragLId < 0) return
        }
        var productId = -1
        var convertId = -1
        when (act.type) {
            ActionType.Convert -> { convertId = SpeciesRegistry.id(act.a); cn = addConsume(ids, per, cn, convertId) }
            ActionType.FormBond -> {
                // Operands are a SUFFIX (act.a) and PREFIX (act.b): join the lex-smallest molecule ending
                // with act.a to the lex-smallest starting with act.b. A single-atom operand (e.g. "c") is the
                // original "ends/starts in that atom" behaviour; a longer one (e.g. "abc") is more selective,
                // so the gene targets specific molecules instead of any sharing the junction atom (and skips
                // wasting ops on, say, raw "cc"). The junction bond is act.a.last–act.b.first, as before.
                if (act.a.isEmpty() || act.b.isEmpty()) return
                val endAId = firstEndingWith(snap, act.a); if (endAId < 0) return
                val startBId = firstStartingWith(snap, act.b); if (startBId < 0) return
                productId = SpeciesRegistry.join(endAId, startBId); if (productId < 0) return   // forbidden (polymerisation) ⇒ no-op
                cn = addConsume(ids, per, cn, endAId)
                cn = addConsume(ids, per, cn, startBId)
            }
            else -> {}   // Import draws from the grid; Repair/Expand/Contract/Mitosis consume no cytoplasm
        }
        // Efficiency gear (Convert / Import / Repair only — FormBond is lossless, Mitosis is a fixed bulk
        // cost; see [Gene]): each energy unit performs gP1 = g+1 actions, but at most `energyCap` units may
        // be spent this tick. g=0 is the uncapped 1:1 baseline (every current gene behaves exactly as before).
        val eff = when (act.type) {
            ActionType.Convert, ActionType.Import, ActionType.Repair -> gene.efficiency.coerceIn(0, CytoTuning.EFFICIENCY_MAX_GEAR)
            else -> 0
        }
        val gP1 = eff + 1
        val energyCap = if (eff == 0) Int.MAX_VALUE else CytoTuning.EFFICIENCY_REF ushr eff
        // Energy units available: Light = the cell's quanta share; BreakBond = bonds it can break (one
        // quantum each), from the fuel's 1/n share. Op budget = min(units, cap) × gP1.
        val energyUnits = if (src is EnergySource.Light) quantaShare else snap.count(breakSpId) / n
        var k = (minOf(energyUnits.toLong(), energyCap.toLong()) * gP1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // BreakBond: the fuel is also broken (⌈k/gP1⌉ bonds) and may double as an action input (pBreak/op);
        // cap k so action-use + bonds-broken fit the fuel's share:  pBreak·k + ⌈k/gP1⌉ ≤ fuelShare.
        if (breakSpId >= 0) {
            var pBreak = 0
            for (i in 0 until cn) if (ids[i] == breakSpId) pBreak = per[i]
            val fuelShare = snap.count(breakSpId) / n
            k = minOf(k, (fuelShare.toLong() * gP1 / (pBreak.toLong() * gP1 + 1L)).toInt())
        }
        // action-substrate caps (action inputs' 1/n share; the BreakBond fuel is handled above)
        for (i in 0 until cn) k = minOf(k, (snap.count(ids[i]) / n) / per[i])
        when (act.type) {
            // Division is a BULK, size-scaling cost: it needs `biomass/4` energy THIS tick. Energy can't be
            // accumulated (quanta are use-or-lose; bonds are spent the tick they're broken), so it can only
            // be paid by breaking a big chunk of stored bonds in one tick — a BreakBond-powered mitosis. ANY
            // energy source is accepted here; light-division is non-viable **emergently, not by rule**: the
            // light scale is tuned so a cell's peak per-tick quanta stays well below biomass/4 for any real
            // divide size, so a Light-sourced Mitosis just never reaches the cost (k < cost ⇒ 0). The "charge
            // up to divide" comes for free — only a hoarded reserve broken in one tick clears the bar. The
            // gate may hold below the cost; it then does nothing (no accumulation toward it).
            ActionType.Mitosis -> { val cost = totalBiomassBonds(work.biomass) / 4; k = if (k >= cost) cost else 0 }
            ActionType.Import -> if (work.gridIndex < 0) k = 0
            ActionType.Repair -> k = minOf(k, repairOpsNeeded(work))
            ActionType.Contract -> k = minOf(k, flexOps(MIN_RADIUS, work.logicalRadius))
            // Sub-tick interpolation: a growth gene fills only up to its OWN gate threshold, never past it.
            // perOp 0 (an unresolved/mutated species id) disables the cap rather than indexing by -1.
            ActionType.Convert -> k = minOf(k, selfGateCap(gene.condition, qBiomass = true, qSpeciesId = -1, snapQ = snapBiomass, perOp = if (convertId >= 0) SpeciesRegistry.bondCount(convertId) else 0, snap = snap, snapBiomass = snapBiomass, work = work))
            ActionType.FormBond -> k = minOf(k, selfGateCap(gene.condition, qBiomass = false, qSpeciesId = productId, snapQ = snap.count(productId), perOp = 1, snap = snap, snapBiomass = snapBiomass, work = work))
        }
        // Metabolic slowdown with size: every op (except Mitosis, which has its own size-scaling cost above)
        // runs at `k × SCALE/(SCALE+biomass)`. A bigger cell spreads its metabolic capacity over more
        // structure, so its build/acquire rate falls while size-proportional decay keeps rising — they cross
        // at an EMERGENT size the cell can't outgrow. No hard cap: a stronger cell settles larger.
        if (act.type != ActionType.Mitosis) {
            val bio = totalBiomassBonds(work.biomass)
            k = (k.toLong() * CytoTuning.METABOLIC_BIOMASS_SCALE / (CytoTuning.METABOLIC_BIOMASS_SCALE + bio)).toInt()
        }
        if (k <= 0) return
        // Apply: action-input consumption, then the broken fuel + its fragments, then the action's output.
        // The fuel is broken at ⌈k/gP1⌉ (each broken bond powers gP1 actions); at g=0 that's k (1:1, as before).
        val bondsBroken = if (breakSpId >= 0) (k + gP1 - 1) / gP1 else 0
        for (i in 0 until cn) work.cytoplasm.add(ids[i], -k * per[i])
        if (breakSpId >= 0) {
            work.cytoplasm.add(breakSpId, -bondsBroken)
            work.cytoplasm.inc(fragLId, bondsBroken); work.cytoplasm.inc(fragRId, bondsBroken)
        }
        when (act.type) {
            ActionType.Convert -> work.biomass.inc(convertId, k)
            ActionType.FormBond -> work.cytoplasm.inc(productId, k)
            ActionType.Import -> {
                val importId = SpeciesRegistry.id(act.a)
                // Active uptake against a concentration gradient: the gene's k energy units buy fewer
                // molecules the further the cell pushes its internal level ABOVE the ambient reservoir
                // (1:1 at or below ambient — riding the free passive band — then diminishing). So filling
                // up where a species is plentiful is cheap, and concentrating it scarce/against demand is
                // dear; hoarding self-limits (a soft capacity) and nutrient-poor patches become a niche
                // only an energy-rich cell can exploit. SCALE = the excess at which yield halves.
                val excess = (work.cytoplasm.count(importId) - grid.count(work.gridIndex, importId)).coerceAtLeast(0).toLong()
                val want = (k.toLong() * CytoTuning.IMPORT_GRADIENT_SCALE / (CytoTuning.IMPORT_GRADIENT_SCALE + excess)).toInt()
                val got = grid.draw(work.gridIndex, importId, want); if (got > 0) work.cytoplasm.inc(importId, got)
            }
            ActionType.Mitosis -> { work.dividing = true; work.divideMorphogen = act.a }
            ActionType.Repair -> applyRepair(work, k)
            ActionType.Contract -> work.logicalRadius = (work.logicalRadius - FLEX_STEP * k).coerceAtLeast(MIN_RADIUS)
        }
    }

    /** Lowest-id (== lex-smallest) species in [snap] that contains bond [bondIdx], or -1 if none. */
    private fun firstWithBond(snap: MoleculeStore, bondIdx: Int): Int {
        if (bondIdx < 0) return -1
        for (i in 0 until snap.size) { val id = snap.idAt(i); if (SpeciesRegistry.containsBond(id, bondIdx)) return id }
        return -1
    }

    /** Lowest-id (lex-smallest) species in [snap] whose string ENDS WITH [suffix] (the FormBond end-A
     *  match), or -1. A single-atom suffix == "ends in that atom"; a longer one is a specific tail. */
    private fun firstEndingWith(snap: MoleculeStore, suffix: String): Int {
        if (suffix.isEmpty()) return -1
        for (i in 0 until snap.size) { val id = snap.idAt(i); if (SpeciesRegistry.string(id).endsWith(suffix)) return id }
        return -1
    }

    /** Lowest-id species in [snap] whose string STARTS WITH [prefix] (the FormBond start-B match), or -1. */
    private fun firstStartingWith(snap: MoleculeStore, prefix: String): Int {
        if (prefix.isEmpty()) return -1
        for (i in 0 until snap.size) { val id = snap.idAt(i); if (SpeciesRegistry.string(id).startsWith(prefix)) return id }
        return -1
    }

    /** Flex steps to move the radius from [lo] up to [hi] (ceil of the gap / [FLEX_STEP]); 0 if none. */
    private fun flexOps(lo: Frac, hi: Frac): Int {
        val gap = hi.raw - lo.raw
        if (gap <= 0L) return 0
        return ((gap + FLEX_STEP.raw - 1L) / FLEX_STEP.raw).toInt()
    }

    /** Repair ops to fully heal the cell's connection damage (each op heals [REPAIR_PER_OP]). */
    private fun repairOpsNeeded(work: CellWork): Int {
        var dmg = 0f
        for (v in work.connectionDamage.values) if (v > 0f) dmg += v
        if (dmg <= 0f) return 0
        return kotlin.math.ceil(dmg / REPAIR_PER_OP).toInt()
    }

    /** Heal up to `k·REPAIR_PER_OP` total damage, worst connection first (deterministic tiebreak by id). */
    private fun applyRepair(work: CellWork, k: Int) {
        var budget = k * REPAIR_PER_OP
        val order = work.connectionDamage.entries
            .filter { it.value > 0f }
            .sortedWith(compareByDescending<Map.Entry<EntityId, Float>> { it.value }.thenBy { it.key.value })
            .map { it.key }
        for (id in order) {
            if (budget <= 0f) break
            val cur = work.connectionDamage[id] ?: continue
            val heal = if (cur <= budget) cur else budget
            budget -= heal
            val left = cur - heal
            if (left <= 0f) work.connectionDamage.remove(id) else work.connectionDamage[id] = left
        }
        work.repaired = true
    }

    /** Phase 2 — cytoplasm diffuses to connected neighbours: each cell sends ⌊count/(degree+1)⌋ of each
     *  species to **each** neighbour and keeps the remainder. Snapshot-based (reads pre-diffusion
     *  counts, writes deltas, applies after) so it's order-independent and conservative; biomass does
     *  not diffuse (it's locked). */
    fun diffuse(works: Map<EntityId, CellWork>, neighbourIds: Map<EntityId, List<EntityId>>) {
        // The compute loop only ever reads a cell's *own* cytoplasm and writes to a separate delta map,
        // never to any cytoplasm — so reading the live store is identical to reading a pre-diffusion copy,
        // and the deltas are applied only after every cell is computed. Deltas allow negatives (the
        // sender's outflow), so they're plain id→int maps, not stores; lazily allocated so the (typically
        // many) isolated, degree-0 cells cost nothing.
        val delta = HashMap<EntityId, HashMap<Int, Int>>()
        for ((id, w) in works) {
            val nbrs = neighbourIds[id] ?: continue
            val degree = nbrs.size
            if (degree == 0) continue
            val selfDelta = delta.getOrPut(id) { HashMap() }
            for (i in 0 until w.cytoplasm.size) {
                val species = w.cytoplasm.idAt(i)
                val out = w.cytoplasm.countAt(i) / (degree + 1)
                if (out <= 0) continue
                // SELECTIVE UPTAKE across the membrane too: only send to neighbours that can metabolise
                // the species; the sender keeps the share meant for any that can't.
                var receivers = 0
                for (nb in nbrs) {
                    val nbWork = works[nb] ?: continue
                    if (!nbWork.handleable.canHold(species)) continue
                    val nbDelta = delta.getOrPut(nb) { HashMap() }
                    nbDelta[species] = (nbDelta[species] ?: 0) + out
                    receivers++
                }
                if (receivers > 0) selfDelta[species] = (selfDelta[species] ?: 0) - out * receivers
            }
        }
        for ((id, d) in delta) {
            val w = works.getValue(id)
            for ((species, dv) in d) {
                if (dv != 0) w.cytoplasm.add(species, dv)
            }
        }
    }

    /** Phase 3 — degradation (biomass loses bonds at a rate ∝ size, fragments return to cytoplasm),
     *  size from biomass, and the death/division decision. */
    fun finish(id: EntityId, work: CellWork, grid: CytoMatterGrid, divide: MutableList<EntityId>, destroy: MutableList<EntityId>) {
        degrade(work, grid)
        val bonds = totalBiomassBonds(work.biomass)
        if (bonds < DEATH_BIOMASS) {
            destroy.add(id)
            return
        }
        // size relaxes elastically toward the biomass baseline (matches the old growth feel) — this same
        // blend is what pulls a Contract-flexed radius back up to baseline once the gene stops.
        val target = biomassRadius(bonds)
        work.logicalRadius =
            (work.logicalRadius * RADIUS_ELASTICITY + target).div(RADIUS_ELASTICITY + 1)
        if (work.dividing) divide.add(id)
    }

    // ── gates ────────────────────────────────────────────────────────────────
    /** The gate is a conjunction: the gene fires iff **every** clause holds (empty ⇒ true). */
    private fun gate(c: GeneCondition, work: CellWork): Boolean {
        for (clause in c.clauses) if (!clauseHolds(clause, work)) return false
        return true
    }

    private fun clauseHolds(clause: Clause, work: CellWork): Boolean {
        val l = operand(clause.lhs, work)
        val r = operand(clause.rhs, work)
        return when (clause.cmp) {
            Comparison.Greater -> l > r
            Comparison.Less -> l < r
        }
    }

    /** Evaluate one side of a [Clause] to an integer: a [Operand.Constant]'s literal, or a live reading of
     *  the cell this tick (cytoplasm count / size-normalised concentration / total biomass / contact count). */
    private fun operand(op: Operand, work: CellWork): Int = when (op) {
        is Operand.Constant -> op.value
        is Operand.Chem -> work.cytoplasm.count(SpeciesRegistry.id(op.species))
        is Operand.Conc -> conc(work.cytoplasm.count(SpeciesRegistry.id(op.species)), totalBiomassBonds(work.biomass))
        Operand.Biomass -> totalBiomassBonds(work.biomass)
        Operand.Touching -> work.touchCount
    }

    /** [operand], but reading the tick-start [snap]shot (cytoplasm + [snapBiomass]) instead of live state,
     *  so a threshold derived from it is order-independent. Touching is transient (fixed for the tick). */
    private fun operandSnap(op: Operand, snap: MoleculeStore, snapBiomass: Int, work: CellWork): Int = when (op) {
        is Operand.Constant -> op.value
        is Operand.Chem -> snap.count(SpeciesRegistry.id(op.species))
        is Operand.Conc -> conc(snap.count(SpeciesRegistry.id(op.species)), snapBiomass)
        Operand.Biomass -> snapBiomass
        Operand.Touching -> work.touchCount
    }

    /** Size-normalised concentration (CytoTuning.CONC_SCALE units): molecules per unit biomass-bond. Long
     *  intermediate (count·SCALE can exceed Int for a hoarding cell); 0 when biomass is 0. Integer floor ⇒
     *  deterministic across platforms. */
    private fun conc(count: Int, biomass: Int): Int =
        if (biomass <= 0) 0 else (count.toLong() * CytoTuning.CONC_SCALE / biomass).toInt()

    /** Sub-tick interpolation cap: the max ops a growth action may do before the quantity it INCREASES
     *  (biomass, when [qBiomass]; else cytoplasm species [qSpeciesId]) crosses a threshold of THIS gene's
     *  own gate — i.e. the portion of the tick before the action would flip its own condition false. Each
     *  AND-clause that the increase would break bounds it (`Q < limit`, or `limit > Q`); the gene's cap is
     *  the **tightest** (min) over all clauses. A clause not reading Q (incl. any [Operand.Conc] — its ratio
     *  isn't linear in Q, so it's left uncapped) imposes none. Reads the snapshot so it's order-independent. */
    private fun selfGateCap(
        cond: GeneCondition, qBiomass: Boolean, qSpeciesId: Int, snapQ: Int, perOp: Int,
        snap: MoleculeStore, snapBiomass: Int, work: CellWork,
    ): Int {
        if (perOp <= 0) return Int.MAX_VALUE
        fun reads(op: Operand): Boolean = when (op) {
            is Operand.Biomass -> qBiomass
            is Operand.Chem -> !qBiomass && SpeciesRegistry.id(op.species) == qSpeciesId
            else -> false
        }
        var cap = Int.MAX_VALUE
        for (clause in cond.clauses) {
            val limit = when {
                reads(clause.lhs) && clause.cmp == Comparison.Less -> operandSnap(clause.rhs, snap, snapBiomass, work)   // Q < rhs
                reads(clause.rhs) && clause.cmp == Comparison.Greater -> operandSnap(clause.lhs, snap, snapBiomass, work) // lhs > Q
                else -> continue
            }
            val headroom = limit - snapQ
            cap = minOf(cap, if (headroom <= 0) 0 else headroom / perOp)
        }
        return cap
    }

    private fun hasConnectionDamage(work: CellWork): Boolean = work.connectionDamage.values.any { it > 0f }

    /** The biomass-derived baseline radius (the size the cell relaxes toward): `sqrt(bonds /
     *  BONDS_PER_FULL)`, floored at [MIN_RADIUS]. Flex actions bound their deviation around this. */
    private fun biomassRadius(bonds: Int): Frac =
        Frac(bonds.toLong(), BONDS_PER_FULL).sqrt().coerceAtLeast(MIN_RADIUS)

    /** Can a Contract op still move the radius (not yet at MIN_RADIUS)? Checked before spending energy so a
     *  fully-contracted gene stops drawing quanta (mirrors the Repair pre-check). */
    private fun canContract(work: CellWork): Boolean = work.logicalRadius > MIN_RADIUS

    /** Spontaneous decay: break `wear / DEGRADE_PERIOD` bonds this tick (rate ∝ biomass size), each
     *  splitting the lexicographically-smallest biomass molecule's leftmost bond. The leftmost split peels
     *  off the leading monomer (the smaller/equal fragment); that **smaller fragment is ejected to the
     *  environment** while the **larger** remainder stays in cytoplasm. So biomass decay is a real matter
     *  LEAK to the commons — a maintenance cost the cell must keep importing against (selection for
     *  efficient builders) and a steady feed for the food web — not the old free cytoplasm treadmill where
     *  both fragments stayed put and could be re-Converted for nothing. The bond's energy is still
     *  dissipated (not recovered). With no position (`gridIndex < 0`) there's nowhere to eject to, so both
     *  fragments stay in cytoplasm. */
    private fun degrade(work: CellWork, grid: CytoMatterGrid) {
        work.wear += totalBiomassBonds(work.biomass)
        var broken = work.wear / DEGRADE_PERIOD
        work.wear %= DEGRADE_PERIOD
        while (broken > 0) {
            val targetId = smallestMultiAtom(work.biomass)   // lex-smallest molecule with a bond to break
            if (targetId < 0) break
            val monoId = SpeciesRegistry.splitLeftMono(targetId)   // leading monomer (the smaller fragment)
            val restId = SpeciesRegistry.splitLeftRest(targetId)   // the larger remainder
            work.biomass.dec(targetId)
            work.cytoplasm.inc(restId, 1)                          // retain the larger fragment
            if (work.gridIndex >= 0) grid.deposit(work.gridIndex, monoId, 1) else work.cytoplasm.inc(monoId, 1)
            broken--
        }
    }

    /** Lowest-id (== lex-smallest) biomass species with at least one bond (length ≥ 2), or -1 if none. */
    private fun smallestMultiAtom(biomass: MoleculeStore): Int {
        for (i in 0 until biomass.size) { val id = biomass.idAt(i); if (SpeciesRegistry.atomCount(id) >= 2) return id }
        return -1
    }
}
