package org.emerge.demo.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.model.PhysicsState

data class PhysicsFrame(
    val state: PhysicsState,
    val myId: PlayerId?,
    val tick: Long,
    val status: String,
)
