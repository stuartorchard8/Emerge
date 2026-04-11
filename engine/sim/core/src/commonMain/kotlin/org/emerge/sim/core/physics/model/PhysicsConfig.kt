package org.emerge.sim.core.physics.model

import org.emerge.sim.core.physics.primitives.Frac

data class PhysicsConfig(
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 32),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
    val gravityNumerator: Frac = Frac(1, 16),
    val shipCollisionDamageThreshold: Frac = Frac(1, 1024 * 8),
    val shipMaxDamage: Frac = Frac(1, 512),
    val shipRespawnTicks: Int = 60 * 5,
)