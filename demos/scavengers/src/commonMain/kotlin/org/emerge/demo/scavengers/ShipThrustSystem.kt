@file:OptIn(BypassesStagedView::class)

package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.BypassesStagedView
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder

import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm

import kotlin.math.absoluteValue
import kotlin.math.sign


object ShipThrustSystem : EcsSystem<ScavengersConfig, PhysicsState, ScavengersInput> {
    override fun update(
        cfg: ScavengersConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, ScavengersInput>,
    ) {
        for ((playerId, entityId) in builder.initial.playerEntities) {
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
            val input = inputs[playerId] ?: ScavengersInput.ZERO

            val thrust = input.thrust / cfg.thrustFactorInv
            val turn = input.turn / cfg.turnFactorInv + input.thrust.absoluteValue*input.turn.sign / cfg.turnFactorInv
            val thrustAcc = Norm.fromAngle(transform.ang) * Frac(thrust.toLong())

            val angDamp = if (thrust == 0) Frac(0) else Frac(-1,20)
            val sasOutput = Frac(motion.angVel.raw.toLong()) * angDamp

            val impulse = ImpulseComponent(
                vel = thrustAcc,
                angVel = Frac(turn.toLong()) + sasOutput,
            )

            builder.update<ImpulseComponent>(entityId) { impulse + it }

            val landing = builder.getComponent<LandingAttachmentComponent>(entityId)
            if (input.thrust > 0 && landing != null) {
                val parentTransform = builder.getComponent<TransformComponent>(landing.parentEntityId)
                val parentMotion = builder.getComponent<MotionComponent>(landing.parentEntityId)
                if (parentTransform != null && parentMotion != null) {
                    val impulse = ImpulseComponent(
                        vel = surfaceVelocityAtAttachment(parentTransform, parentMotion, landing) - motion.vel,
                        angVel = parentMotion.angVel - motion.angVel,
                    )
                    builder.update<ImpulseComponent>(entityId) { impulse + it }
                }
                builder.remove<LandingAttachmentComponent>(entityId)
            }
        }
    }

    private fun surfaceVelocityAtAttachment(
        parentTransform: TransformComponent,
        parentMotion: MotionComponent,
        landing: LandingAttachmentComponent,
    ): Coord2 {
        val worldOffset = landing.relativePos.rotateByAngle(parentTransform.ang)
        return parentMotion.surfaceVelocityAtOffset(
            worldOffset.norm,
            worldOffset.len,
        )
    }
}
