package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.set

object IntegrationSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val transforms = LinkedHashMap(state.raw.transforms.asMap())
        val motions = LinkedHashMap(state.raw.motions.asMap())
        for ((entityId, motion) in motions.entries) {
            val transform = state.raw.transforms[entityId] ?: continue
            val impulse = state.raw.impulses[entityId] ?: ImpulseComponent()

            // x1 = x0 + vt + half(at^2) where t=1
            val pos = transform.pos + Frac2.raw(motion.vel.x.raw, motion.vel.y.raw) + impulse.pos + impulse.vel/2
            // v1 = v0 + at
            val vel = motion.vel + impulse.vel

            val ang = transform.ang + Frac(motion.angVel.raw.toLong()) + impulse.angVel/2
            val angVel = motion.angVel + impulse.angVel

            transforms[entityId] = transform.copy(pos = pos, ang = ang)
            motions[entityId] = motion.copy(vel = vel, angVel = angVel)
        }
        state.integrate(
            transforms = ComponentTable.fromMap(transforms),
            motions = ComponentTable.fromMap(motions),
        )
    }
}
