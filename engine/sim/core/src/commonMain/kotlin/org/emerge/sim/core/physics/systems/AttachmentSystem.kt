package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput

object AttachmentSystem : EcsSystem<PhysicsConfig, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        for ((entityId, landing) in builder.initial.raw.landings.entries()) {
            val parentTransform = builder.getComponent<TransformComponent>(landing.parentEntityId)
            val parentMotion = builder.getComponent<MotionComponent>(landing.parentEntityId)
            val transform = builder.getComponent<TransformComponent>(entityId)
            val motion = builder.getComponent<MotionComponent>(entityId)
            if (parentTransform == null || parentMotion == null || transform == null || motion == null) {
                builder.remove<LandingAttachmentComponent>(entityId)
                continue
            }
            val outcome = TransformComponent(
                pos = parentTransform.pos + landing.relativePos.rotateByAngle(parentTransform.ang),
                ang = parentTransform.ang + landing.relativeAng,
            )
            val delta = ImpulseComponent(
                pos = outcome.pos - transform.pos,
                vel = parentMotion.vel - motion.vel,
                angVel = parentMotion.angVel - motion.angVel
                        // Trick to embed position change as velocity change.
                        + (outcome.ang - transform.ang)/4,
            )
            builder.update<ImpulseComponent>(entityId) { delta }
        }
    }
}