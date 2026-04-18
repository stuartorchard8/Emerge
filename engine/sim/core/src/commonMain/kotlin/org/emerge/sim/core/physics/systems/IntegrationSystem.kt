package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.set

object IntegrationSystem : EcsSystem<PhysicsConfig, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val transforms = LinkedHashMap(builder.entries<TransformComponent>())
        val motions = LinkedHashMap(builder.entries<MotionComponent>())
        for ((entityId, motion) in motions.entries) {
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val impulse = builder.getComponent<ImpulseComponent>(entityId) ?: ImpulseComponent()

            // x1 = x0 + vt + half(at^2) where t=1
            val pos = transform.pos + Frac2.raw(motion.vel.x.raw, motion.vel.y.raw) + impulse.pos + impulse.vel/2
            // v1 = v0 + at
            val vel = motion.vel + impulse.vel

            val ang = transform.ang + Frac(motion.angVel.raw.toLong()) + impulse.angVel/2
            val angVel = motion.angVel + impulse.angVel

            transforms[entityId] = transform.copy(pos = pos, ang = ang)
            motions[entityId] = motion.copy(vel = vel, angVel = angVel)
        }
        builder.setTable<TransformComponent>(transforms)
        builder.setTable<MotionComponent>(motions)
    }
}
