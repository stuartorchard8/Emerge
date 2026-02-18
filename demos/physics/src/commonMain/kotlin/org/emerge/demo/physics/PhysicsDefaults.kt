package org.emerge.demo.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx

fun createDefaultInitialState(): PhysicsState = PhysicsState(
        bodies = mapOf(*((0..<128).map {
            PlayerId(it) to CircleBody(
                playerId = PlayerId(it),
                pos = Vec2Fx(it, it*15),
                vel = Vec2Fx(0, 0),
                radius = Int.MAX_VALUE/32,
            )
        }.toTypedArray()),
    )
)

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(): (PhysicsState, PlayerId) -> PhysicsState =
    { s, pid ->
        val bodies = LinkedHashMap(s.bodies)
        val radius = Int.MAX_VALUE/32
        val x = 100 + (pid.value * radius)
        val y = 250
        bodies[pid] = CircleBody(
            playerId = pid,
            pos = Vec2Fx(x, y),
            vel = Vec2Fx(0, 0),
            radius = radius,
        )
        s.copy(bodies = bodies)
    }

