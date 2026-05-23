package org.emerge.sim.core.physics.model

import org.emerge.sim.core.physics.primitives.Frac

/**
 * Engine-side contract for physics tuning constants.
 *
 * Engine systems that live in `engine/sim/core/physics/systems/` read from a `cfg`
 * supplied by the demo. They do not assume any particular concrete config type —
 * they only require the handful of fields declared here. Each demo defines its
 * own `data class XxxConfig : PhysicsTuning` carrying the union of engine-required
 * tuning and demo-private tuning.
 *
 * Read by:
 *  - [org.emerge.sim.core.physics.systems.GravitySystem] — [gravityNumerator]
 *  - [org.emerge.sim.core.physics.systems.RollingResistanceSystem] — [rollingResistance]
 *  - [org.emerge.sim.core.physics.systems.CrashSystem] — [collisionSpeedDamageThreshold]
 *
 * Demo-private tuning (thrust factors, respawn windows, max-health thresholds, …)
 * lives on the concrete demo config and is consumed by demo-local systems.
 */
interface PhysicsTuning {
    val gravityNumerator: Frac
    val rollingResistance: Frac
    val collisionSpeedDamageThreshold: Frac
}
