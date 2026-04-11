package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
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

            val up = Norm.fromAngle(transform.ang + angleJitter)
            var forward = up.cw90
            if (ds.walkDirection > 0) {
                forward = -forward
            }
            state.spawnParticle(
                pos = transform.pos + forward * Frac(1, 1024) - up * Frac(1, 2048),
                vel = motion.vel + forward * Frac(1, 1024) *
                    Frac(state.nextRandomInt(until = Int.MAX_VALUE).toLong()),
                radius = Frac(1, 2048),
                shape = BodyShape.CIRCLE,
                lifetime = 30,
                teamId = team.teamId,
            )
        }
    }
}
