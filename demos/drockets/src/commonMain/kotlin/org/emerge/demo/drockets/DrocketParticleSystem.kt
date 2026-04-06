package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Spawns exhaust particles behind thrusting drockets, mirroring
 * the engine's ShipThrustParticleSystem but driven by DrocketStateComponent.
 */
object DrocketParticleSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        for ((entityId, ds) in DrocketsRegistry.drocketStates) {
            if (ds.phase != DrocketPhase.THRUSTING) continue
            val transform = state.raw.transforms[entityId] ?: continue
            val motion = state.raw.motions[entityId] ?: continue
            val team = state.raw.teams[entityId] ?: continue

            // Emit 1 particle per tick with some random jitter
            val angleJitter = Frac(
                state.nextRandomInt(until = Int.MAX_VALUE / 8).toLong() - Int.MAX_VALUE / 16,
            )
            var norm = Norm.fromAngle(transform.ang + angleJitter).cw90
            if (ds.walkDirection < 0) {
                norm = -norm
            }
            state.spawnParticle(
                pos = transform.pos,
                vel = motion.vel - norm * Frac(1, 1024) *
                    Frac(state.nextRandomInt(until = Int.MAX_VALUE).toLong()),
                radius = Frac(1, 2048),
                shape = BodyShape.CIRCLE,
                lifetime = 30,
                teamId = team.teamId,
            )
        }
    }
}
