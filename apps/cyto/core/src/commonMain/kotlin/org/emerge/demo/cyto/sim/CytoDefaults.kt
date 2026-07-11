package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Fresh-start world (matter model, MORPHOGENESIS.md) built from a [CytoScenario]. The **default** scenario
 * ([CytoScenario.DEFAULT]) reproduces the historical boot world byte-for-byte: one hand-authored **autotroph**
 * ([CellType.Collector] = [AUTOTROPH_GENES]) at the world origin, seeded with a little cytoplasm + biomass,
 * plus the finite [CytoMatterField] reservoir. Under the moving daylight band ([CytoTuning.LIGHT_MOVING]) it
 * bonds a/b into `ab` while lit, converts `ab` to biomass to grow, hoards a reserve through the dark, and
 * divides by breaking it — a clonal colony that **plateaus** as the local matter is drawn down.
 *
 * A non-default scenario resizes the torus + day/night cycle (via [CytoWorldConfig]) and seeds the chosen
 * founders + matter level. **Call this before constructing the [org.emerge.demo.cyto.CytoController]** — it
 * applies [CytoWorldConfig] here, and the reducer/biology sizes its per-tile buffers from the world size at
 * construction, so the holder must already reflect the scenario.
 */
fun createCytoInitialState(scenario: CytoScenario = CytoScenario.DEFAULT): SimState {
    CytoWorldConfig.applyFrom(scenario)
    // Seed the deterministic PRNG non-zero: the engine LCG's first draw degenerates to 0 from a 0 seed
    // (3037000493 ushr 32 == 0), which would spuriously fire a mutation on the founder's first gene at
    // tick 1. A fixed non-zero seed keeps the sim deterministic without that artifact.
    val builder = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
    for (p in founderPlacements(scenario)) {
        builder.spawnCell(
            pos = p.pos,
            vel = Coord2.zero,
            type = p.type,
            biomass = CytoSeed.STARTER_BIOMASS,
            logicalRadius = MIN_RADIUS,
            genome = p.genome ?: genomeForType(p.type),
        )
    }
    builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(scenario.matterLevel)) }
    return builder.build()
}

/**
 * Deterministic founder layout: expands [CytoScenario.founders] into (position, type) pairs. [Distribution.Clustered]
 * packs them in a small ring near the origin (the classic single-seed start); [Distribution.Scattered] spreads
 * them on a jittered square grid across the inner ~75% of the torus, so colonies start apart and contest the
 * space between them. A single clustered founder lands exactly at the origin (preserving the old boot world).
 */
private class Placement(val pos: Coord2, val type: CellType, val genome: List<Gene>?)

private fun founderPlacements(scenario: CytoScenario): List<Placement> {
    // Expand each FounderSpec into per-founder (type, genome) entries, preserving its optional genome override.
    val founders = ArrayList<Pair<CellType, List<Gene>?>>()
    for (f in scenario.founders) repeat(f.count.coerceAtLeast(0)) { founders.add(f.type to f.genome) }
    if (founders.isEmpty()) return emptyList()
    val half = CytoUnits.CELLS_PER_AXIS.toFloat()
    val out = ArrayList<Placement>(founders.size)
    when (scenario.distribution) {
        Distribution.Clustered -> {
            if (founders.size == 1) return listOf(Placement(CytoUnits.coord2(0f, 0f), founders[0].first, founders[0].second))
            val ring = 3f
            for ((i, f) in founders.withIndex()) {
                val a = (i.toFloat() / founders.size) * TAU
                out.add(Placement(CytoUnits.coord2(ring * cos(a), ring * sin(a)), f.first, f.second))
            }
        }
        Distribution.Scattered -> {
            val extent = half * 0.75f
            val cols = ceil(sqrt(founders.size.toFloat())).toInt().coerceAtLeast(1)
            val step = (2f * extent) / cols
            for ((i, f) in founders.withIndex()) {
                val gx = i % cols; val gy = i / cols
                // Cell centre + a deterministic jitter (index-hashed) so a grid doesn't look mechanical.
                val jx = (hash01(i * 2 + 1) - 0.5f) * step * 0.5f
                val jy = (hash01(i * 2 + 2) - 0.5f) * step * 0.5f
                val x = -extent + (gx + 0.5f) * step + jx
                val y = -extent + (gy + 0.5f) * step + jy
                out.add(Placement(CytoUnits.coord2(x, y), f.first, f.second))
            }
        }
    }
    return out
}

private const val TAU = 6.2831855f
private fun cos(a: Float) = kotlin.math.cos(a)
private fun sin(a: Float) = kotlin.math.sin(a)
/** Cheap deterministic [0,1) hash of an int (integer-only, multiplatform-stable). */
private fun hash01(n: Int): Float {
    var h = n * -0x61c88647          // Fibonacci hashing constant
    h = h xor (h ushr 15)
    h *= -0x7ee3623b
    h = h xor (h ushr 13)
    return ((h ushr 8) and 0xFFFFFF).toFloat() / 0x1000000.toFloat()
}
