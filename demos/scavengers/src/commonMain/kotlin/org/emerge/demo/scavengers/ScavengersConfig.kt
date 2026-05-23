package org.emerge.demo.scavengers

import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Scavengers tuning constants. Implements the engine-side [PhysicsTuning] contract so
 * engine systems (`GravitySystem`, `RollingResistanceSystem`, `CrashSystem`) read the
 * fields they need; the additional fields are Scavengers-private and consumed by the
 * demo's own systems (`ShipThrustSystem`, `DamageSystem`, `LandingSystem`,
 * `RespawnSystem`).
 *
 * Defaults match the pre-modularization `PhysicsConfig()` values verbatim so existing
 * behaviour is preserved.
 */
data class ScavengersConfig(
    // Scavengers-only: ship control scheme.
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 32),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
    // Engine contract (PhysicsTuning).
    override val gravityNumerator: Frac = Frac(1, 16),
    override val rollingResistance: Frac = Frac(1, 16),
    override val collisionSpeedDamageThreshold: Frac = Frac(1, 1024 * 8),
    // Scavengers-only: health + respawn loop.
    val maxHealth: Frac = Frac(1, 512),
    val respawnTicks: Int = 60 * 5,
) : PhysicsTuning
