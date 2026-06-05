package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Fresh-start world: a single Stem cell with surplus energy at the torus origin — it grows
 * a colony as it divides. Mirrors the Phase-A seed.
 */
fun createCytoInitialState(): SimState {
    val builder = SimBuilder(SimState())
    builder.spawnCell(
        pos = Coord2.zero,
        vel = Coord2.zero,
        type = CellType.Stem,
        chemicals = mapOf("energy" to 2f),
        logicalRadius = MIN_RADIUS,
    )
    return builder.build()
}
