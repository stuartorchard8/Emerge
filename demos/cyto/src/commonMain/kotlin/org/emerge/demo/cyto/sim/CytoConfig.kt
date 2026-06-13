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

    /** Connection gains for the split-impulse solver (see SpringConstraintSystem). The original Cyto
     *  used a Box2D DistanceJoint at frequencyHz=10, dampingRatio=4 — *heavily overdamped*, i.e. a soft,
     *  non-oscillatory relaxation toward rest that lets connections stretch under load. We reproduce
     *  that overdamped-soft behaviour (not Box2D's literal float constants — the engine's fixed-point
     *  Frac can't hold a stiffness of ~ω² ≈ 1e5) via the solver's two channels:
     *
     *   - [springStiffness] = the position-relaxation rate: the fraction of a connection's length error
     *     pulled out per solver iteration, on the pseudo-velocity (position) channel. < 1 ⇒ soft: a
     *     loaded connection sits at a stretch ≈ load / effective-rate instead of snapping to rest. This
     *     visible stretch is what makes force-based breaking work (stretch ∝ transmitted force). Because
     *     it's a pseudo-velocity, softening it never injects kinetic energy (no drag-rectified pump).
     *   - [springDamping] = the fraction of relative normal velocity cancelled per iteration (real
     *     velocity, dissipative) — the overdamping.
     *
     *  Soft enough to stretch + tear under load, firm enough to hold a colony's shape. Tune on runCyto. */
    val springStiffness: Frac = Frac(1, 10),
    val springDamping: Frac = Frac(1, 1),

    /** Repulsion impulse fraction for overlapping, non-connected cells. */
    val repulsion: Frac = Frac(1, 2),

    /** Connection breaks when accumulated stress damage exceeds this (matches the original). Higher =
     *  less fragile connections. Breaking is heal-gated by [connectionStressScale]'s −0.25/tick floor,
     *  so only genuinely over-stretched connections (stretch past ~0.5 logical) ever accrue toward it. */
    val connectionBreakDamage: Float = 3f,

    /** Exposed-surface viscous drag (CytoDragSystem): quadratic coefficient over the exposed speed
     *  (logical units/tick), capped at full cancellation. Higher = more drag; tune for the
     *  decelerate-fast-glide-slow feel. Quadratic ⇒ v(t)=v0/(1+C·v0·t): at 0.05 a fast push (~2
     *  logical/tick) decays gracefully over ~a second while a slow drift (~0.3) barely damps. */
    val dragCoefficient: Float = 1.0f,

    /** Stretch (logical units) -> stress, for connection damage. Lower = a given stretch
     *  hurts less, so connections tolerate more deformation before they fray. */
    val connectionStressScale: Float = 0.5f,

    /** Mouse-drag pull: how hard a grabbed cell is pulled toward the pointer, and its damping. */
    val grabStiffness: Frac = Frac(1, 2),
    val grabDamping: Frac = Frac(1, 1),

    /** Variable-mass propulsion ("rocket"): when a cell's atoms change, rescale its velocity to hold
     *  momentum (shed matter → speed up). True = on (the intended physics). A diagnostic toggle —
     *  set false to ablate it and check whether colony self-propulsion comes from here. ⚙ */
    val variableMass: Boolean = true,

    /** Mouse-joint reach cap (logical units): the grab pull is computed as if the pointer is at most
     *  this far away, so a fast/far pointer can't inject a teleporting one-tick velocity (which would
     *  whip the cell's spring network). The cell then follows at up to grabStiffness × this per tick.
     *  ⚙ (Without it, dragging spikes: a far target = a 0.5·distance velocity = hundreds of units/tick.) */
    val grabMaxReach: Float = 4f,

    /** Per-tick genetic damage: each gene of every cell independently faces *each* mutation operator
     *  (threshold drift / duplication / deletion / point-mutation) with probability `1 / mutationRateDenom`
     *  every tick (not tied to division — accumulating damage both drives evolution and disrupts frozen
     *  no-division steady states). `0` disables mutation. At 1/200_000 a cell accrues well under one
     *  mutation per ~10k-tick lifetime, so most individuals persist unmodified by chance while the
     *  population as a whole explores — and deleterious mutants that die just recycle their matter to
     *  the survivors. (Was 1/10_000, which caused mutational meltdown.) ⚙ */
    val mutationRateDenom: Int = 100_000,
) : PhysicsTuning
