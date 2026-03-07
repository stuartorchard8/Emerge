package org.emerge.demo.physics

import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Frac
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Frac2
import org.emerge.sim.core.physics.Norm

fun createDefaultInitialState(): PhysicsState = PhysicsState(
        bodies = mapOf(*((0..<ScreenRenderer.MAX_BODIES).map {
            PlayerId(it) to CircleBody(
                playerId = PlayerId(it),
                pos = Norm.fromAngle(Frac(it, ScreenRenderer.MAX_BODIES.toUInt())) * (Int.MAX_VALUE/2),
                vel = Frac2(0, 0),
                ang = Frac(0),
                angVel = Frac(0),
                radius = Int.MAX_VALUE/(ScreenRenderer.MAX_BODIES)*16,
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

