package org.emerge.demo.drockets

import org.emerge.sim.core.physics.PhysicsState

data class DrocketsFrame(
    val state: PhysicsState,
    val tick: Long,
)
