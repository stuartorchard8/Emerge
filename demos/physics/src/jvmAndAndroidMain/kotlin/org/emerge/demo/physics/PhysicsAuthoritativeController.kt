package org.emerge.demo.physics

import org.emerge.sim.core.physics.primitives.PhysicsInput

abstract class PhysicsAuthoritativeController {
    abstract fun tick(localInput: PhysicsInput): PhysicsFrame
}
