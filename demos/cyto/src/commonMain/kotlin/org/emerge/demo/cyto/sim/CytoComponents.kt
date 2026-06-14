package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Per-cell biological state (matter model, MORPHOGENESIS.md). Matter is integer molecule counts in two
 * pools: mobile [cytoplasm] (genes act on it; diffuses to connected neighbours) and locked [biomass]
 * (structure — sets the cell's size, non-transferable until it degrades or the cell dies). There is no
 * `energy` chemical / pool: energy is a transient per-tick flux (light) the biology spends and forgets.
 *
 * Physics (position/velocity/collider radius) lives in the engine components; this carries what the
 * genes, growth, division, and death logic need. [logicalRadius] is derived from total biomass.
 * [wear] is the degradation accumulator carried across ticks. [genome] is heritable (seeded from
 * [genomeForType], inherited clonally on division). [sticky]/[stickyTemp] drive contact welding.
 */
data class CytoCellComponent(
    val type: CellType,
    val logicalRadius: Frac,
    val cytoplasm: Map<String, Int> = emptyMap(),
    val biomass: Map<String, Int> = emptyMap(),
    val genome: List<Gene> = emptyList(),
    val wear: Int = 0,
    val sticky: Boolean = false,
    /** Transient: gene-driven stickiness for this tick; OR-ed with [sticky] by the contact system. */
    val stickyTemp: Boolean = false,
)

/**
 * The world's finite **matter reservoir** ([CytoMatterGrid]), carried as a singleton component on a
 * reserved entity ([GRID_SINGLETON]) so the otherwise-stateless reducer
 * (which rebuilds [org.emerge.sim.core.sim.SimState] each tick) persists it across ticks. The singleton
 * is invisible to every cell/physics iteration (no [CytoCellComponent] / transform / collider) and is
 * never allocated through the entity counter, so it never perturbs id allocation / `lastEntityValue`.
 */
data class CytoMatterGridComponent(val grid: CytoMatterGrid)

/** Reserved entity id the [CytoMatterGridComponent] singleton lives on — far above any allocated cell
 *  id (allocation grows from 0), never added to the live-id set, so it collides with nothing. */
val GRID_SINGLETON = EntityId(Int.MAX_VALUE)

/**
 * Per-cell connection bookkeeping that parallels the engine
 * [org.emerge.sim.core.physics.components.SpringConstraintComponent]: the accumulated stress damage for
 * each neighbour spring. When a neighbour's damage exceeds the break threshold the connection (spring)
 * is removed. Stored symmetrically so either endpoint can read/break it.
 */
data class ConnectionStateComponent(
    val damage: Map<EntityId, Float> = emptyMap(),
)
