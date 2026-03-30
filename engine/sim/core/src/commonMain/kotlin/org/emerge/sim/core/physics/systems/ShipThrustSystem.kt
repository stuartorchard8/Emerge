package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.collections.set
import kotlin.math.absoluteValue
import kotlin.math.sign


object ShipThrustSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        val landings = LinkedHashMap(state.raw.landings.asMap())
        for ((playerId, entityId) in state.raw.playerEntities) {
            val transform = state.raw.transforms[entityId] ?: continue
            val motion = state.raw.motions[entityId] ?: continue
            val input = inputs[playerId] ?: PhysicsInput.ZERO

            val thrust = input.thrust / cfg.thrustFactorInv
            val turn = input.turn / cfg.turnFactorInv + input.thrust.absoluteValue*input.turn.sign / cfg.turnFactorInv
            val thrustAcc = Norm.fromAngle(transform.ang) * Frac(thrust.toLong())

            val angDamp = if (thrust == 0) Frac(0) else Frac(-1,20)
            val sasOutput = Frac(motion.angVel.raw.toLong()) * angDamp

            val impulse = ImpulseComponent(
                vel = thrustAcc,
                angVel = Frac(turn.toLong()) + sasOutput,
            )

            impulses[entityId] = impulses[entityId]?.plus(impulse) ?: impulse

            val landing = landings[entityId]
            if (input.thrust > 0 && landing != null) {
                val parentTransform = state.raw.transforms[landing.parentEntityId]
                val parentMotion = state.raw.motions[landing.parentEntityId]
                if (parentTransform != null && parentMotion != null) {
                    val impulse = ImpulseComponent(
                        vel = surfaceVelocityAtAttachment(parentTransform, parentMotion, landing) - motion.vel,
                        angVel = parentMotion.angVel - motion.angVel,
                    )
                    impulses[entityId] = impulses[entityId]?.plus(impulse) ?: impulse
                }
                landings.remove(entityId)
            }
        }

        state.setLandings(ComponentTable.fromMap(landings))
        state.addImpulses(impulses)
    }

    private fun surfaceVelocityAtAttachment(
        parentTransform: TransformComponent,
        parentMotion: MotionComponent,
        landing: LandingAttachmentComponent,
    ): Coord2 {
        val worldOffset = landing.relativePos.rotateByAngle(parentTransform.ang)
        return surfaceVelocityAtOffset(
            sourceMotion = parentMotion,
            worldOffset = worldOffset,
        )
    }

    private fun surfaceVelocityAtOffset(
        sourceMotion: MotionComponent,
        worldOffset: Frac2,
    ): Coord2 {
        if (worldOffset.lenSq.raw == 0L) {
            return sourceMotion.vel
        }
        val tangent = worldOffset.norm.cw90
        val spinSpeed = worldOffset.len.toCircumference() * Frac(sourceMotion.angVel.raw.toLong())
        return sourceMotion.vel - tangent * spinSpeed
    }
}
