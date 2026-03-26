package org.emerge.demo.physics

import org.emerge.sim.core.physics.primitives.PhysicsInput

abstract class PhysicsController {
    abstract fun tick(localInput: PhysicsInput): PhysicsFrame
}
