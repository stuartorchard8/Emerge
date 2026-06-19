package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Cyto's **runtime** tuning: implements the engine [PhysicsTuning] contract (Cyto has no gravity /
 * rolling resistance, so those are zero) and carries the per-instance-overridable physics + mutation
 * knobs. The default values all live in [CytoTuning] (the single tuning sheet, with the explanatory
 * docs) — edit them there; override here only when a specific run/test needs a different value
 * (e.g. tests use `cfg.copy(mutationRateDenom = …)`).
 */
data class CytoConfig(
    override val gravityNumerator: Frac = Frac(0),
    override val rollingResistance: Frac = Frac(0),
    override val collisionSpeedDamageThreshold: Frac = Frac(0),

    val springStiffness: Frac = CytoTuning.SPRING_STIFFNESS,
    val springDamping: Frac = CytoTuning.SPRING_DAMPING,
    val repulsion: Frac = CytoTuning.REPULSION,
    val contactDamping: Frac = CytoTuning.CONTACT_DAMPING,
    val maxWeldDegree: Int = CytoTuning.MAX_WELD_DEGREE,
    val compressionTolerance: Float = CytoTuning.COMPRESSION_TOLERANCE,
    val connectionBreakDamage: Float = CytoTuning.CONNECTION_BREAK_DAMAGE,
    val dragCoefficient: Float = CytoTuning.DRAG_COEFFICIENT,
    val dragMaxFraction: Float = CytoTuning.DRAG_MAX_FRACTION,
    val cellWidthDragCoefficient: Float = CytoTuning.CELL_WIDTH_DRAG_COEFFICIENT,
    val connectionStressScale: Float = CytoTuning.CONNECTION_STRESS_SCALE,
    val overStretchBreakMultiple: Float = CytoTuning.OVERSTRETCH_BREAK_MULTIPLE,
    val grabStiffness: Frac = CytoTuning.GRAB_STIFFNESS,
    val grabDamping: Frac = CytoTuning.GRAB_DAMPING,
    val variableMass: Boolean = CytoTuning.VARIABLE_MASS,
    val grabMaxReach: Float = CytoTuning.GRAB_MAX_REACH,
    val mutationRateDenom: Int = if (CytoTuning.MUTATION_ENABLED) CytoTuning.MUTATION_RATE_DENOM else 0,
) : PhysicsTuning
