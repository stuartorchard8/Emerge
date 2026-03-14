package org.emerge.sim.core.physics.components

data class ControlIntentComponent(
    val thrust: Int,
    val turn: Int,
) {
    companion object {
        val ZERO = ControlIntentComponent(
            thrust = 0,
            turn = 0,
        )
    }
}