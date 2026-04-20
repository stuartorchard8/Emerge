package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId

enum class KnightPhase {
    IDLE,
    WALKING,
}

data class KnightStateComponent(
    val phase: KnightPhase = KnightPhase.IDLE,
    val planetId: EntityId,
    val walkDirection: Int = 1,
    val ticksRemaining: Int = 0,
)
