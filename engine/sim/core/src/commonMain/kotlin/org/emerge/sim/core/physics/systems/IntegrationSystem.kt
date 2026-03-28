package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.components.ControlIntentComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.collections.set
import kotlin.math.absoluteValue
import kotlin.math.sign

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
            val impulse = state.raw.impulses[entityId] ?: ImpulseComponent.ZERO

            // TODO: send controls through impulse
            val control = state.raw.controls[entityId] ?: ControlIntentComponent.ZERO
            val thrust = control.thrust / cfg.thrustFactorInv
            val turn = control.turn / cfg.turnFactorInv + control.thrust.absoluteValue*control.turn.sign / cfg.turnFactorInv
            val thrustAcc = Norm.fromAngle(transform.ang) * Frac(thrust.toLong())

            // x1 = x0 + vt + half(at^2) where t=1
            val pos = transform.pos + Frac2.raw(motion.vel.x.raw, motion.vel.y.raw) + impulse.pos + impulse.vel/2
            // v1 = v0 + at
            val vel = motion.vel + thrustAcc + impulse.vel

            val angDamp = if (thrust == 0) Frac(1,1) else Frac(19,20)
            val angVel = Frac(motion.angVel.raw.toLong()) * angDamp + Frac(turn.toLong())
            val ang = transform.ang + Frac(angVel.raw) + impulse.ang

            transforms[entityId] = transform.copy(pos = pos, ang = ang)
            motions[entityId] = motion.copy(vel = vel, angVel = Coord(angVel.raw.toInt()))
        }
        state.integrate(
            transforms = ComponentTable.fromMap(transforms),
            motions = ComponentTable.fromMap(motions),
        )
    }
}
