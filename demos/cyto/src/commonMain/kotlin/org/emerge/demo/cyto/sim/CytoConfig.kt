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

    /** Soft-spring gains for cell connections (see SpringConstraintSystem). Tuned to match
     *  the original Box2D DistanceJoint (frequencyHz=10, dampingRatio=4 at the 1/64 s step),
     *  which is *heavily overdamped*: connections settle smoothly toward rest with no bounce.
     *
     *  The solver is the discrete oscillator `vrel' = (1-d)·vrel - k·e`, `e' = e + vrel'`,
     *  whose modes solve `λ² - (2-k-d)λ + (1-d) = 0`. The original's sampled continuous modes
     *  are ≈{0.88, 0}; k=1/8, d=1 reproduces them as {0.875, 0} — slow-mode τ ≈ 7.5 ticks,
     *  critically/over-damped (no oscillation). Lower stiffness = softer/slower membrane;
     *  drop damping below 1 only if you want the connections to visibly bounce.
     *
     *  Connectivity relaxation scales both gains down together in dense clusters, so the
     *  overdamped *ratio* is preserved regardless of neighbour count. */
    val springStiffness: Frac = Frac(1, 8),
    val springDamping: Frac = Frac(1, 1),

    /** Repulsion impulse fraction for overlapping, non-connected cells. */
    val repulsion: Frac = Frac(1, 2),

    /** Connection breaks when accumulated stress damage exceeds this (original: 3). Higher =
     *  less fragile connections. */
    val connectionBreakDamage: Float = 4f,

    /** Per-tick velocity drag on a connected cell's unshielded velocity (original: ×-10·dt). */
    val connectedDrag: Frac = Frac(10, 64),

    /** Stretch (logical units) -> stress, for connection damage. Lower = a given stretch
     *  hurts less, so connections tolerate more deformation before they fray. */
    val connectionStressScale: Float = 0.5f,

    /** Mouse-drag pull: how hard a grabbed cell is pulled toward the pointer, and its damping. */
    val grabStiffness: Frac = Frac(1, 2),
    val grabDamping: Frac = Frac(1, 1),
) : PhysicsTuning
