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

    /** Soft-spring gains for cell connections (see SpringConstraintSystem). Connectivity
     *  relaxation keeps clusters stable, so the effective per-cell stiffness ≈ this value
     *  regardless of neighbour count — safe to push up for a snappier membrane. */
    val springStiffness: Frac = Frac(1, 1),
    val springDamping: Frac = Frac(3, 4),

    /** Repulsion impulse fraction for overlapping, non-connected cells. */
    val repulsion: Frac = Frac(1, 2),

    /** Connection breaks when accumulated stress damage exceeds this (original: 3). Higher =
     *  less fragile connections. */
    val connectionBreakDamage: Float = 8f,

    /** Per-tick velocity drag on a connected cell's unshielded velocity (original: ×-10·dt). */
    val connectedDrag: Frac = Frac(10, 64),

    /** Stretch (logical units) -> stress, for connection damage. Lower = a given stretch
     *  hurts less, so connections tolerate more deformation before they fray. */
    val connectionStressScale: Float = 0.5f,

    /** Mouse-drag pull: how hard a grabbed cell is pulled toward the pointer, and its damping. */
    val grabStiffness: Frac = Frac(1, 2),
    val grabDamping: Frac = Frac(1, 1),
) : PhysicsTuning
