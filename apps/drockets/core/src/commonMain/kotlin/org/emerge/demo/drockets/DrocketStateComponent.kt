package org.emerge.demo.drockets

enum class DrocketPhase {
    WALKING,
    CHARGING,
    THRUSTING,
    FLYING,
}

data class DrocketStateComponent(
    val phase: DrocketPhase = DrocketPhase.WALKING,
    val walkDirection: Int = 1,
    val ticksRemaining: Int = 0,
    val fuel: Int = 0,
)
