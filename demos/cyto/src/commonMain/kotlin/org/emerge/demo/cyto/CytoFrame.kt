package org.emerge.demo.cyto

import org.emerge.sim.core.sim.SimState

/**
 * One frame's renderable state: the engine [SimState] snapshot (component tables the
 * renderer reads) plus the tick counter. Native (Box2D-free) — this is now a plain
 * immutable snapshot like the other demos' frames.
 *
 * @param springData optional CSR-based spring/damage data for the renderer, avoiding SimState materialization.
 */
class CytoFrame(
    val state: SimState,
    val tick: Long,
    val springData: CytoFrameSpringData? = null,
)

/** Read-only view of CSR spring data for the renderer (avoids SimState SpringConstraintComponent allocation). */
class CytoFrameSpringData(
    val entityId: IntArray,
    val csrOffset: IntArray,
    val csrOtherSlot: IntArray,
    val csrOtherId: IntArray,
    val csrRestRaw: LongArray,
    val csrStiffRaw: LongArray,
    val csrDampRaw: LongArray,
    val csrEdgeAux: FloatArray,
    val slotOfEntityId: (Int) -> Int,
)
