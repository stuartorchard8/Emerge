package org.emerge.demo.physics

import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2i

fun createDefaultInitialState(): PhysicsState = PhysicsState(
        bodies = mapOf(*((0..<ScreenRenderer.MAX_BODIES).map {
            PlayerId(it) to CircleBody(
                playerId = PlayerId(it),
                pos = Vec2i(it, it*2)*(Int.MAX_VALUE/(ScreenRenderer.MAX_BODIES)),
                vel = Vec2i(0, 0),
                ang = 0,
                angVel = it*2048,
                radius = Int.MAX_VALUE/(ScreenRenderer.MAX_BODIES+it)*32,
            )
        }.toTypedArray()),
    )
)

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(): (PhysicsState, PlayerId) -> PhysicsState =
    { s, _ ->
        s   // No change to state on join
    }

