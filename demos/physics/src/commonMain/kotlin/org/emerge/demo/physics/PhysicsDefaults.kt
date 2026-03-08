package org.emerge.demo.physics

import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Frac
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Frac2
import org.emerge.sim.core.physics.Norm

fun createDefaultInitialState(): PhysicsState = PhysicsState(
    bodies = mapOf(
        *((0..<ScreenRenderer.MAX_BODIES).map {
            PlayerId(it) to CircleBody(
                playerId = PlayerId(it),
                pos = Norm.fromAngle(Frac(it, ScreenRenderer.MAX_BODIES/2)) * Frac(3, 8),
                vel = Frac2.zero,
                ang = Frac(0),
                angVel = Frac(0),
                radius = Frac(1, 32),
                bounce = Frac(3, 4),
                rough = Frac(it%10+1, 10),
            )
        }.toTypedArray()),
    ),
)

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(): (PhysicsState, PlayerId) -> PhysicsState =
    { s, _ ->
        s   // No change to state on join
    }

