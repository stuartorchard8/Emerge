package org.emerge.demo.physics

import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.Body
import org.emerge.sim.core.physics.Frac
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Frac2
import org.emerge.sim.core.physics.Norm
import org.emerge.sim.core.physics.BodyShape
import org.emerge.sim.core.physics.Vec2

fun createDefaultInitialState(): PhysicsState = PhysicsState(
    bodies = mapOf(
        *((0..<ScreenRenderer.MAX_BODIES).map {
            PlayerId(it) to if (it == 0) Body.rocket(
                playerId = PlayerId(it),
                pos = Frac2.zero,
            ) else Body(
                playerId = PlayerId(it),
                pos = Norm.fromAngle(Frac(it, ScreenRenderer.MAX_BODIES)) * Frac(1, 3),
                vel = Norm.fromAngle(Frac(it, ScreenRenderer.MAX_BODIES)).perp * Frac(1, 1000),
                ang = Frac(0),
                angVel = Frac(0),
                mass = (it.toUInt()+10u)*100u,
                radius = Frac(it+100, 4000),
                bounce = Frac(3, 4),
                rough = Frac(8, 16),
                shape = BodyShape.CIRCLE,
            )
        }.toTypedArray()),
    ),
)

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(): (PhysicsState, PlayerId) -> PhysicsState =
    { s, pid ->
        val updated = LinkedHashMap(s.bodies)
        val existing = updated[pid]
        val rocket = Body.rocket(
            playerId = pid,
            pos = existing?.pos ?: Frac2.zero
        )
        updated[pid] = rocket
        s.copy(bodies = updated)
    }

