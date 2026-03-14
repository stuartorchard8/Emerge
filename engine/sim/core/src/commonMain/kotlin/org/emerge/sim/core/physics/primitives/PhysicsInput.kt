package org.emerge.sim.core.physics.primitives

data class PhysicsInput(val thrust: Int, val turn: Int) {
    companion object {
        val ZERO = PhysicsInput(0, 0)
    }
}