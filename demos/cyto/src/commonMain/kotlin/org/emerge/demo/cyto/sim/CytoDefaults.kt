package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Fresh-start world (matter model, MORPHOGENESIS.md): one hand-authored **autotroph** ([Collector] =
 * [AUTOTROPH_GENES]) at the world origin, seeded with a little cytoplasm + biomass, plus the finite
 * [CytoMatterField] reservoir. Under the moving daylight band ([CytoTuning.LIGHT_MOVING]) it bonds a/b into
 * `ab` while lit, converts `ab` to biomass to grow, hoards a reserve through the dark, and divides by
 * breaking it — a clonal colony that **plateaus** as the local matter is drawn down (the carrying capacity).
 */
fun createCytoInitialState(): SimState {
    // Seed the deterministic PRNG non-zero: the engine LCG's first draw degenerates to 0 from a 0 seed
    // (3037000493 ushr 32 == 0), which would spuriously fire a mutation on the founder's first gene at
    // tick 1. A fixed non-zero seed keeps the sim deterministic without that artifact. (SimState's world
    // defaults to a fresh EcsWorld per construction, so each call gets an isolated entity allocator.)
    val builder = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
    builder.spawnCell(
        pos = CytoUnits.coord2(0f, 0f),   // seed at the world origin (the moving daylight band sweeps to it)
        vel = Coord2.zero,
        type = CellType.Collector,
        cytoplasm = CytoSeed.SEED_CYTOPLASM,
        biomass = CytoSeed.STARTER_BIOMASS,
        logicalRadius = MIN_RADIUS,
    )
    builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(CytoSeed.MATTER_UNIFORM_LEVEL)) }
    return builder.build()
}
