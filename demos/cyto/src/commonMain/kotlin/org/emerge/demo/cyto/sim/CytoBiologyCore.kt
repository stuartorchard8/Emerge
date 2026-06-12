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
     *  float — see [CytoBiologySystem]). Field peaks at ~STRENGTH (0.005), so a fully-exposed cell on a
     *  source gets ~`STRENGTH·SCALE` ops/tick. ⚙ */
    const val LIGHT_QUANTA_SCALE = 2000
    /** Degradation: a cell's wear accumulator gains its total biomass bonds each tick; every
     *  DEGRADE_PERIOD of accumulated wear breaks one bond (so decay rate ∝ size). ⚙ */
    const val DEGRADE_PERIOD = 4000
    /** Biomass bonds for a full-size (radius 1.0) cell — `radius = sqrt(bonds / BONDS_PER_FULL)`. ⚙ */
    const val BONDS_PER_FULL = 16
    /** Cell dies when total biomass falls below this (1 ⇒ dies once biomass is empty). ⚙ */
    const val DEATH_BIOMASS = 1

    /** Phase 1 — execute one cell's genome: each gated gene performs up to `work.quanta` ops of its
     *  action this tick (energy is per-gene, private, use-or-lose). Import draws from [grid] (so this
     *  must run in a fixed cell order across cells). */
    fun runGenes(work: CellWork, grid: CytoMatterGrid) {
        for (gene in work.genome) {
            if (!gate(gene.condition, work)) continue
            val budget = work.quanta
            if (budget <= 0) continue
            when (gene.action.type) {
                ActionType.Import -> {
                    val sp = gene.action.a
                    val taken = if (work.gridIndex >= 0) grid.draw(work.gridIndex, sp, budget) else 0
                    if (taken > 0) inc(work.cytoplasm, sp, taken)
                }
                ActionType.FormBond -> formBonds(work, gene.action.a, gene.action.b, budget)
                ActionType.Convert -> convert(work, gene.action.a, budget)
                ActionType.Mitosis -> work.dividing = true   // one-shot; gate + a quantum is enough
            }
        }
    }

    /** Phase 2 — cytoplasm diffuses to connected neighbours: each cell sends ⌊count/(degree+1)⌋ of each
     *  species to **each** neighbour and keeps the remainder. Snapshot-based (reads pre-diffusion
     *  counts, writes deltas, applies after) so it's order-independent and conservative; biomass does
     *  not diffuse (it's locked). */
    fun diffuse(works: Map<EntityId, CellWork>, neighbourIds: Map<EntityId, List<EntityId>>) {
        val snapshot = HashMap<EntityId, Map<String, Int>>(works.size)
        val delta = HashMap<EntityId, HashMap<String, Int>>(works.size)
        for ((id, w) in works) {
            snapshot[id] = HashMap(w.cytoplasm)
            delta[id] = HashMap()
        }
        for ((id, _) in works) {
            val nbrs = neighbourIds[id] ?: continue
            val degree = nbrs.size
            if (degree == 0) continue
            val snap = snapshot.getValue(id)
            val selfDelta = delta.getValue(id)
            for ((species, v) in snap) {
                val out = v / (degree + 1)
                if (out <= 0) continue
                selfDelta[species] = (selfDelta[species] ?: 0) - out * degree
                for (nb in nbrs) {
                    val nbDelta = delta[nb] ?: continue
                    nbDelta[species] = (nbDelta[species] ?: 0) + out
                }
            }
        }
        for ((id, w) in works) {
            for ((species, d) in delta.getValue(id)) {
                if (d != 0) addOrRemove(w.cytoplasm, species, d)
            }
        }
    }

    /** Phase 3 — degradation (biomass loses bonds at a rate ∝ size, fragments return to cytoplasm),
     *  size from biomass, and the death/division decision. */
    fun finish(id: EntityId, work: CellWork, divide: MutableList<EntityId>, destroy: MutableList<EntityId>) {
        degrade(work)
        val bonds = totalBiomassBonds(work.biomass)
        if (bonds < DEATH_BIOMASS) {
            destroy.add(id)
            return
        }
        // size = sqrt(bonds / BONDS_PER_FULL), elastically blended toward the target (matches the old
        // growth feel); Frac.sqrt keeps it deterministic.
        val target = Frac(bonds.toLong(), BONDS_PER_FULL).sqrt().coerceAtLeast(MIN_RADIUS)
        work.logicalRadius =
            (work.logicalRadius * RADIUS_ELASTICITY + target).div(RADIUS_ELASTICITY + 1)
        if (work.dividing) divide.add(id)
    }

    // ── gates ────────────────────────────────────────────────────────────────
    private fun gate(c: GeneCondition, work: CellWork): Boolean {
        val value = when (c.type) {
            ConditionType.ChemQty -> work.cytoplasm[c.species] ?: 0
            ConditionType.Biomass -> totalBiomassBonds(work.biomass)
        }
        return when (c.cmp) {
            Comparison.Greater -> value > c.threshold
            Comparison.Less -> value < c.threshold
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────
    /** Form up to [budget] `a–b` bonds: join a cytoplasm molecule ending in atom [a] with one starting
     *  in atom [b] (lexicographically-smallest candidates), refused once the product would repeat a
     *  bond (polymerisation). */
    private fun formBonds(work: CellWork, a: String, b: String, budget: Int) {
        if (a.isEmpty() || b.isEmpty()) return
        val ac = a[0]; val bc = b[0]
        var done = 0
        while (done < budget) {
            val endA = work.cytoplasm.entries
                .filter { it.value > 0 && it.key.isNotEmpty() && it.key.last() == ac }
                .minByOrNull { it.key }?.key ?: break
            val startB = work.cytoplasm.entries
                .filter { it.value > 0 && it.key.isNotEmpty() && it.key.first() == bc }
                .minByOrNull { it.key }?.key ?: break
            if (endA == startB && (work.cytoplasm[endA] ?: 0) < 2) break  // need two molecules
            val product = Molecules.join(endA, startB) ?: break           // forbidden → stop
            dec(work.cytoplasm, endA)
            dec(work.cytoplasm, startB)
            inc(work.cytoplasm, product, 1)
            done++
        }
    }

    /** Lock up to [budget] molecules of [species] from cytoplasm into biomass (grows the cell). */
    private fun convert(work: CellWork, species: String, budget: Int) {
        val avail = work.cytoplasm[species] ?: 0
        val n = if (budget < avail) budget else avail
        if (n <= 0) return
        addOrRemove(work.cytoplasm, species, -n)
        inc(work.biomass, species, n)
    }

    /** Spontaneous decay: break `wear / DEGRADE_PERIOD` bonds this tick (rate ∝ biomass size), each
     *  splitting the lexicographically-smallest biomass molecule's leftmost bond → two fragments back to
     *  cytoplasm. The bond's energy is dissipated (not recovered). */
    private fun degrade(work: CellWork) {
        work.wear += totalBiomassBonds(work.biomass)
        var broken = work.wear / DEGRADE_PERIOD
        work.wear %= DEGRADE_PERIOD
        while (broken > 0) {
            val target = work.biomass.entries
                .filter { it.value > 0 && it.key.length >= 2 }
                .minByOrNull { it.key }?.key ?: break
            val (f1, f2) = Molecules.splitLeftmost(target) ?: break
            dec(work.biomass, target)
            inc(work.cytoplasm, f1, 1)
            inc(work.cytoplasm, f2, 1)
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
