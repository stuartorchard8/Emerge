package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Applies this tick's pointer interactions ([CytoInput]), ported from Cyto's
 * `CellWorldInputProcessor.onTapEnd`: an empty tap (or explicit spawn) creates a cell with
 * surplus energy; a tap on cells applies the active [TouchMode]. Runs first in the pipeline.
 */
object CytoInteractionSystem : EcsSystem<CytoConfig, SimState, CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        val input = inputs.values.firstOrNull() ?: return

        for (spawn in input.spawns) {
            builder.spawnCell(CytoUnits.coord2(spawn.x, spawn.y), Coord2.zero, spawn.type, mapOf("energy" to 2f), MIN_RADIUS)
        }

        if (input.taps.isEmpty()) return
        val cells = builder.entries<CytoCellComponent>()
        for (tap in input.taps) {
            val hits = cells.keys.filter { id -> contains(builder, id, tap.x, tap.y) }
            if (hits.isEmpty()) {
                builder.spawnCell(CytoUnits.coord2(tap.x, tap.y), Coord2.zero, tap.type, mapOf("energy" to 2f), MIN_RADIUS)
                continue
            }
            for (id in hits) {
                when (tap.mode) {
                    TouchMode.Delete -> builder.emit(CellDestroyIntent(id))
                    TouchMode.Detach -> builder.emit(DetachIntent(id))
                    TouchMode.Sticky -> builder.update<CytoCellComponent>(id) { c -> (c ?: cells.getValue(id)).copy(sticky = true) }
                    TouchMode.Set -> builder.update<CytoCellComponent>(id) { c -> (c ?: cells.getValue(id)).copy(type = tap.type) }
                    TouchMode.Activate, TouchMode.Base -> Unit
                }
            }
        }
    }

    /** Cell contains a logical point if the point is within its radius. */
    private fun contains(builder: SimBuilder, id: EntityId, x: Float, y: Float): Boolean {
        val transform = builder.getComponent<TransformComponent>(id) ?: return false
        val radius = builder.getComponent<ColliderComponent>(id)?.radius ?: return false
        val dx = CytoUnits.toLogical(transform.pos.x) - x
        val dy = CytoUnits.toLogical(transform.pos.y) - y
        val r = CytoUnits.toLogical(radius)
        return dx * dx + dy * dy < r * r
    }
}
