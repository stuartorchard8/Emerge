package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId

data class PhysicsConfig(
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 128),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
)

data class PhysicsState(
    val bodies: Map<PlayerId, Body>,
)

data class PhysicsInput(val thrust: Int, val turn: Int) {
    companion object {
        val ZERO = PhysicsInput(0, 0)
    }
}
