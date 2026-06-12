package org.emerge.desktop

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.sim.SimState

/**
 * Headless probe for the matter-model autotroph: seed the default world (one autotroph on a light
 * source + the finite matter reservoir) and print the population + reservoir + total-atoms over time.
 * The colony should grow then **plateau** as the local matter is drawn down (the carrying capacity),
 * and total atoms (reservoir + cells) must stay **constant** (the closed matter loop).
 * `--args="<ticks> <printEvery>"` (defaults 8000, 500).
 */
fun main(args: Array<String>) {
    val ticks = args.getOrNull(0)?.toIntOrNull() ?: 8000
    val every = args.getOrNull(1)?.toIntOrNull() ?: 500
    val cfg = CytoConfig()
    val reducer = CytoReducer()
    val input = mapOf(PlayerId(0) to CytoInput.EMPTY)
    var state = createCytoInitialState()

    fun pop() = state.components.getTable<CytoCellComponent>().asMap().size
    fun reservoir() = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.totalAtoms() ?: 0L
    fun cellAtoms(): Long {
        var s = 0L
        for (c in state.components.getTable<CytoCellComponent>().asMap().values) {
            for ((sp, n) in c.cytoplasm) s += sp.length.toLong() * n
            for ((sp, n) in c.biomass) s += sp.length.toLong() * n
        }
        return s
    }

    val atoms0 = reservoir() + cellAtoms()
    println("tick\tpop\treservoir\tcellAtoms\ttotal(Δ vs start)")
    fun line(t: Int) {
        val total = reservoir() + cellAtoms()
        println("$t\t${pop()}\t${reservoir()}\t${cellAtoms()}\t$total (${total - atoms0})")
    }
    line(0)
    for (t in 1..ticks) {
        state = reducer.reduce(cfg, state, input)
        if (t % every == 0) line(t)
    }
    println("final: pop=${pop()}  total atoms=${reservoir() + cellAtoms()} (start $atoms0)")
}
