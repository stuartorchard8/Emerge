package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.ecs.EcsWorld
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
    // Seed the deterministic PRNG non-zero: the engine LCG's first draw degenerates to 0 from a 0 seed
    // (3037000493 ushr 32 == 0), which would spuriously fire a mutation on the founder's first gene at
    // tick 1. A fixed non-zero seed keeps the sim deterministic without that artifact.
    // Fresh EcsWorld per call (NOT the shared, mutable EcsWorld.EMPTY default): EMPTY's entity set is
    // mutated in place by createEntity, so two createCytoInitialState() calls that shared it would hand
    // their founders different ids (process-history-dependent) — breaking determinism across independent
    // worlds (golden regression, two-world tests, repeated loads). A fresh world isolates each call.
    val builder = SimBuilder(SimState(world = EcsWorld(), randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
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
