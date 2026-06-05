package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MAX_CHEM
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.RADIUS_ELASTICITY
import org.emerge.demo.cyto.sim.runGenes
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Emitted by [CytoBiologySystem]; consumed by [CytoLifecycleSystem] in a later phase. */
data class CellDivisionIntent(val id: EntityId)
data class CellDestroyIntent(val id: EntityId)

/**
 * Cyto's chemistry + genes + growth, ported from `Cell.chemistry` / `Cell.act` and
 * `CellWorld.fixedUpdate`'s two-pass structure (all cells run chemistry, then all run
 * act). Cell biology stays in Float/logical units; the engine [ColliderComponent] radius
 * is derived from the grown logical radius. Division/death are emitted as intents for the
 * structural [CytoLifecycleSystem] to apply.
 */
object CytoBiologySystem : EcsSystem<CytoConfig, SimState, org.emerge.demo.cyto.sim.CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, org.emerge.demo.cyto.sim.CytoInput>,
    ) {
        val cells = builder.entries<CytoCellComponent>()
        if (cells.isEmpty()) return
        val springs = builder.entries<SpringConstraintComponent>()
        val dt = TIME_STEP

        // Build per-cell working state, applying last tick's pending transfers.
        val works = LinkedHashMap<EntityId, CellWork>(cells.size)
        val neighbourCounts = HashMap<EntityId, Int>(cells.size)
        for ((id, cell) in cells) {
            val chem = HashMap(cell.chemicals)
            for ((k, v) in cell.pendingTransfers) {
                chem[k] = ((chem[k] ?: 0f) + v).coerceIn(0f, MAX_CHEM)
            }
            works[id] = CellWork(
                chemicals = chem,
                transfers = HashMap(),
                suppression = HashMap(cell.suppression),
                touch = cell.touch,
                logicalRadius = cell.logicalRadius,
                divideCooldown = cell.divideCooldown,
                type = cell.type,
            )
            neighbourCounts[id] = springs[id]?.springs?.size ?: 0
        }

        // Pass 1 — chemistry: genes then enzyme reactions, for every cell.
        for ((_, work) in works) {
            runGenes(work, dt)
            work.touch = 0f
            runReactions(work)
        }

        // Pass 2 — act: diffusion, energy, growth, type behaviour, division decision.
        val divide = ArrayList<EntityId>()
        val destroy = ArrayList<EntityId>()
        for ((id, work) in works) {
            act(id, work, springs[id]?.springs, works, neighbourCounts, dt, divide, destroy)
        }

        // Write back component + collider radius.
        for ((id, work) in works) {
            builder.update<CytoCellComponent>(id) { current ->
                (current ?: CytoCellComponent(work.type, work.chemicals, work.logicalRadius)).copy(
                    chemicals = work.chemicals,
                    suppression = work.suppression,
                    logicalRadius = work.logicalRadius,
                    divideCooldown = work.divideCooldown,
                    pendingTransfers = work.transfers,
                    touch = 0f,
                    stickyTemp = work.isStickyTemp,
                )
            }
            // The collider radius is a pure function of logicalRadius; only rewrite it when
            // the radius actually moved (a settled cell holds its radius, so this skips an
            // allocation + write for the steady bulk). len() is deterministic, so equal
            // logicalRadius ⇒ identical ColliderComponent.
            val original = cells[id]
            if (original == null || work.logicalRadius != original.logicalRadius) {
                builder.update<ColliderComponent>(id) { ColliderComponent(CytoUnits.len(work.logicalRadius)) }
            }
        }

        for (id in destroy) builder.emit(CellDestroyIntent(id))
        for (id in divide) builder.emit(CellDivisionIntent(id))
    }

    private fun act(
        id: EntityId,
        work: CellWork,
        neighbours: List<SpringConstraint>?,
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

        // Diffuse chemicals to connected neighbours. Iterate the spring list directly (same
        // order as before) rather than mapping it to a fresh EntityId list per cell.
        val selfConnections = neighbours?.size ?: 0
        if (neighbours != null) for (spring in neighbours) {
            val nId = spring.other
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
    private fun runReactions(work: CellWork) {
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

    const val TIME_STEP = 1f / 64f
}
