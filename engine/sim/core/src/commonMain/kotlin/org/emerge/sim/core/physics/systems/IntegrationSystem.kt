package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.components.ControlIntentComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import kotlin.collections.set

object IntegrationSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val transforms = LinkedHashMap(state.transforms.asMap())
        val motions = LinkedHashMap(state.motions.asMap())
        for (entityId in state.world.entities) {
            if (state.landings.contains(entityId)) {
                continue
            }
            val transform = state.transforms[entityId] ?: continue
            val motion = state.motions[entityId] ?: continue
            val renderShape = state.renderShapes[entityId] ?: continue
            val control = state.controls[entityId] ?: ControlIntentComponent.ZERO
            val thrust = control.thrust / cfg.thrustFactorInv
            val turn = control.turn / cfg.turnFactorInv
            val acc = when (renderShape.shape) {
                BodyShape.TRIANGLE -> Norm.fromAngle(transform.ang) * Frac(thrust.toLong())
                BodyShape.CIRCLE -> Frac2.zero
            }

            val vel = motion.vel + acc
            val pos = transform.pos + Frac2.raw(vel.x.raw, vel.y.raw)

            val angVel = when (renderShape.shape) {
                BodyShape.TRIANGLE -> motion.angVel + Frac(turn.toLong())
                BodyShape.CIRCLE -> motion.angVel
            }
            val ang = transform.ang + Frac(angVel.raw.toLong())

            transforms[entityId] = transform.copy(pos = pos, ang = ang)
            motions[entityId] = motion.copy(vel = vel, angVel = angVel)
        }
        return state.copy(
            transforms = ComponentTable.fromMap(transforms),
            motions = ComponentTable.fromMap(motions),
        )
    }
}
