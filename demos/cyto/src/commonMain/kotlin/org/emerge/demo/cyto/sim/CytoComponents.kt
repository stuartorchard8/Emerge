package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Per-cell biological state, kept in Float/logical units (as the original Cyto `Cell`
 * did). Physics — position, velocity, collider radius — lives in the engine
 * `TransformComponent`/`MotionComponent`/`ColliderComponent`; this carries everything the
 * chemistry, genes, growth, and division logic need.
 *
 * Transient within-tick scratch from the original (`enzymes`, `isStickyTemp`,
 * `contraction`, `dividing`, reaction intents) is recomputed each tick inside
 * [org.emerge.demo.cyto.sim.systems.CytoBiologySystem] rather than stored here.
 * [pendingTransfers] is the original `chemicalTransfers` map — written during a tick and
 * applied at the start of the next. [suppression] mirrors the original (accumulating)
 * `chemicalSuppression`. [touch] is contact pressure accumulated by the contact system
 * and consumed (then reset) by the biology system.
 */
data class CytoCellComponent(
    val type: CellType,
    val chemicals: Map<String, Frac>,
    val logicalRadius: Frac,
    /** Accumulated division (mitosis) warm-up; a Mitosis gene grows it, the cell splits + resets to 0
     *  at [DIVIDE_THRESHOLD]. A fresh cell starts at 0 (replaces the old count-down cooldown). */
    val divideCharge: Frac = Frac(0, 1),
    val sticky: Boolean = false,
    val pendingTransfers: Map<String, Frac> = emptyMap(),
    val suppression: Map<String, Frac> = emptyMap(),
    val touch: Frac = Frac(0, 1),
    /** Transient: gene-driven stickiness for this tick (original `isStickyTemp`). Recomputed
     *  by the biology system each tick; OR-ed with [sticky] by the contact system. */
    val stickyTemp: Boolean = false,
    /** This cell's heritable gene network (the [Gene] list driving its chemistry/behaviour).
     *  Seeded from [genomeForType] at spawn, then carried + inherited clonally on division so it
     *  can diverge under mutation — the data-driven replacement for type-keyed gene lookup. */
    val genome: List<Gene> = emptyList(),
)

/**
 * The world's depletable energy reservoir ([CytoEnergyGrid]), carried as a **singleton** component.
 *
 * The grid is global, not per-cell, so it rides on one reserved entity ([GRID_SINGLETON]) rather than
 * a real cell: this is how the otherwise-stateless AoS [org.emerge.demo.cyto.sim.CytoReducer] (which
 * rebuilds [org.emerge.sim.core.sim.SimState] every tick) persists the reservoir across ticks. The
 * live SoA path keeps the same grid as a [org.emerge.demo.cyto.sim.soa.CytoWorld] field instead and
 * re-emits this component on its singleton id only at the once-per-frame materialize boundary, so the
 * two paths stay byte-identical. The singleton is invisible to every cell/physics iteration (it carries
 * no [CytoCellComponent] / transform / collider) and is never allocated through the entity counter, so
 * it does not perturb id allocation or `lastEntityValue`.
 */
data class CytoEnergyGridComponent(val grid: CytoEnergyGrid)

/** Reserved entity id the [CytoEnergyGridComponent] singleton lives on. Sits far above any allocated
 *  cell id (allocation grows from 0) and is never added to the live-id set, so it collides with nothing
 *  and never bumps `lastEntityValue`. */
val GRID_SINGLETON = EntityId(Int.MAX_VALUE)

/**
 * Per-cell connection bookkeeping that parallels the engine
 * [org.emerge.sim.core.physics.components.SpringConstraintComponent]: the accumulated
 * stress damage for each neighbour spring. When a neighbour's damage exceeds the break
 * threshold the connection (spring) is removed. Stored symmetrically so either endpoint
 * can read/break it.
 */
data class ConnectionStateComponent(
    val damage: Map<EntityId, Float> = emptyMap(),
)
