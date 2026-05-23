package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.model.PhysicsState

data class ScavengersFrame(
    val state: PhysicsState,
    val myId: PlayerId?,
    val tick: Long,
    val status: String,
)
