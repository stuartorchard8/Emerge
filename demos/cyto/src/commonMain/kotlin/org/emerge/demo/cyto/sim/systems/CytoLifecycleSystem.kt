package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Applies the structural changes intents request: detach (cut all of a cell's
 * connections), destroy (remove a dead cell and its springs), weld (spring-join a
 * contacting pair), and divide (mitosis). Division is ported from
 * `CellWorld.fixedUpdate`: chemicals split in half, the daughter offset along the cell's
 * outward normal, and the mother's "ahead" connections rewired to the daughter. Runs after
 * biology/connection phases.
 */
object CytoLifecycleSystem : EcsSystem<CytoConfig, SimState, CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        // Detach: cut every connection of the named cells.
        for (intent in builder.events<DetachIntent>()) {
            for (n in neighboursOf(builder, intent.id)) removeSpringPair(builder, intent.id, n)
        }

        // Destroy: drop springs to dead cells, then remove them.
        val destroyed = HashSet<EntityId>()
        for (intent in builder.events<CellDestroyIntent>()) {
            if (!destroyed.add(intent.id)) continue
            for (n in neighboursOf(builder, intent.id)) removeSpringPair(builder, intent.id, n)
            builder.removeEntity(intent.id)
        }

        // Weld: spring-join contacting pairs (once each, skipping the just-destroyed).
        val welded = HashSet<Pair<EntityId, EntityId>>()
        for (intent in builder.events<WeldIntent>()) {
            if (intent.a in destroyed || intent.b in destroyed) continue
            if (!welded.add(intent.a to intent.b)) continue
            if (!springExists(builder, intent.a, intent.b)) addSpring(builder, intent.a, intent.b, cfg)
        }

        // Divide.
        for (intent in builder.events<CellDivisionIntent>()) {
            if (intent.id in destroyed) continue
            divide(builder, cfg, intent.id)
        }
    }

    private fun divide(builder: SimBuilder, cfg: CytoConfig, motherId: EntityId) {
        val cell = builder.getComponent<CytoCellComponent>(motherId) ?: return
        val transform = builder.getComponent<TransformComponent>(motherId) ?: return
        val motionVel = builder.getComponent<MotionComponent>(motherId)?.vel ?: Coord2.zero
        val motherPos = transform.pos
        val neighbours = neighboursOf(builder, motherId)

        // Outward normal = away from the average neighbour direction.
        var sumDelta = Frac2.zero
        for (n in neighbours) {
            val np = builder.getComponent<TransformComponent>(n)?.pos ?: continue
            sumDelta = sumDelta + (np - motherPos)
        }
        val neighbourVector = -(sumDelta / (neighbours.size + 1))
        val neighbourNormal: Norm =
            if (neighbourVector.x.raw == 0L && neighbourVector.y.raw == 0L) Norm.fromAngle(transform.ang)
            else neighbourVector.norm

        val offset = neighbourNormal * CytoUnits.len(0.25f * cell.logicalRadius)

        // Group connections by how aligned they are with the split direction.
        val ahead = ArrayList<EntityId>()
        val side = ArrayList<EntityId>()
        for (n in neighbours) {
            val np = builder.getComponent<TransformComponent>(n)?.pos ?: continue
            val toMother = (motherPos - np).norm
            val s = toMother.dot(neighbourNormal).toFloat()
            val group = if (s.absoluteValue < 0.75f) 0f else s.sign
            when (group) {
                -1f -> ahead.add(n)
                0f -> side.add(n)
            }
        }

        val halfChemicals = cell.chemicals.mapValues { it.value / 2f }
        val daughterEnergy = halfChemicals["energy"] ?: 0f
        val daughterRadius = sqrt(min(1f, daughterEnergy))

        val daughter = builder.spawnCell(
            pos = motherPos + offset,
            vel = motionVel,
            type = CellType.Stem,
            chemicals = halfChemicals,
            logicalRadius = daughterRadius,
        )

        for (n in ahead) {
            addSpring(builder, daughter, n, cfg)
            removeSpringPair(builder, motherId, n)
        }
        for (n in side) {
            addSpring(builder, daughter, n, cfg)
        }

        // Mother: step back along the split, rotate a quarter turn, halve chemicals.
        builder.update<TransformComponent>(motherId) { current ->
            (current ?: transform).copy(pos = motherPos - offset, ang = transform.ang + Frac(1, 2))
        }
        builder.update<CytoCellComponent>(motherId) { current ->
            (current ?: cell).copy(chemicals = halfChemicals, divideCooldown = 5f)
        }

        addSpring(builder, motherId, daughter, cfg)
    }
}
