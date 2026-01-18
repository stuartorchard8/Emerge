package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId

data class CircleBody(
    val playerId: PlayerId,
    val pos: Vec2Fx,
    val vel: Vec2Fx,
    val radius: Fx,
)

data class PhysicsState(
    val width: Fx,
    val height: Fx,
    val bodies: Map<PlayerId, CircleBody>,
)

/**
 * Player input is an acceleration direction in \[-1, 0, 1] on each axis.
 */
data class PhysicsInput(val ax: Int, val ay: Int) {
    init {
        require(ax in -1..1) { "ax must be -1..1" }
        require(ay in -1..1) { "ay must be -1..1" }
    }
}

