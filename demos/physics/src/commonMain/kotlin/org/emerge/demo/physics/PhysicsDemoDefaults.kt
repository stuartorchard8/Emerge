package org.emerge.demo.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx

data class PhysicsDemoConfig(
    val worldW: Fx = Fx.fromInt(100),
    val worldH: Fx = Fx.fromInt(100),
    val radius: Fx = Fx.fromInt(8),
)

fun createDefaultInitialState(cfg: PhysicsDemoConfig): PhysicsState =
    PhysicsState(
        width = cfg.worldW,
        height = cfg.worldH,
        bodies = mapOf(
            PlayerId(0) to CircleBody(
                playerId = PlayerId(0),
                pos = Vec2Fx(Fx.fromInt(0), Fx.fromInt(0)),
                vel = Vec2Fx(Fx(0), Fx(0)),
                radius = cfg.radius,
            ),
            PlayerId(1) to CircleBody(
                playerId = PlayerId(1),
                pos = Vec2Fx(Fx.fromInt(20), Fx.fromInt(20)),
                vel = Vec2Fx(Fx(0), Fx(0)),
                radius = cfg.radius,
            ),
        ),
    )

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(cfg: PhysicsDemoConfig): (PhysicsState, PlayerId) -> PhysicsState =
    { s, pid ->
        val bodies = LinkedHashMap(s.bodies)
        val x = 100 + (pid.value * 70)
        val y = 250
        bodies[pid] = CircleBody(
            playerId = pid,
            pos = Vec2Fx(Fx.fromInt(x), Fx.fromInt(y)),
            vel = Vec2Fx(Fx(0), Fx(0)),
            radius = cfg.radius,
        )
        s.copy(bodies = bodies)
    }

