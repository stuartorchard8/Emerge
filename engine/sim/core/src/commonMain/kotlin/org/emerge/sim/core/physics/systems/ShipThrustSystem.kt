package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
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

            if (input.thrust > state.nextRandomInt(until = Int.MAX_VALUE)) {
                val team = state.raw.teams[entityId] ?: continue
                val angleJitter = Frac(state.nextRandomInt(until = Int.MAX_VALUE/8).toLong()-Int.MAX_VALUE/16)
                val angleVectoring = Frac(input.turn/-16L + motion.angVel.raw.toLong()*4) // Combined turning & dampening
                val norm = Norm.fromAngle(transform.ang + angleVectoring + angleJitter)
                state.spawnParticle(
                    pos = transform.pos,
                    vel = motion.vel - norm * Frac(1,1024)*Frac(state.nextRandomInt(until = Int.MAX_VALUE).toLong()),
                    radius = Frac(1, 2048),
                    shape = BodyShape.CIRCLE,
                    lifetime = 30,
                    teamId = team.teamId,
                )
            }
        }
        state.addImpulses(impulses)
    }
}
