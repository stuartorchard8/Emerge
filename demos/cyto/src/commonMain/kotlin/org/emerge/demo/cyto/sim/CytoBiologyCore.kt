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
    private val FLEX_RANGE = CytoTuning.FLEX_RANGE

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
        val active = work.genome.filter { isActive(it, work) }
        val n = active.size
        if (n == 0) return
        val snap = work.cytoplasm.copy()          // immutable source of each gene's 1/n share
        val quantaShare = work.quanta / n
        for (gene in active) applyGene(gene, work, grid, snap, n, quantaShare)
    }

    /** A gene is "active" — counting toward the [runGenes] bloat tax — when its condition holds AND its
     *  action isn't a guaranteed no-op this tick. So an always-on Repair gene with nothing damaged, or a
     *  flex gene already at its limit, costs the cell (and its neighbours' share of the genome) nothing. */
    private fun isActive(gene: Gene, work: CellWork): Boolean {
        if (!gate(gene.condition, work)) return false
        return when (gene.action.type) {
            ActionType.Repair -> hasConnectionDamage(work)
            ActionType.Expand -> canExpand(work)
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
    private fun applyGene(gene: Gene, work: CellWork, grid: CytoMatterGrid, snap: MoleculeStore, n: Int, quantaShare: Int) {
        val src = gene.source
        val act = gene.action
        // Per-op cytoplasm consumption (action inputs + BreakBond substrate, SUMMED — so an overlap like
        // BreakBond(ab)+Convert(ab) eating 2 ab/op is counted correctly). Species resolved from the snapshot
        // by forward scan (lex-smallest == lowest id), so the choice is order-independent.
        val consume = HashMap<Int, Int>(4)
        var fragLId = -1; var fragRId = -1
        if (src is EnergySource.BreakBond) {
            val bondIdx = SpeciesRegistry.bondIndexOf(src.bond)
            val spId = firstWithBond(snap, bondIdx); if (spId < 0) return
            fragLId = SpeciesRegistry.breakLeft(spId, bondIdx); fragRId = SpeciesRegistry.breakRight(spId, bondIdx)
            if (fragLId < 0) return
            consume[spId] = (consume[spId] ?: 0) + 1
        }
        var productId = -1
        var convertId = -1
        when (act.type) {
            ActionType.Convert -> { convertId = SpeciesRegistry.id(act.a); consume[convertId] = (consume[convertId] ?: 0) + 1 }
            ActionType.FormBond -> {
                val ac = act.a.firstOrNull() ?: return
                val bc = act.b.firstOrNull() ?: return
                val endAId = firstEndingIn(snap, SpeciesRegistry.atomIndexOf(ac)); if (endAId < 0) return
                val startBId = firstStartingWith(snap, SpeciesRegistry.atomIndexOf(bc)); if (startBId < 0) return
                productId = SpeciesRegistry.join(endAId, startBId); if (productId < 0) return   // forbidden (polymerisation) ⇒ no-op
                consume[endAId] = (consume[endAId] ?: 0) + 1
                consume[startBId] = (consume[startBId] ?: 0) + 1
            }
            else -> {}   // Import draws from the grid; Repair/Expand/Contract/Mitosis consume no cytoplasm
        }
        // Op count: min over each consumed species' 1/n share, the light energy share, and action caps.
        var k = if (src is EnergySource.Light) quantaShare else Int.MAX_VALUE
        for ((s, per) in consume) k = minOf(k, (snap.count(s) / n) / per)
        when (act.type) {
            ActionType.Mitosis -> k = minOf(k, 1)
            ActionType.Import -> if (work.gridIndex < 0) k = 0
            ActionType.Repair -> k = minOf(k, repairOpsNeeded(work))
            ActionType.Expand -> k = minOf(k, flexOps(work.logicalRadius, flexMax(work)))
            ActionType.Contract -> k = minOf(k, flexOps(MIN_RADIUS, work.logicalRadius))
            ActionType.Convert -> {
                // Size cap: growth gets *less effective the bigger the cell already is*, scaling Convert
                // by (1 − biomass/MAX) so biomass asymptotes to MAX_BIOMASS_BONDS. Bounds cell radius —
                // keeps the broadphase grid fine (one runaway giant otherwise coarsens it for everyone)
                // and stops Mitosis-less mutants growing without limit. Energy/decay can't cap size here
                // (both dwarf maintenance), so the cap rides on growth.
                val room = (CytoTuning.MAX_BIOMASS_BONDS - totalBiomassBonds(work.biomass)).coerceAtLeast(0)
                k = (k.toLong() * room / CytoTuning.MAX_BIOMASS_BONDS).toInt()
            }
            else -> {}
        }
        if (k <= 0) return
        // Apply: bulk consumption, then BreakBond fragments, then the action's output.
        for ((s, per) in consume) work.cytoplasm.add(s, -k * per)
        if (fragLId >= 0) { work.cytoplasm.inc(fragLId, k); work.cytoplasm.inc(fragRId, k) }
        when (act.type) {
            ActionType.Convert -> work.biomass.inc(convertId, k)
            ActionType.FormBond -> work.cytoplasm.inc(productId, k)
            ActionType.Import -> {
                val importId = SpeciesRegistry.id(act.a)
                val got = grid.draw(work.gridIndex, importId, k); if (got > 0) work.cytoplasm.inc(importId, got)
            }
            ActionType.Mitosis -> work.dividing = true
            ActionType.Repair -> applyRepair(work, k)
            ActionType.Expand -> work.logicalRadius = (work.logicalRadius + FLEX_STEP * k).coerceIn(MIN_RADIUS, flexMax(work))
            ActionType.Contract -> work.logicalRadius = (work.logicalRadius - FLEX_STEP * k).coerceAtLeast(MIN_RADIUS)
        }
    }

    /** Lowest-id (== lex-smallest) species in [snap] that contains bond [bondIdx], or -1 if none. */
    private fun firstWithBond(snap: MoleculeStore, bondIdx: Int): Int {
        if (bondIdx < 0) return -1
        for (i in 0 until snap.size) { val id = snap.idAt(i); if (SpeciesRegistry.containsBond(id, bondIdx)) return id }
        return -1
    }

    /** Lowest-id species in [snap] whose last atom is [atomIdx] (the FormBond end-A endpoint), or -1. */
    private fun firstEndingIn(snap: MoleculeStore, atomIdx: Int): Int {
        if (atomIdx < 0) return -1
        for (i in 0 until snap.size) { val id = snap.idAt(i); if (SpeciesRegistry.lastAtom(id) == atomIdx) return id }
        return -1
    }

    /** Lowest-id species in [snap] whose first atom is [atomIdx] (the FormBond start-B endpoint), or -1. */
    private fun firstStartingWith(snap: MoleculeStore, atomIdx: Int): Int {
        if (atomIdx < 0) return -1
        for (i in 0 until snap.size) { val id = snap.idAt(i); if (SpeciesRegistry.firstAtom(id) == atomIdx) return id }
        return -1
    }

    /** Flex steps to move the radius from [lo] up to [hi] (ceil of the gap / [FLEX_STEP]); 0 if none. */
    private fun flexOps(lo: Frac, hi: Frac): Int {
        val gap = hi.raw - lo.raw
        if (gap <= 0L) return 0
        return ((gap + FLEX_STEP.raw - 1L) / FLEX_STEP.raw).toInt()
    }

    private fun flexMax(work: CellWork): Frac = biomassRadius(totalBiomassBonds(work.biomass)) + FLEX_RANGE

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
        // blend is what pulls a flexed (Expand/Contract) radius back to baseline once the gene stops.
        val target = biomassRadius(bonds)
        work.logicalRadius =
            (work.logicalRadius * RADIUS_ELASTICITY + target).div(RADIUS_ELASTICITY + 1)
        if (work.dividing) divide.add(id)
    }

    // ── gates ────────────────────────────────────────────────────────────────
    private fun gate(c: GeneCondition, work: CellWork): Boolean {
        val value = when (c.type) {
            ConditionType.ChemQty -> work.cytoplasm.count(SpeciesRegistry.id(c.species))
            ConditionType.Biomass -> totalBiomassBonds(work.biomass)
            ConditionType.Touching -> work.touchCount
        }
        return when (c.cmp) {
            Comparison.Greater -> value > c.threshold
            Comparison.Less -> value < c.threshold
        }
    }

    private fun hasConnectionDamage(work: CellWork): Boolean = work.connectionDamage.values.any { it > 0f }

    /** The biomass-derived baseline radius (the size the cell relaxes toward): `sqrt(bonds /
     *  BONDS_PER_FULL)`, floored at [MIN_RADIUS]. Flex actions bound their deviation around this. */
    private fun biomassRadius(bonds: Int): Frac =
        Frac(bonds.toLong(), BONDS_PER_FULL).sqrt().coerceAtLeast(MIN_RADIUS)

    /** Can an Expand op still move the radius (not yet at baseline+FLEX_RANGE)? Checked before spending
     *  energy so a maxed-out gene stops drawing quanta (mirrors the Repair pre-check). */
    private fun canExpand(work: CellWork): Boolean =
        work.logicalRadius < biomassRadius(totalBiomassBonds(work.biomass)) + FLEX_RANGE

    /** Can a Contract op still move the radius (not yet at MIN_RADIUS)? */
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
