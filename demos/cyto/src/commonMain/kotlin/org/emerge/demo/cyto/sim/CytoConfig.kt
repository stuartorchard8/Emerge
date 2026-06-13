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

    /** Soft-spring gains for cell connections (see SpringConstraintSystem). Matched to the
     *  original Box2D DistanceJoint (frequencyHz=10, dampingRatio=4 at the 1/64 s step).
     *
     *  Discretising that joint gives stiffness k = ω²·dt² = (2π·10)²/64² ≈ 0.96 and damping
     *  d = 2ζω·dt ≈ 7.85. Stiffness is what sets the *static* stretch under load (e ≈ Δv/k),
     *  so k≈1 is what keeps the membrane firm rather than squishy. The explicit single-pass
     *  solver can't take d ≫ 1 (it would over-correct and bounce); the solver's modes solve
     *  `λ² - (2-k-d)λ + (1-d) = 0`, and at k=1 the *only* non-oscillating damping is d=1,
     *  giving a deadbeat response {0, 0}: firm, no overshoot, fast settle. That trades away
     *  the original's slow overdamped *approach* (which this integrator can't do at high k)
     *  but keeps its firmness — the visible "squish under load", which matters most here.
     *
     *  Connectivity relaxation scales both gains down together in dense clusters (Jacobi
     *  stability), so interior cells of a large colony stay softer than a lone pair; that's
     *  the main residual gap from Box2D's iterative (converged-stiff) solve. */
    val springStiffness: Frac = Frac(1, 1),
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

    /** Per-tick genetic damage: each gene of every cell independently faces *each* mutation operator
     *  (threshold drift / duplication / deletion / point-mutation) with probability `1 / mutationRateDenom`
     *  every tick (not tied to division — accumulating damage both drives evolution and disrupts frozen
     *  no-division steady states). `0` disables mutation. At 1/200_000 a cell accrues well under one
     *  mutation per ~10k-tick lifetime, so most individuals persist unmodified by chance while the
     *  population as a whole explores — and deleterious mutants that die just recycle their matter to
     *  the survivors. (Was 1/10_000, which caused mutational meltdown.) ⚙ */
    val mutationRateDenom: Int = 200_000,
) : PhysicsTuning
