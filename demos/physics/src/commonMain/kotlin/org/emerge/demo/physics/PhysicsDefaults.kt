package org.emerge.demo.physics

import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.Frac
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Frac2
import org.emerge.sim.core.physics.Norm
import org.emerge.sim.core.physics.BodyShape

fun createDefaultInitialState(): PhysicsState {
    var state = PhysicsState()
    for (it in 0 until ScreenRenderer.MAX_BODIES) {
        val playerId = PlayerId(it)
        state = if (it == 0) {
            spawnRocket(
                state = state,
                playerId = playerId,
                pos = Frac2.zero,
            )
        } else {
            state.spawnBody(
                playerId = playerId,
                pos = Norm.fromAngle(Frac(it, ScreenRenderer.MAX_BODIES)) * Frac(1, 3),
                vel = Norm.fromAngle(Frac(it, ScreenRenderer.MAX_BODIES)).perp * Frac(1, 1000),
                ang = Frac(0),
                angVel = Frac(0),
                mass = (it.toUInt() + 10u) * 100u,
                radius = Frac(it + 100, 4000),
                bounce = Frac(3, 4),
                rough = Frac(8, 16),
                shape = BodyShape.CIRCLE,
            ).first
        }
    }
    return state
}

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(): (PhysicsState, PlayerId) -> PhysicsState =
    { s, pid ->
        val existingPos = s.playerTransform(pid)?.pos ?: Frac2.zero
        val existingEntity = s.playerEntities[pid]
        if (existingEntity != null) {
            s.putBody(
                entityId = existingEntity,
                playerId = pid,
                pos = existingPos,
                vel = Frac2.zero,
                ang = Frac(0),
                angVel = Frac(0),
                mass = 1000u,
                radius = Frac(1, 160),
                bounce = Frac(3, 4),
                rough = Frac(1, 16),
                shape = BodyShape.TRIANGLE,
            )
        } else {
            spawnRocket(
                state = s,
                playerId = pid,
                pos = existingPos,
            )
        }
    }

private fun spawnRocket(
    state: PhysicsState,
    playerId: PlayerId,
    pos: Frac2,
): PhysicsState =
    state.spawnBody(
        playerId = playerId,
        pos = pos,
        vel = Frac2.zero,
        ang = Frac(0),
        angVel = Frac(0),
        mass = 1000u,
        radius = Frac(1, 160),
        bounce = Frac(3, 4),
        rough = Frac(1, 16),
        shape = BodyShape.TRIANGLE,
    ).first

