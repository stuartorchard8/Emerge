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

    // ── tunable knobs (MORPHOGENESIS §v1 spec) ──────────────────────────────────
    /** light → quanta: `quanta = floor(field × exposure × SCALE)` (computed in integer `Frac` raws, no
     *  float — see the reducer's biology phase). Field peaks at ~STRENGTH (0.005), so a fully-exposed cell on a
     *  source gets ~`STRENGTH·SCALE` ops/tick. ⚙ */
    const val LIGHT_QUANTA_SCALE = 2000
    /** Degradation: a cell's wear accumulator gains its total biomass bonds each tick; every
     *  DEGRADE_PERIOD of accumulated wear breaks one bond (so decay rate ∝ size). ⚙ */
    const val DEGRADE_PERIOD = 4000
    /** Biomass bonds for a full-size (radius 1.0) cell — `radius = sqrt(bonds / BONDS_PER_FULL)`. ⚙ */
    const val BONDS_PER_FULL = 16
    /** Cell dies when total biomass falls below this (1 ⇒ dies once biomass is empty). ⚙ */
    const val DEATH_BIOMASS = 1
    /** Safety backstop on ops per gene per tick (Light is already capped by quanta; BreakBond is
     *  bounded by available bonds — this caps a pathological store from being processed all at once). ⚙ */
    const val MAX_OPS_PER_GENE = 4096
    /** Connection damage healed per Repair op (one quantum). 0.25 matches the old free per-tick heal —
     *  so ~one op/tick maintains a lightly-loaded connection; more stress needs more energy. ⚙ */
    const val REPAIR_PER_OP = 0.25f
    /** Radius moved per Expand/Contract op (one quantum). ⚙ */
    val FLEX_STEP = Frac(1, 64)
    /** Max radius deviation a gene can hold the cell at, **away from** its biomass baseline (Expand
     *  pushes up to baseline+this; Contract down to [MIN_RADIUS]). Bounds the actuator so a high-quanta
     *  cell can't balloon without limit. ⚙ */
    val FLEX_RANGE = Frac(1, 2)

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

    /** Phase 1 — execute one cell's genome: each gated gene performs up to `work.quanta` ops of its
     *  action this tick (energy is per-gene, private, use-or-lose). Import draws from [grid] (so this
     *  must run in a fixed cell order across cells). */
    fun runGenes(work: CellWork, grid: CytoMatterGrid) {
        for (gene in work.genome) runGene(gene, work, grid)
    }

    /** Execute one gated gene: repeatedly draw a quantum from its energy source and spend it on one
     *  action op, until energy runs out or the action can't proceed. Light gives up to `work.quanta`
     *  quanta; BreakBond gives one per bond it can break (so it's bounded by stored matter). */
    private fun runGene(gene: Gene, work: CellWork, grid: CytoMatterGrid) {
        val source = gene.source
        // Light is a SHARED per-cell budget: every light-sourced gene draws from the same `work.quanta`,
        // spent down here across the genome (in gene order). So a genome with many light genes must
        // budget its flux among them rather than getting a free full-power copy each — which had rewarded
        // genome bloat (more genes = more total work at no cost). A gene whose gate is satisfied stops
        // drawing, releasing the remaining budget to later genes; BreakBond genes are unaffected (their
        // energy comes from the matter they cleave, not the light pool).
        var ops = 0
        // Futile-cycle guard: a gene whose source + action exactly undo each other (e.g. BreakBond(ab) +
        // FormBond(a,b) → break "ab", reform "ab") leaves the cell's mutable state unchanged from one op to
        // the next, yet would otherwise spin to MAX_OPS_PER_GENE every tick doing net-zero work. Such an op
        // is a no-op for the persisted state, so halting on it is bit-identical to running it to the cap —
        // we stop the gene the first time two consecutive completed ops leave the state unchanged. The
        // signature is computed only once an op actually completes (acting genes are the rare case), so a
        // gated-off or energy-starved gene pays nothing. Import/Convert/Repair always move the signature,
        // so legitimate throughput genes run to their real resource limit.
        var sigPrev = 0L
        while (ops < MAX_OPS_PER_GENE) {
            if (!gate(gene.condition, work)) break  // re-checked each op: the gene acts only while its condition holds
            // Actions whose feasibility doesn't depend on consuming the quantum are checked BEFORE
            // spending energy — otherwise a BreakBond-powered gene would burn a bond every tick doing
            // nothing (Repair with nothing to heal, a flex gene already at its limit).
            if (gene.action.type == ActionType.Repair && !hasConnectionDamage(work)) break
            if (gene.action.type == ActionType.Expand && !canExpand(work)) break
            if (gene.action.type == ActionType.Contract && !canContract(work)) break
            val gotEnergy = when (source) {
                is EnergySource.Light -> if (work.quanta > 0) { work.quanta--; true } else false
                is EnergySource.BreakBond -> breakOne(work, source.bond)
            }
            if (!gotEnergy) break
            val acted = when (gene.action.type) {
                ActionType.Import -> importOne(work, grid, gene.action.a)
                ActionType.FormBond -> formBondOne(work, gene.action.a, gene.action.b)
                ActionType.Convert -> convertOne(work, gene.action.a)
                ActionType.Expand -> expandOne(work)
                ActionType.Contract -> contractOne(work)
                ActionType.Mitosis -> { work.dividing = true; return }  // one-shot: one quantum, then done
                ActionType.Repair -> repairOne(work)
            }
            if (!acted) break
            ops++
            val sigNow = stateSig(work)
            if (ops > 1 && sigNow == sigPrev) break  // two ops in a row changed nothing → futile cycle
            sigPrev = sigNow
        }
    }

    /** Order-independent signature of a cell's mutable biology state (cytoplasm + biomass +
     *  connection damage). Used by [runGene] to detect a net-zero (futile) op. Distinct per-map
     *  multipliers keep the same species in cytoplasm vs biomass from cancelling. */
    private fun stateSig(work: CellWork): Long {
        var h = work.logicalRadius.raw * 40503L   // flex actions move only the radius — keep them progressing
        for ((k, v) in work.cytoplasm) h += (k.hashCode() * 31L + 1L) * v
        for ((k, v) in work.biomass) h += (k.hashCode() * 1000003L + 7L) * v
        for ((k, v) in work.connectionDamage) h += (k.value * 2654435761L) xor v.toRawBits().toLong()
        return h
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

    // ── energy source ────────────────────────────────────────────────────────
    /** Break one instance of [bond] in the lexicographically-smallest cytoplasm molecule containing it,
     *  returning its two fragments to the cytoplasm and yielding one quantum. False if no molecule has
     *  the bond (so a BreakBond gene is bounded by the matter it can break). */
    private fun breakOne(work: CellWork, bond: String): Boolean {
        val target = work.cytoplasm.entries
            .filter { it.value > 0 && it.key.contains(bond) }
            .minByOrNull { it.key }?.key ?: return false
        val (l, r) = Molecules.breakAt(target, bond) ?: return false
        dec(work.cytoplasm, target)
        inc(work.cytoplasm, l, 1)
        inc(work.cytoplasm, r, 1)
        return true
    }

    // ── single-op actions (one per quantum) ────────────────────────────────────
    /** Import one molecule of [species] from the reservoir into the cytoplasm. */
    private fun importOne(work: CellWork, grid: CytoMatterGrid, species: String): Boolean {
        if (work.gridIndex < 0) return false
        if (grid.draw(work.gridIndex, species, 1) != 1) return false
        inc(work.cytoplasm, species, 1)
        return true
    }

    /** Form one `a–b` bond: join a cytoplasm molecule ending in atom [a] with one starting in atom [b]
     *  (lexicographically-smallest candidates); fails if no pair exists or the product would repeat a
     *  bond (polymerisation). */
    private fun formBondOne(work: CellWork, a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val ac = a[0]; val bc = b[0]
        val endA = work.cytoplasm.entries
            .filter { it.value > 0 && it.key.isNotEmpty() && it.key.last() == ac }
            .minByOrNull { it.key }?.key ?: return false
        val startB = work.cytoplasm.entries
            .filter { it.value > 0 && it.key.isNotEmpty() && it.key.first() == bc }
            .minByOrNull { it.key }?.key ?: return false
        if (endA == startB && (work.cytoplasm[endA] ?: 0) < 2) return false  // need two molecules
        val product = Molecules.join(endA, startB) ?: return false           // forbidden
        dec(work.cytoplasm, endA)
        dec(work.cytoplasm, startB)
        inc(work.cytoplasm, product, 1)
        return true
    }

    private fun hasConnectionDamage(work: CellWork): Boolean = work.connectionDamage.values.any { it > 0f }

    /** Heal the cell's most-damaged connection by [REPAIR_PER_OP] (deterministic tiebreak by neighbour
     *  id). False when nothing is damaged — so the gene's quantum loop halts and spends no more energy. */
    private fun repairOne(work: CellWork): Boolean {
        val worst = work.connectionDamage.entries
            .filter { it.value > 0f }
            .minWithOrNull(compareByDescending<Map.Entry<EntityId, Float>> { it.value }.thenBy { it.key.value })
            ?: return false
        val healed = (worst.value - REPAIR_PER_OP).coerceAtLeast(0f)
        if (healed <= 0f) work.connectionDamage.remove(worst.key) else work.connectionDamage[worst.key] = healed
        work.repaired = true
        return true
    }

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

    /** Push the radius out by [FLEX_STEP], clamped to baseline+[FLEX_RANGE]. Moves no matter. */
    private fun expandOne(work: CellWork): Boolean {
        val max = biomassRadius(totalBiomassBonds(work.biomass)) + FLEX_RANGE
        if (work.logicalRadius >= max) return false
        work.logicalRadius = (work.logicalRadius + FLEX_STEP).coerceIn(MIN_RADIUS, max)
        return true
    }

    /** Pull the radius in by [FLEX_STEP], floored at [MIN_RADIUS]. Moves no matter. */
    private fun contractOne(work: CellWork): Boolean {
        if (work.logicalRadius <= MIN_RADIUS) return false
        work.logicalRadius = (work.logicalRadius - FLEX_STEP).coerceAtLeast(MIN_RADIUS)
        return true
    }

    /** Lock one molecule of [species] from cytoplasm into biomass (grows the cell). */
    private fun convertOne(work: CellWork, species: String): Boolean {
        if ((work.cytoplasm[species] ?: 0) <= 0) return false
        addOrRemove(work.cytoplasm, species, -1)
        inc(work.biomass, species, 1)
        return true
    }

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
