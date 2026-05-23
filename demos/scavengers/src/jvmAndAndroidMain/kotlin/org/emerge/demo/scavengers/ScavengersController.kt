package org.emerge.demo.scavengers

import org.emerge.sim.core.physics.primitives.PhysicsInput

abstract class ScavengersController {
    abstract fun tick(localInput: PhysicsInput): ScavengersFrame
}
