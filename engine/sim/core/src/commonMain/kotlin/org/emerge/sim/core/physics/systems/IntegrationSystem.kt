package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput

object IntegrationSystem : EcsSystem<PhysicsTuning, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val transforms = LinkedHashMap(builder.entries<TransformComponent>())
        val motions = LinkedHashMap(builder.entries<MotionComponent>())
        for ((entityId, motion) in motions.entries) {
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val impulse = builder.getComponent<ImpulseComponent>(entityId) ?: ImpulseComponent()

            // v1 = v0 + at
            val vel = motion.vel + impulse.vel
            // p1 = p0 + v1 (v1 for better gravitational stability than v0t + 0.5at²)
            val pos = transform.pos + impulse.pos + vel.asFrac2()

            val ang = transform.ang + Frac(motion.angVel.raw.toLong()) + impulse.angVel/2
            val angVel = motion.angVel + impulse.angVel

            transforms[entityId] = transform.copy(pos = pos, ang = ang)
            motions[entityId] = motion.copy(vel = vel, angVel = angVel)
        }
        builder.setTable<TransformComponent>(transforms)
        builder.setTable<MotionComponent>(motions)
    }
}
