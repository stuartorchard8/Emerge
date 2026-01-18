package org.emerge.demo.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsState

data class AuthoritativeDemoFrame(
    val state: PhysicsState?,
    val myId: PlayerId?,
    val tick: Long,
    val status: String,
)

