package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId
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
    /** Continuous drag: pull [Grab.entity] toward the pointer this tick (or null). */
    val grab: Grab? = null,
    /** One-shot: cut all connections of these cells (Detach hold mode). */
    val detaches: List<EntityId> = emptyList(),
) : SimInput {
    /** [genome] (if non-null) is the authoring "brush" genome to give the cell instead of the type's
     *  preset — how a loaded `.gene` file / the editor paints cells with a custom genome. [biomass] (if
     *  non-null) overrides the genome-derived starter biomass — the campaign uses it to place a cell with a
     *  fixed hand-authored reserve (e.g. a gene-less starter holding 2000 each of r/g/b). */
    data class Spawn(val x: Float, val y: Float, val type: CellType, val genome: List<Gene>? = null, val biomass: Map<String, Int>? = null)
    data class Tap(val x: Float, val y: Float, val mode: TouchMode, val type: CellType, val genome: List<Gene>? = null, val biomass: Map<String, Int>? = null)
    /** [sticky] makes the held cell weld to whatever it touches while dragged (Sticky mode). */
    data class Grab(val entity: EntityId, val x: Float, val y: Float, val sticky: Boolean = false)

    companion object {
        val EMPTY = CytoInput()
    }
}

/** Whether a touch mode acts while held or on tap-release (ported from Cyto). */
enum class TouchModeGroup { Hold, TapUp }

/** Pointer behaviour, ported from Cyto's TouchMode (with its swatch colours + groups). */
enum class TouchMode(val color: Long, val group: TouchModeGroup) {
    Base(0xDDDDDDFF, TouchModeGroup.Hold),
    Sticky(0x009900FF, TouchModeGroup.Hold),
    Detach(0xEEAA22FF, TouchModeGroup.Hold),
    Activate(0x0000FFFF, TouchModeGroup.TapUp),
    Delete(0xFF0000FF, TouchModeGroup.TapUp),
    Set(0xAA00AAFF, TouchModeGroup.TapUp),
    Kill(0xF55000000, TouchModeGroup.TapUp),
}
