package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set


object LiftOffSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val motions = LinkedHashMap(state.motions.asMap())
        val landings = LinkedHashMap(state.landings.asMap())
        for ((entityId, control) in state.controls.entries()) {
            val landing = landings[entityId]
            if (control.thrust > 0 && landing != null) {
                val parentTransform = state.transforms[landing.parentEntityId]
                val parentMotion = state.motions[landing.parentEntityId]
                if (parentTransform != null && parentMotion != null) {
                    motions[entityId] =
                        MotionComponent(
                            vel = surfaceVelocityAtAttachment(parentTransform, parentMotion, landing),
                            angVel = parentMotion.angVel,
                        )
                }
                landings.remove(entityId)
            }
        }
        return state.copy(
            motions = ComponentTable.fromMap(motions),
            landings = ComponentTable.fromMap(landings),
        )
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
