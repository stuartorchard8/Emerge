package org.emerge.sim.core.physics.model

import org.emerge.sim.core.physics.primitives.Frac

data class PhysicsConfig(
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 32),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
    val gravityNumerator: Frac = Frac(1, 16),
    val collisionSpeedDamageThreshold: Frac = Frac(1, 1024 * 8),
    val maxDamage: Frac = Frac(1, 512),
    val respawnTicks: Int = 60 * 5,
)