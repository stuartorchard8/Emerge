package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.PhysicsConfig
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
    ) {
        val particles = LinkedHashMap(state.raw.particles.asMap())
        for ((entityId, particle) in state.raw.particles.entries()) {
            val newLife = particle.life-1
            if (newLife > 0) {
                particles[entityId] = particle.copy(life = newLife)
            } else {
                particles.remove(entityId)
                state.removeEntity(entityId)
            }
        }
        state.raw = state.raw.copy(
            particles = ComponentTable.fromMap(particles),
        )
        for ((entityId, control) in state.raw.controls.entries()) {
            if (control.thrust > Random.nextInt(until = Int.MAX_VALUE)) {
                val transform = state.raw.transforms[entityId] ?: continue
                val motion = state.raw.motions[entityId] ?: continue
                val team = state.raw.teams[entityId] ?: continue
                val angleJitter = Frac(Random.nextInt(until = Int.MAX_VALUE/8).toLong()-Int.MAX_VALUE/16)
                val angleVectoring = Frac(control.turn/-16L + motion.angVel.raw.toLong()*4) // Combined turning & dampening
                val norm = Norm.fromAngle(transform.ang + angleVectoring + angleJitter)
                state.spawnParticle(
                    pos = transform.pos,
                    vel = motion.vel - norm * Frac(1,1024)*Frac(Random.nextInt(until = Int.MAX_VALUE).toLong()),
                    radius = Frac(1, 2048),
                    shape = BodyShape.CIRCLE,
                    lifetime = 30,
                    teamId = team.teamId,
                )
            }
        }
    }
}
