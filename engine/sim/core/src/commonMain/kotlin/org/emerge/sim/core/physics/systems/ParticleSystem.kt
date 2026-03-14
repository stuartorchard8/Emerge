package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
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
        val teams = LinkedHashMap(state.teams.asMap())
        for ((entityId, particle) in state.particles.entries()) {
            val newLife = particle.life-1
            if (newLife > 0) {
                particles[entityId] = particle.copy(life = newLife)
            } else {
                particles.remove(entityId)
                newState = state.removeEntity(entityId)
            }
        }
        for ((entityId, control) in state.controls.entries()) {
            if (control.thrust > Random.nextInt(until = Int.MAX_VALUE)) {
                val transform = state.transforms[entityId] ?: continue
                val motion = state.motions[entityId] ?: continue
                val teamId = teams[entityId]?.teamId
                val norm = Norm.fromAngle(transform.ang+Frac(Random.nextInt(until = Int.MAX_VALUE/6).toLong()-Int.MAX_VALUE/12))
                val particleState = newState.spawnBody(
                    playerId = null,
                    pos = transform.pos - (norm * Frac(1,1024)),
                    vel = motion.vel - norm * Frac(1,2048),
                    ang = Coord(0),
                    angVel = Coord(0),
                    mass = 0u,
                    radius = Frac(1, 2048),
                    bounce = Frac(1, 1),
                    rough = Frac(1, 1),
                    shape = BodyShape.CIRCLE,
                )
                newState = particleState.first
                val particleId = particleState.second
                particles[particleId] = ParticleComponent(60, 60)
                if (teamId != null) {
                    teams[particleId] = TeamComponent(teamId = teamId)
                }
            }
        }
        return newState.copy(
            particles = ComponentTable.fromMap(particles),
            teams = ComponentTable.fromMap(teams),
        )
    }
}
