package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Fresh-start world: a Support feeder welded to a Stem at the torus origin. The Support pumps
 * energy (placeholder economy) that diffuses into the Stem; once the Stem's energy clears its
 * mitosis gate it warms up and divides, growing a colony. (Division is now gene-driven, so a Stem
 * needs an energy surplus to split — a lone unfed Stem just sits, which is the point.)
 */
fun createCytoInitialState(): SimState {
    val builder = SimBuilder(SimState())
    builder.spawnCell(
        pos = CytoUnits.coord2(-0.1f, 0f),
        vel = Coord2.zero,
        type = CellType.Support,
        chemicals = mapOf("energy" to 2f),
        logicalRadius = MIN_RADIUS,
    )
    builder.spawnCell(
        pos = CytoUnits.coord2(0.1f, 0f),
        vel = Coord2.zero,
        type = CellType.Stem,
        chemicals = mapOf("energy" to 2f),
        logicalRadius = MIN_RADIUS,
    )
    return builder.build()
}
