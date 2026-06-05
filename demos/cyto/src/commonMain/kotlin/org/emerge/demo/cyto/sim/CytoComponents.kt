package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId

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
    val chemicals: Map<String, Float>,
    val logicalRadius: Float,
    val divideCooldown: Float = 5f,
    val sticky: Boolean = false,
    val pendingTransfers: Map<String, Float> = emptyMap(),
    val suppression: Map<String, Float> = emptyMap(),
    val touch: Float = 0f,
    /** Transient: gene-driven stickiness for this tick (original `isStickyTemp`). Recomputed
     *  by the biology system each tick; OR-ed with [sticky] by the contact system. */
    val stickyTemp: Boolean = false,
)

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
