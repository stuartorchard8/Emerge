package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId

enum class DrocketPhase {
    WALKING,
    CHARGING,
    THRUSTING,
    FLYING,
}

data class DrocketStateComponent(
    val phase: DrocketPhase = DrocketPhase.WALKING,
    val planetId: EntityId,
    val walkDirection: Int = 1,
    val ticksRemaining: Int = 0,
    val fuel: Int = 0,
)
