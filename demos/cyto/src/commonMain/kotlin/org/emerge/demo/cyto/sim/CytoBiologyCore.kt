package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac

/**
 * The pure, storage-agnostic core of Cyto's chemistry/growth biology — per-cell `act` (diffusion +
 * energy + growth + division/death decision) and the enzyme `runReactions`, on [CellWork]s + neighbour
 * ids. Both the AoS [org.emerge.demo.cyto.sim.systems.CytoBiologySystem] and the SoA biology path call
 * this single implementation, so the result is **bit-identical by construction**.
 *
 * All chemistry is fixed-point [Frac] in `[0,1]` (1.0 = a full cell). The old `[0,10]` magnitudes were
 * rescaled ÷10 (full-size threshold 1→0.1, decay constants adjusted) — same dynamics, Frac-safe range.
 * Frac diffusion floors `v/n`; the cell keeps the undivided remainder (conservative — until the
 * depletable grid gives waste somewhere else to go).
 */
internal object CytoBiologyCore {

    private val ZERO = Frac(0, 1)
    private val ONE = Frac(1, 1)
    private val FULL_SIZE_ENERGY = Frac(1, 10)   // old "energy ≥ 1" full-size / linear-decay threshold, ÷10
    private val DECAY_SLOPE = Frac(5, 4)         // old 0.125 × (the ÷10 rescale) → 1.25·energy
    private val DECAY_INTERCEPT = Frac(1, 8)     // old +0.125 (unchanged)
    private val HALF = Frac(1, 2)

    fun act(
        id: EntityId,
        work: CellWork,
        neighbourIds: List<EntityId>?,
        works: Map<EntityId, CellWork>,
        neighbourCounts: Map<EntityId, Int>,
        dt: Frac,
        divide: MutableList<EntityId>,
        destroy: MutableList<EntityId>,
    ) {
        if (dt.sign <= 0) return
        val energy = work.chemicals["energy"] ?: ONE
        if (energy.sign <= 0) {
            destroy.add(id)
            return
        }

        // Diffuse chemicals to connected neighbours (floor split; the cell keeps the remainder).
        val selfConnections = neighbourIds?.size ?: 0
        if (neighbourIds != null) for (nId in neighbourIds) {
            val nWork = works[nId] ?: continue
            val maxConnections = maxOf(selfConnections, neighbourCounts[nId] ?: 0) + 1
            for ((k, v) in work.chemicals) {
                val totalInhibition = Frac.abs((work.suppression[k] ?: ZERO) + (nWork.suppression[k] ?: ZERO))
                val transfer = v.div(maxConnections) - totalInhibition
                if (transfer.sign > 0) {
                    work.transfers[k] = (work.transfers[k] ?: ZERO) - transfer
                    nWork.transfers[k] = (nWork.transfers[k] ?: ZERO) + transfer
                }
            }
        }

        // Energy upkeep + growth. Loss is ÷10 of the old per-tick loss (the units rescale).
        val decayBase = dt.div(10)
        var targetRadius = ONE
        if (energy >= FULL_SIZE_ENERGY) {
            work.transfers["energy"] = (work.transfers["energy"] ?: ZERO) - decayBase
        } else {
            val decay = energy * DECAY_SLOPE + DECAY_INTERCEPT
            work.transfers["energy"] = (work.transfers["energy"] ?: ZERO) - decayBase * (decay * decay)
            targetRadius = (energy / FULL_SIZE_ENERGY).sqrt()
        }

        if (work.contraction.sign > 0) {
            val chargeToUse = work.contraction.coerceAtMost(dt)
            val strength = chargeToUse / dt
            targetRadius *= ONE - strength * HALF
            work.contraction = ZERO
        }

        // Division is gene-driven: a Mitosis gene accrued `divideCharge`; at the threshold the cell
        // splits (the lifecycle resets it). Clamp ≥0 so a starved cell stalls rather than banking debt.
        if (work.divideCharge.sign < 0) work.divideCharge = ZERO
        if (work.divideCharge >= DIVIDE_THRESHOLD) divide.add(id)

        // PLACEHOLDER: Support mints energy from nothing, keyed on type — the one remaining hardcoded
        // economy; retired when Collectors absorb from the environment. Rescaled ÷10 (was +5).
        if (work.type == CellType.Support) work.transfers["energy"] = (work.transfers["energy"] ?: ZERO) + HALF

        work.logicalRadius =
            (work.logicalRadius * RADIUS_ELASTICITY + targetRadius.coerceAtLeast(MIN_RADIUS)).div(RADIUS_ELASTICITY + 1)
    }

    private class ChemReaction(val catalyst: Pair<String, String>) {
        var chemA = Frac(0, 1)
        var chemB = Frac(0, 1)
        var chemC = Frac(0, 1)
    }

    /** Enzyme-catalysed string-pattern reactions, ported from `Cell.chemistry`. */
    fun runReactions(work: CellWork) {
        if (work.enzymes.isEmpty()) return
        val intents = mutableListOf<ChemReaction>()
        for ((a, b) in work.enzymes) {
            val aMatches = work.chemicals.filter { it.key.takeLast(a.length) == a }
            val bMatches = work.chemicals.filter { it.key.take(b.length) == b }
            for (am in aMatches) for (bm in bMatches) {
                intents.add(ChemReaction(am.key to bm.key))
            }
            val ab = "$a$b"
            val abMatches = work.chemicals.filter { it.key.contains(ab) }
            for (abm in abMatches) {
                val segments = abm.key.split(ab)
                for (index in 1 until segments.size) {
                    val prefix = segments.take(index).joinToString(ab)
                    val chemA = "$prefix$a"
                    val suffix = segments.takeLast(segments.size - index).joinToString(ab)
                    val chemB = "$b$suffix"
                    intents.add(ChemReaction(chemA to chemB))
                }
            }
        }
        work.enzymes.clear()

        for ((key, value) in work.chemicals) {
            val aI = intents.filter { it.catalyst.first == key }
            val bI = intents.filter { it.catalyst.second == key }
            val cI = intents.filter { "${it.catalyst.first}${it.catalyst.second}" == key }
            val total = aI.size + bI.size + cI.size
            val allocation = value.div(total + 1)
            aI.forEach { it.chemA = allocation }
            bI.forEach { it.chemB = allocation }
            cI.forEach { it.chemC = allocation }
        }

        for (r in intents) {
            val chemA = r.catalyst.first
            val chemB = r.catalyst.second
            val chemC = "$chemA$chemB"
            val minAB = r.chemA.coerceAtMost(r.chemB)
            val diff = (minAB - r.chemC).div(2)
            if (diff.sign != 0) {
                work.chemicals[chemA] = (work.chemicals[chemA] ?: ZERO) - diff
                work.chemicals[chemB] = (work.chemicals[chemB] ?: ZERO) - diff
                work.chemicals[chemC] = (work.chemicals[chemC] ?: ZERO) + diff
            }
        }
    }
}
