package org.emerge.demo.cyto

import org.emerge.sim.core.sim.SimState

/**
 * One frame's renderable state: the engine [SimState] snapshot (component tables the
 * renderer reads) plus the tick counter. Native (Box2D-free) — this is now a plain
 * immutable snapshot like the other demos' frames.
 */
class CytoFrame(
    val state: SimState,
    val tick: Long,
)
