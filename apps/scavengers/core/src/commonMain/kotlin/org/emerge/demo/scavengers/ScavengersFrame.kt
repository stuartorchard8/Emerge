package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId

data class ScavengersFrame(
    val state: ScavengersState,
    val myId: PlayerId?,
    val tick: Long,
    val status: String,
)
