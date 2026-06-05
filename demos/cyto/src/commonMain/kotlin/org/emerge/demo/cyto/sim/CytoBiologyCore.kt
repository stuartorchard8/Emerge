package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The pure, storage-agnostic core of Cyto's chemistry/growth biology — the per-cell `act`
 * (diffusion + energy + growth + type behaviour + division/death decision) and the enzyme
 * `runReactions`, operating only on [CellWork]s and neighbour ids. Both the array-of-structs
 * [org.emerge.demo.cyto.sim.systems.CytoBiologySystem] and the SoA biology slow-path call this
 * single implementation, so multi-species chemistry is **bit-identical by construction** rather
 * than by parallel reproduction.
 *
 * Note on determinism: a cell's per-neighbour float diffusion accumulates in neighbour order,
 * and each chemical accumulates independently of the others, so the *inner* chemical-map
 * iteration order is irrelevant to the result — only the (caller-controlled) neighbour order
 * and cell order matter.
 */
internal object CytoBiologyCore {

    fun act(
        id: EntityId,
        work: CellWork,
        neighbourIds: List<EntityId>?,
        works: Map<EntityId, CellWork>,
        neighbourCounts: Map<EntityId, Int>,
        dt: Float,
        divide: MutableList<EntityId>,
        destroy: MutableList<EntityId>,
    ) {
        if (dt <= 0f) return
        val energy = work.chemicals["energy"] ?: 1f
        if (energy <= 0f) {
            destroy.add(id)
            return
        }

        // Diffuse chemicals to connected neighbours.
        val selfConnections = neighbourIds?.size ?: 0
        if (neighbourIds != null) for (nId in neighbourIds) {
            val nWork = works[nId] ?: continue
            val maxConnections = max(selfConnections, neighbourCounts[nId] ?: 0) + 1
            for ((k, v) in work.chemicals) {
                val totalInhibition = abs((work.suppression[k] ?: 0f) + (nWork.suppression[k] ?: 0f))
                val transfer = v / maxConnections - totalInhibition
                if (transfer > 0f) {
                    work.transfers[k] = (work.transfers[k] ?: 0f) - transfer
                    nWork.transfers[k] = (nWork.transfers[k] ?: 0f) + transfer
                }
            }
        }

        var targetRadius = 1f
        if (energy >= 1f) {
            work.transfers["energy"] = (work.transfers["energy"] ?: 0f) - dt
        } else {
            val decay = energy * 0.125f + 0.125f
            work.transfers["energy"] = (work.transfers["energy"] ?: 0f) - dt * decay * decay
            targetRadius = sqrt(energy)
        }

        if (work.contraction > 0f) {
            val chargeToUse = min(work.contraction, dt)
            val strength = chargeToUse / dt
            targetRadius *= 1f - strength * 0.5f
            work.contraction = 0f
        }

        when (work.type) {
            CellType.Support -> work.transfers["energy"] = (work.transfers["energy"] ?: 0f) + 5f
            CellType.Stem -> {
                if (work.divideCooldown > 0f) {
                    work.divideCooldown -= dt
                } else if (energy > 0.5f) {
                    divide.add(id)
                }
            }
            else -> Unit
        }

        work.logicalRadius =
            (work.logicalRadius * RADIUS_ELASTICITY + max(targetRadius, MIN_RADIUS)) / (RADIUS_ELASTICITY + 1f)
    }

    private class ChemReaction(val catalyst: Pair<String, String>) {
        var chemA = 0f
        var chemB = 0f
        var chemC = 0f
    }

    /** Enzyme-catalysed string-pattern reactions, ported verbatim from `Cell.chemistry`. */
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
            val allocation = value / (total + 1f)
            aI.forEach { it.chemA = allocation }
            bI.forEach { it.chemB = allocation }
            cI.forEach { it.chemC = allocation }
        }

        for (r in intents) {
            val chemA = r.catalyst.first
            val chemB = r.catalyst.second
            val chemC = "$chemA$chemB"
            val minAB = min(r.chemA, r.chemB)
            val diff = (minAB - r.chemC) / 2f
            if (diff != 0f) {
                work.chemicals[chemA] = (work.chemicals[chemA] ?: 0f) - diff
                work.chemicals[chemB] = (work.chemicals[chemB] ?: 0f) - diff
                work.chemicals[chemC] = (work.chemicals[chemC] ?: 0f) + diff
            }
        }
    }
}
