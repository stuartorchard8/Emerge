package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Cyto tuning. Implements the engine [PhysicsTuning] contract (Cyto uses no gravity or
 * rolling resistance, so those are zero), plus Cyto-specific physics knobs. The spring,
 * weld, repulsion, and damage constants are the ones that need *visual* tuning on
 * `runCyto` to feel like the original Box2D sim — they start at first-cut values.
 */
data class CytoConfig(
    override val gravityNumerator: Frac = Frac(0),
    override val rollingResistance: Frac = Frac(0),
    override val collisionSpeedDamageThreshold: Frac = Frac(0),

    /** Soft-spring gains for cell connections (see SpringConstraintSystem). */
    val springStiffness: Frac = Frac(1, 4),
    val springDamping: Frac = Frac(3, 4),

    /** Repulsion impulse fraction for overlapping, non-connected cells. */
    val repulsion: Frac = Frac(1, 2),

    /** Connection breaks when accumulated stress damage exceeds this (original: 3). */
    val connectionBreakDamage: Float = 3f,

    /** Per-tick velocity drag on a connected cell's unshielded velocity (original: ×-10·dt). */
    val connectedDrag: Frac = Frac(10, 64),

    /** Stretch (logical units) -> stress, for connection damage. */
    val connectionStressScale: Float = 1f,
) : PhysicsTuning
