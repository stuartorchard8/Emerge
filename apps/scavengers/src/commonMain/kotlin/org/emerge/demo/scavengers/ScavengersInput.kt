package org.emerge.demo.scavengers

import org.emerge.sim.core.SimInput

/**
 * Per-tick player input for Scavengers. Two integer axes — thrust forward/back, turn
 * left/right. Read by [ShipThrustSystem]; encoded for the wire by
 * [ScavengersCodecs.inputCodec].
 */
data class ScavengersInput(val thrust: Int, val turn: Int) : SimInput {
    companion object {
        val ZERO = ScavengersInput(0, 0)
    }
}
