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
import org.emerge.sim.core.physics.primitives.Frac
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
        val n = springs.size

        // Cache each spring-bearing cell's components into flat arrays under a dense index,
        // so the per-spring inner loops below read transform/radius/motion/damage by array
        // index instead of hitting the component tables (same approach as the spring solver
        // and ContactSystem). Springs are symmetric in cyto, so every neighbour is in the
        // index; a neighbour that isn't (a removed cell) is treated as gone — exactly what
        // the prior null-component check did. The reads are valid for the whole phase:
        // biology (the only prior writer of radius) has already run, and nothing writes
        // transform/motion until integration in a later phase.
        val ids = arrayOfNulls<EntityId>(n)
        val index = HashMap<EntityId, Int>(n)
        val transforms = arrayOfNulls<TransformComponent>(n)
        val radii = arrayOfNulls<Frac>(n)
        val motions = arrayOfNulls<MotionComponent>(n)
        val damages = arrayOfNulls<Map<EntityId, Float>>(n)
        run {
            var i = 0
            for ((id, comp) in springs) {
                ids[i] = id
                index[id] = i
                transforms[i] = builder.getComponent<TransformComponent>(id)
                radii[i] = builder.getComponent<ColliderComponent>(id)?.radius
                motions[i] = builder.getComponent<MotionComponent>(id)
                damages[i] = builder.getComponent<ConnectionStateComponent>(id)?.damage
                i++
            }
        }

        // 1. refresh rest lengths, accumulate damage, collect breaks.
        //
        // The written component would be byte-identical to the existing one unless a rest
        // length, the configured gains, the damage, or the membership actually changed. In a
        // settled colony none of those move tick-to-tick, so we first do an allocation-free
        // detection sweep and only rebuild (the allocating path) the cells that changed.
        // This keeps the output bit-identical to an unconditional rebuild while skipping the
        // ArrayList / SpringConstraint.copy / component / HashMap churn for the steady bulk.
        val broken = HashMap<EntityId, MutableSet<EntityId>>()
        var ai = 0
        for ((id, comp) in springs) {
            val aIdx = ai++
            if (comp.springs.isEmpty()) continue
            val transformA = transforms[aIdx] ?: continue
            val radiusA = radii[aIdx] ?: continue
            val damageState = damages[aIdx] ?: emptyMap()

            // Detection sweep — no allocation. (Arithmetic mirrors the rebuild below exactly.)
            var springsChanged = false
            var damageChanged = false
            for (spring in comp.springs) {
                val other = spring.other
                val bIdx = index[other]
                val transformB = if (bIdx != null) transforms[bIdx] else null
                val radiusB = if (bIdx != null) radii[bIdx] else null
                if (transformB == null || radiusB == null) { springsChanged = true; continue }
                val rest = radiusA + radiusB
                if (rest != spring.restLength ||
                    spring.stiffness != cfg.springStiffness ||
                    spring.damping != cfg.springDamping
                ) springsChanged = true
                val dist = (transformB.pos - transformA.pos).len
                val stretch = CytoUnits.toLogical(dist) - CytoUnits.toLogical(rest)
                val stress = max(0f, stretch * cfg.connectionStressScale) - 0.25f
                val prior = damageState[other] ?: 0f
                val damage = max(0f, prior + stress)
                if (damage > cfg.connectionBreakDamage) springsChanged = true
                else if (damage != prior) damageChanged = true
            }
            if (!springsChanged && !damageChanged) continue

            // Rebuild — only reached when something genuinely changed.
            val newSprings = ArrayList<SpringConstraint>(comp.springs.size)
            val newDamage = HashMap<EntityId, Float>()
            for (spring in comp.springs) {
                val other = spring.other
                val bIdx = index[other]
                val transformB = if (bIdx != null) transforms[bIdx] else null
                val radiusB = if (bIdx != null) radii[bIdx] else null
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
        var di = 0
        for ((id, comp) in springs) {
            val aIdx = di++
            if (comp.springs.isEmpty()) continue
            val transformA = transforms[aIdx] ?: continue
            val motion = motions[aIdx] ?: continue
            var unshielded = motion.vel.asFrac2()
            for (spring in comp.springs) {
                val bIdx = index[spring.other] ?: continue
                val transformB = transforms[bIdx] ?: continue
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
