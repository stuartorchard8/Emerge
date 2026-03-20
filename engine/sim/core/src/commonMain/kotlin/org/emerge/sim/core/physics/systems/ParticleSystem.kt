package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.set
import kotlin.random.Random

object ParticleSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        var newState = state
        val particles = LinkedHashMap(state.particles.asMap())
        for ((entityId, particle) in state.particles.entries()) {
            val newLife = particle.life-1
            if (newLife > 0) {
                particles[entityId] = particle.copy(life = newLife)
            } else {
                particles.remove(entityId)
                newState = state.removeEntity(entityId)
            }
        }
        newState = newState.copy(
            particles = ComponentTable.fromMap(particles),
        )
        for ((entityId, control) in state.controls.entries()) {
            if (control.thrust > Random.nextInt(until = Int.MAX_VALUE)) {
                val transform = state.transforms[entityId] ?: continue
                val motion = state.motions[entityId] ?: continue
                val team = state.teams[entityId] ?: continue
                val angleJitter = Frac(Random.nextInt(until = Int.MAX_VALUE/8).toLong()-Int.MAX_VALUE/16)
                val angleVectoring = Frac(control.turn/-16L + motion.angVel.raw.toLong()*4) // Combined turning & dampening
                val norm = Norm.fromAngle(transform.ang + angleVectoring + angleJitter)
                val particleState = newState.spawnParticle(
                    pos = transform.pos,
                    vel = motion.vel - norm * Frac(1,1024)*Frac(Random.nextInt(until = Int.MAX_VALUE).toLong()),
                    radius = Frac(1, 2048),
                    shape = BodyShape.CIRCLE,
                    lifetime = 30,
                    teamId = team.teamId,
                )
                newState = particleState.first
            }
        }
        return newState
    }
}
