package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Fresh-start world (matter model, MORPHOGENESIS.md): one hand-authored **autotroph** ([Collector] =
 * [AUTOTROPH_GENES]) sitting on a light source, seeded with a little cytoplasm + biomass, plus the
 * finite [CytoMatterGrid] reservoir (free monomers near the sources). It imports a/b, bonds them into
 * `ab` under light, converts `ab` to biomass to grow, and divides — a clonal colony that **plateaus** as
 * the local matter is drawn down (the carrying capacity).
 */
fun createCytoInitialState(): SimState {
    val builder = SimBuilder(SimState())
    val (sx, sy) = CytoLightField.SOURCES.first()    // sit the seed on a light source
    builder.spawnCell(
        pos = CytoUnits.coord2(sx, sy),
        vel = Coord2.zero,
        type = CellType.Collector,
        cytoplasm = mapOf("a" to 4, "b" to 4),
        biomass = mapOf("ab" to 8),
        logicalRadius = MIN_RADIUS,
    )
    builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.seeded()) }
    return builder.build()
}
