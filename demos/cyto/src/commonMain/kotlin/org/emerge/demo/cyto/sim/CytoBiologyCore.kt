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
    /** Safety backstop on ops per gene per tick (Light is already capped by quanta; BreakBond is
     *  bounded by available bonds — this caps a pathological store from being processed all at once). ⚙ */
    const val MAX_OPS_PER_GENE = 4096
    /** Connection damage healed per Repair op (one quantum). 0.25 matches the old free per-tick heal —
     *  so ~one op/tick maintains a lightly-loaded connection; more stress needs more energy. ⚙ */
    const val REPAIR_PER_OP = 0.25f

    /** Phase 0 — passive cell↔environment exchange (FREE, down-gradient): per species, move
     *  ⌊(env − cyto)/2⌋ between the cell and its reservoir grid-cell (signed — absorb when the env is
     *  richer, leak when the cell is), halving the gradient toward equilibrium. This is how a cell feeds
     *  for free on what's around it (and how an autotroph's surplus leaks out to feed heterotrophs);
     *  concentrating *against* the gradient is the job of the energy-costing Import/Export genes. Run in
     *  a fixed cell order (cells share a grid-cell). Conservative; biomass is locked (doesn't exchange). */
    fun passiveEnvExchange(work: CellWork, grid: CytoMatterGrid) {
        val idx = work.gridIndex
        if (idx < 0) return
        val species = HashSet<String>(work.cytoplasm.keys)
        species.addAll(grid.cellAt(idx).keys)
        for (sp in species) {
            val cyto = work.cytoplasm[sp] ?: 0
            val env = grid.count(idx, sp)
            val t = (env - cyto) / 2          // signed, toward zero; +ve = into the cell
            if (t == 0) continue
            addOrRemove(work.cytoplasm, sp, t)
            if (t > 0) grid.draw(idx, sp, t) else grid.deposit(idx, sp, -t)
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
        var lightLeft = if (source is EnergySource.Light) work.quanta else 0
        var ops = 0
        // Futile-cycle guard: a gene whose source + action exactly undo each other (e.g. BreakBond(ab) +
        // FormBond(a,b) → break "ab", reform "ab") leaves the cell's mutable state unchanged across the op,
        // yet would otherwise spin to MAX_OPS_PER_GENE every tick doing net-zero work. Because such an op
        // is a no-op for the persisted state, halting on it is bit-identical to running it to the cap — so
        // we stop the gene the first time a completed op changes nothing. Import (draws from the grid → cyto
        // grows), Convert (biomass grows), and Repair (damage falls) all move the signature, so legitimate
        // throughput genes are unaffected.
        var sigPrev = stateSig(work)
        while (ops < MAX_OPS_PER_GENE) {
            if (!gate(gene.condition, work)) break  // re-checked each op: the gene acts only while its condition holds
            // Repair has no matter-feasibility of its own, so check the need BEFORE spending energy —
            // otherwise a BreakBond repair gene would burn a bond every tick even with nothing to heal.
            if (gene.action.type == ActionType.Repair && !hasConnectionDamage(work)) break
            val gotEnergy = when (source) {
                is EnergySource.Light -> if (lightLeft > 0) { lightLeft--; true } else false
                is EnergySource.BreakBond -> breakOne(work, source.bond)
            }
            if (!gotEnergy) break
            val acted = when (gene.action.type) {
                ActionType.Import -> importOne(work, grid, gene.action.a)
                ActionType.FormBond -> formBondOne(work, gene.action.a, gene.action.b)
                ActionType.Convert -> convertOne(work, gene.action.a)
                ActionType.Mitosis -> { work.dividing = true; return }  // one-shot: one quantum, then done
                ActionType.Repair -> repairOne(work)
            }
            if (!acted) break
            ops++
            val sigNow = stateSig(work)
            if (sigNow == sigPrev) break  // op produced no net change → futile cycle, no point repeating
            sigPrev = sigNow
        }
    }

    /** Order-independent signature of a cell's mutable biology state (cytoplasm + biomass +
     *  connection damage). Used by [runGene] to detect a net-zero (futile) op. Distinct per-map
     *  multipliers keep the same species in cytoplasm vs biomass from cancelling. */
    private fun stateSig(work: CellWork): Long {
        var h = 0L
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

    /** Lock one molecule of [species] from cytoplasm into biomass (grows the cell). */
    private fun convertOne(work: CellWork, species: String): Boolean {
        if ((work.cytoplasm[species] ?: 0) <= 0) return false
        addOrRemove(work.cytoplasm, species, -1)
        inc(work.biomass, species, 1)
        return true
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
