package org.emerge.demo.drockets

import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Drockets tuning constants. Implements the engine-side [PhysicsTuning] contract so
 * engine systems (`GravitySystem`, `RollingResistanceSystem`, `CrashSystem`) read the
 * fields they need; [maxHealth] is Drockets-private and consumed by
 * [DrocketAdaptiveDamageSystem] as the destruction threshold for non-drocket entities.
 *
 * Scavengers-only fields (`thrustFactorInv`, `turnFactorInv`, `respawnTicks`) are
 * intentionally absent: Drockets uses its own thrust/walk systems and its respawn
 * pathway was removed as dead code in Move 5.
 */
data class DrocketsConfig(
    // Engine contract (PhysicsTuning).
    override val gravityNumerator: Frac = Frac(1, 1 shl 11),
    override val rollingResistance: Frac = Frac(1, 16),
    override val collisionSpeedDamageThreshold: Frac = Frac(1, 1 shl 20),
    // Drockets-only: destruction threshold for non-drocket entities. Drockets themselves
    // use an adaptive threshold based on population (see DrocketAdaptiveDamageSystem).
    val maxHealth: Frac = Frac(1, 512),
) : PhysicsTuning
