package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.nextRandomInt
import org.emerge.sim.core.physics.model.spawnParticle
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
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val drocketStates = builder.entries<DrocketStateComponent>()
        for ((entityId, ds) in drocketStates) {
            if (ds.phase != DrocketPhase.THRUSTING) continue
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
            val team = builder.getComponent<TeamComponent>(entityId) ?: continue

            // Emit 1 particle per tick with some random jitter
            val angleJitter = Frac(
                builder.nextRandomInt(until = Int.MAX_VALUE / 8).toLong() - Int.MAX_VALUE / 16,
            )

            val up = Norm.fromAngle(transform.ang + angleJitter)
            var forward = up.cw90
            if (ds.walkDirection > 0) {
                forward = -forward
            }
            builder.spawnParticle(
                pos = transform.pos + forward * Frac(1, 1024) - up * Frac(1, 2048),
                vel = motion.vel + forward * Frac(1, 1024) *
                    Frac(builder.nextRandomInt(until = Int.MAX_VALUE).toLong()),
                radius = Frac(1, 2048),
                shape = BodyShape.CIRCLE,
                lifetime = 30,
                teamId = team.teamId,
            )
        }
    }
}
