package org.emerge.desktop

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Headless population probe: seed one **self-sufficient** cell (collect light + divide — the genome
 * that exploded exponentially) on a light source and print the population over time. With the
 * exposure-gated harvest, income scales with the colony's surface not its volume, so the count should
 * **plateau** instead of running away. `--args="<ticks> <printEvery>"` (defaults 8000, 500).
 */
fun main(args: Array<String>) {
    val ticks = args.getOrNull(0)?.toIntOrNull() ?: 8000
    val every = args.getOrNull(1)?.toIntOrNull() ?: 500
    val cfg = CytoConfig()
    val reducer = CytoReducer()
    val input = mapOf(PlayerId(0) to CytoInput.EMPTY)
    val genome = GeneCodec.parse(
        """
        Light _ 1.0 > Secrete energy _ 0.0
        Chem energy 1.0 > Mitosis _ _ -0.5
        """.trimIndent(),
    )
    val (sx, sy) = CytoLightField.SOURCES.first()
    var state = SimBuilder(SimState()).run {
        spawnCell(CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector, mapOf("energy" to Frac(1, 5)), MIN_RADIUS, genome = genome)
        build()
    }
    fun pop() = state.components.getTable<CytoCellComponent>().asMap().size
    println("tick\tpop")
    println("0\t${pop()}")
    var prev = pop()
    for (t in 1..ticks) {
        state = reducer.reduce(cfg, state, input)
        if (t % every == 0) {
            val p = pop()
            println("$t\t$p\t(${if (p > prev) "+${p - prev}" else "${p - prev}"})")
            prev = p
        }
    }
    println("final population: ${pop()}  (STRENGTH=${CytoLightField.STRENGTH})")
}
