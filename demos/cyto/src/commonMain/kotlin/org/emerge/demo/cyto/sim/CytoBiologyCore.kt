package org.emerge.demo.cyto.sim

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac

/**
 * The per-cell biology of the matter model (MORPHOGENESIS.md), operating on [CellWork] + the
 * environment [CytoMatterGrid]. Everything here is integer/`Frac` and PRNG-free, so it is deterministic
 * and matter is conserved by construction (atoms are only moved between cytoplasm, biomass, and the
 * reservoir — never minted).
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
            val species = HashSet<String>(grid.cellAt(idx).keys)
            for (w in cells) species.addAll(w.cytoplasm.keys)
            for (sp in species) exchangeSpecies(idx, sp, cells, grid)
        }
    }

    /** Resolve one species' passive exchange for the cells sharing grid-cell [idx], against the snapshot
     *  `env`. Leakers deposit fully; absorbers split the snapshot proportionally when over-subscribed. */
    private fun exchangeSpecies(idx: Int, sp: String, cells: List<CellWork>, grid: CytoMatterGrid) {
        val env = grid.count(idx, sp)
        val want = IntArray(cells.size)        // each absorber's desired draw against the shared snapshot
        var demand = 0L                        // Σ want over absorbers
        for (i in cells.indices) {
            val cyto = cells[i].cytoplasm[sp] ?: 0
            val t = (env - cyto) / 2           // signed, toward zero; +ve = into the cell
            if (t < 0) {                       // leaker: always succeeds (deposits into the reservoir)
                addOrRemove(cells[i].cytoplasm, sp, t)
                grid.deposit(idx, sp, -t)
            } else if (t > 0) {
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
            addOrRemove(cells[i].cytoplasm, sp, grant[i])
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
        val snap = HashMap(work.cytoplasm)        // immutable source of each gene's 1/n share
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
    private fun applyGene(gene: Gene, work: CellWork, grid: CytoMatterGrid, snap: Map<String, Int>, n: Int, quantaShare: Int) {
        val src = gene.source
        val act = gene.action
        // Per-op cytoplasm consumption (action inputs + BreakBond substrate, SUMMED — so an overlap like
        // BreakBond(ab)+Convert(ab) eating 2 ab/op is counted correctly). Species resolved from the snapshot
        // (lex-smallest), so the choice is order-independent.
        val consume = HashMap<String, Int>()
        var fragL: String? = null; var fragR: String? = null
        if (src is EnergySource.BreakBond) {
            val sp = snap.entries.filter { it.value > 0 && it.key.contains(src.bond) }.minByOrNull { it.key }?.key ?: return
            val frags = Molecules.breakAt(sp, src.bond) ?: return
            fragL = frags.first; fragR = frags.second
            consume[sp] = (consume[sp] ?: 0) + 1
        }
        var product: String? = null
        when (act.type) {
            ActionType.Convert -> consume[act.a] = (consume[act.a] ?: 0) + 1
            ActionType.FormBond -> {
                val ac = act.a.firstOrNull() ?: return
                val bc = act.b.firstOrNull() ?: return
                val endA = snap.entries.filter { it.value > 0 && it.key.lastOrNull() == ac }.minByOrNull { it.key }?.key ?: return
                val startB = snap.entries.filter { it.value > 0 && it.key.firstOrNull() == bc }.minByOrNull { it.key }?.key ?: return
                product = Molecules.join(endA, startB) ?: return   // forbidden (polymerisation) ⇒ no-op
                consume[endA] = (consume[endA] ?: 0) + 1
                consume[startB] = (consume[startB] ?: 0) + 1
            }
            else -> {}   // Import draws from the grid; Repair/Expand/Contract/Mitosis consume no cytoplasm
        }
        // Op count: min over each consumed species' 1/n share, the light energy share, and action caps.
        var k = if (src is EnergySource.Light) quantaShare else Int.MAX_VALUE
        for ((s, per) in consume) k = minOf(k, ((snap[s] ?: 0) / n) / per)
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
        for ((s, per) in consume) addOrRemove(work.cytoplasm, s, -k * per)
        if (fragL != null) { inc(work.cytoplasm, fragL, k); inc(work.cytoplasm, fragR!!, k) }
        when (act.type) {
            ActionType.Convert -> inc(work.biomass, act.a, k)
            ActionType.FormBond -> inc(work.cytoplasm, product!!, k)
            ActionType.Import -> { val got = grid.draw(work.gridIndex, act.a, k); if (got > 0) inc(work.cytoplasm, act.a, got) }
            ActionType.Mitosis -> work.dividing = true
            ActionType.Repair -> applyRepair(work, k)
            ActionType.Expand -> work.logicalRadius = (work.logicalRadius + FLEX_STEP * k).coerceIn(MIN_RADIUS, flexMax(work))
            ActionType.Contract -> work.logicalRadius = (work.logicalRadius - FLEX_STEP * k).coerceAtLeast(MIN_RADIUS)
        }
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
        // No snapshot copy: the compute loop only ever reads a cell's *own* cytoplasm and writes to a
        // separate delta map, never to any cytoplasm — so reading the live `w.cytoplasm` is identical to
        // reading a pre-diffusion copy, and the deltas are applied only after every cell is computed.
        // Deltas are allocated lazily, so the (typically many) isolated, degree-0 cells cost nothing.
        val delta = HashMap<EntityId, HashMap<String, Int>>()
        for ((id, w) in works) {
            val nbrs = neighbourIds[id] ?: continue
            val degree = nbrs.size
            if (degree == 0) continue
            val selfDelta = delta.getOrPut(id) { HashMap() }
            for ((species, v) in w.cytoplasm) {
                val out = v / (degree + 1)
                if (out <= 0) continue
                selfDelta[species] = (selfDelta[species] ?: 0) - out * degree
                for (nb in nbrs) {
                    val nbDelta = delta.getOrPut(nb) { HashMap() }
                    nbDelta[species] = (nbDelta[species] ?: 0) + out
                }
            }
        }
        for ((id, d) in delta) {
            val w = works.getValue(id)
            for ((species, dv) in d) {
                if (dv != 0) addOrRemove(w.cytoplasm, species, dv)
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
            ConditionType.ChemQty -> work.cytoplasm[c.species] ?: 0
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
     *  off the leading monomer ([f1], always the smaller/equal fragment); that **smaller fragment is
     *  ejected to the environment** while the **larger** [f2] stays in cytoplasm. So biomass decay is a
     *  real matter LEAK to the commons — a maintenance cost the cell must keep importing against
     *  (selection for efficient builders) and a steady feed for the food web — not the old free cytoplasm
     *  treadmill where both fragments stayed put and could be re-Converted for nothing. The bond's energy
     *  is still dissipated (not recovered). With no position (`gridIndex < 0`) there's nowhere to eject to,
     *  so both fragments stay in cytoplasm. */
    private fun degrade(work: CellWork, grid: CytoMatterGrid) {
        work.wear += totalBiomassBonds(work.biomass)
        var broken = work.wear / DEGRADE_PERIOD
        work.wear %= DEGRADE_PERIOD
        while (broken > 0) {
            val target = work.biomass.entries
                .filter { it.value > 0 && it.key.length >= 2 }
                .minByOrNull { it.key }?.key ?: break
            val (f1, f2) = Molecules.splitLeftmost(target) ?: break   // f1 = leading monomer (the smaller)
            dec(work.biomass, target)
            inc(work.cytoplasm, f2, 1)                                // retain the larger fragment
            if (work.gridIndex >= 0) grid.deposit(work.gridIndex, f1, 1) else inc(work.cytoplasm, f1, 1)
            broken--
        }
    }

    // ── map helpers ────────────────────────────────────────────────────────────
    private fun inc(m: MutableMap<String, Int>, k: String, n: Int) {
        m[k] = (m[k] ?: 0) + n
    }

    private fun dec(m: MutableMap<String, Int>, k: String) = addOrRemove(m, k, -1)

    private fun addOrRemove(m: MutableMap<String, Int>, k: String, delta: Int) {
        val v = (m[k] ?: 0) + delta
        if (v <= 0) m.remove(k) else m[k] = v
    }
}
