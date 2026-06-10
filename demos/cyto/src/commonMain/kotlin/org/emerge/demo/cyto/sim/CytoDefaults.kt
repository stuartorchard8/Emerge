package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Fresh-start world: a Collector sitting on a light source, welded to a Stem. The Collector turns
 * the environmental light at its position into energy (no free lunch — see [CytoLightField]) which
 * diffuses into the Stem; once the Stem's energy clears its mitosis gate it warms up and divides,
 * growing a colony anchored to the light. (A lone unfed Stem, or a Collector parked in the dark,
 * just sits — which is the point.)
 */
fun createCytoInitialState(): SimState {
    val builder = SimBuilder(SimState())
    val (sx, sy) = CytoLightField.SOURCES.first()    // sit the seed on a light source
    builder.spawnCell(
        pos = CytoUnits.coord2(sx - 0.1f, sy),
        vel = Coord2.zero,
        type = CellType.Collector,
        chemicals = mapOf("energy" to 2f),
        logicalRadius = MIN_RADIUS,
    )
    builder.spawnCell(
        pos = CytoUnits.coord2(sx + 0.1f, sy),
        vel = Coord2.zero,
        type = CellType.Stem,
        chemicals = mapOf("energy" to 2f),
        logicalRadius = MIN_RADIUS,
    )
    return builder.build()
}
