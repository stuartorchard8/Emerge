package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.SimInput

/**
 * Pointer interactions for one tick, mirroring Cyto's TouchMode actions. Mutation is
 * input-driven (the ECS way): the host collects interactions and hands them to the
 * reducer via `inputs[PlayerId(0)]`, where [org.emerge.demo.cyto.sim.systems.CytoInteractionSystem]
 * applies them. Positions are in logical Cyto units (the renderer converts pointer pixels).
 */
data class CytoInput(
    val spawns: List<Spawn> = emptyList(),
    val taps: List<Tap> = emptyList(),
) : SimInput {
    data class Spawn(val x: Float, val y: Float, val type: CellType)
    data class Tap(val x: Float, val y: Float, val mode: TouchMode, val type: CellType)

    companion object {
        val EMPTY = CytoInput()
    }
}

/** Pointer behaviour, ported from Cyto's TouchMode. */
enum class TouchMode { Base, Sticky, Detach, Activate, Delete, Set }
