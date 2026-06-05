package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.max

/**
 * Keeps connection springs in sync with the cells they join: refreshes each spring's rest
 * length to the (now possibly grown) touching distance rA+rB, accumulates stretch-stress
 * damage and breaks over-stressed connections (porting `Cell.update`'s damage loop), and
 * applies the connected-cell velocity drag ("shielding") from the original.
 *
 * Runs after biology (radii are final) and before the spring solver (so it uses fresh
 * rest lengths).
 */
object CytoConnectionMaintenanceSystem : EcsSystem<CytoConfig, SimState, CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        val springs = builder.entries<SpringConstraintComponent>()
        if (springs.isEmpty()) return

        // 1. refresh rest lengths, accumulate damage, collect breaks.
        val broken = HashMap<EntityId, MutableSet<EntityId>>()
        for ((id, comp) in springs) {
            if (comp.springs.isEmpty()) continue
            val transformA = builder.getComponent<TransformComponent>(id) ?: continue
            val radiusA = builder.getComponent<ColliderComponent>(id)?.radius ?: continue
            val damageState = builder.getComponent<ConnectionStateComponent>(id)?.damage ?: emptyMap()

            val newSprings = ArrayList<SpringConstraint>(comp.springs.size)
            val newDamage = HashMap<EntityId, Float>()
            for (spring in comp.springs) {
                val other = spring.other
                val transformB = builder.getComponent<TransformComponent>(other)
                val radiusB = builder.getComponent<ColliderComponent>(other)?.radius
                if (transformB == null || radiusB == null) continue // neighbour gone — drop

                val rest = radiusA + radiusB
                val dist = (transformB.pos - transformA.pos).len
                val stretch = CytoUnits.toLogical(dist) - CytoUnits.toLogical(rest)
                // Stress only when stretched; relaxed connections heal by 0.25/tick (original).
                val stress = max(0f, stretch * cfg.connectionStressScale) - 0.25f
                val damage = max(0f, (damageState[other] ?: 0f) + stress)

                if (damage > cfg.connectionBreakDamage) {
                    broken.getOrPut(id) { HashSet() }.add(other)
                    broken.getOrPut(other) { HashSet() }.add(id)
                } else {
                    newSprings.add(spring.copy(restLength = rest, stiffness = cfg.springStiffness, damping = cfg.springDamping))
                    newDamage[other] = damage
                }
            }
            builder.update<SpringConstraintComponent>(id) { SpringConstraintComponent(newSprings) }
            builder.update<ConnectionStateComponent>(id) { ConnectionStateComponent(newDamage) }
        }

        // 2. apply breaks on the far side too.
        for ((id, others) in broken) {
            builder.update<SpringConstraintComponent>(id) { cur ->
                SpringConstraintComponent((cur?.springs ?: emptyList()).filter { it.other !in others })
            }
            builder.update<ConnectionStateComponent>(id) { cur ->
                ConnectionStateComponent((cur?.damage ?: emptyMap()).filterKeys { it !in others })
            }
        }

        // 3. connected-cell drag ("velocity shielding"): damp the part of a cell's velocity
        // that isn't moving toward a connected neighbour.
        val drag = -cfg.connectedDrag
        for ((id, comp) in springs) {
            if (comp.springs.isEmpty()) continue
            val transformA = builder.getComponent<TransformComponent>(id) ?: continue
            val motion = builder.getComponent<MotionComponent>(id) ?: continue
            var unshielded = motion.vel.asFrac2()
            for (spring in comp.springs) {
                val transformB = builder.getComponent<TransformComponent>(spring.other) ?: continue
                val normal = (transformB.pos - transformA.pos).norm // id -> other
                val towardOther = unshielded.dot(normal)
                if (towardOther.raw > 0L) {
                    unshielded = unshielded - normal * towardOther
                }
            }
            builder.update<ImpulseComponent>(id) { ImpulseComponent(vel = unshielded * drag) + it }
        }
    }
}
