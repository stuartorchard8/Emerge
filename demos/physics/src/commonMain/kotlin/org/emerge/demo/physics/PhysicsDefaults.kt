package org.emerge.demo.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx

data class PhysicsConfig(
    val worldHalfW: Int = Int.MAX_VALUE,
    val worldHalfH: Int = Int.MAX_VALUE,
    val radius: Int = Int.MAX_VALUE/16,
)

fun createDefaultInitialState(cfg: PhysicsConfig): PhysicsState = PhysicsState(
        halfWidth = cfg.worldHalfW,
        halfHeight = cfg.worldHalfH,
        bodies = mapOf(*((0..15).map {
            PlayerId(it) to CircleBody(
                playerId = PlayerId(it),
                pos = Vec2Fx(it, it),
                vel = Vec2Fx(0, 0),
                radius = cfg.radius,
            )
        }.toTypedArray()),
    )
)

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(cfg: PhysicsConfig): (PhysicsState, PlayerId) -> PhysicsState =
    { s, pid ->
        val bodies = LinkedHashMap(s.bodies)
        val x = 100 + (pid.value * cfg.radius)
        val y = 250
        bodies[pid] = CircleBody(
            playerId = pid,
            pos = Vec2Fx(x, y),
            vel = Vec2Fx(0, 0),
            radius = cfg.radius,
        )
        s.copy(bodies = bodies)
    }

