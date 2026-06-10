package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoBiologyCore
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
        val transforms = builder.entries<org.emerge.sim.core.physics.components.TransformComponent>()
        val light = org.emerge.demo.cyto.sim.CytoLightField.default()
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
                initialSuppression = cell.suppression,
                touch = cell.touch,
                logicalRadius = cell.logicalRadius,
                divideCharge = cell.divideCharge,
                type = cell.type,
                genome = cell.genome,
                light = transforms[id]?.pos?.let { light.sampleAt(CytoUnits.toLogical(it.x), CytoUnits.toLogical(it.y)) } ?: 0f,
            )
            neighbourCounts[id] = springs[id]?.springs?.size ?: 0
        }

        // Pass 1 — chemistry: genes then enzyme reactions, for every cell.
        for ((_, work) in works) {
            runGenes(work, dt)
            work.touch = 0f
            CytoBiologyCore.runReactions(work)
        }

        // Pass 2 — act: diffusion, energy, growth, type behaviour, division decision.
        val divide = ArrayList<EntityId>()
        val destroy = ArrayList<EntityId>()
        for ((id, work) in works) {
            CytoBiologyCore.act(id, work, springs[id]?.springs?.map { it.other }, works, neighbourCounts, dt, divide, destroy)
        }

        // Write back component + collider radius.
        for ((id, work) in works) {
            builder.update<CytoCellComponent>(id) { current ->
                (current ?: CytoCellComponent(work.type, work.chemicals, work.logicalRadius)).copy(
                    chemicals = work.chemicals,
                    suppression = work.suppression,
                    logicalRadius = work.logicalRadius,
                    divideCharge = work.divideCharge,
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

    const val TIME_STEP = 1f / 64f
}
