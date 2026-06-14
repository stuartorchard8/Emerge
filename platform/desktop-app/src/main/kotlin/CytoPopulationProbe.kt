package org.emerge.desktop

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.sim.SimState

/**
 * Headless probe for the matter-model autotroph: seed the default world (one autotroph on a light
 * source + the finite matter reservoir) and print the population + reservoir + total-atoms over time.
 * The colony should grow then **plateau** as the local matter is drawn down (the carrying capacity),
 * and total atoms (reservoir + cells) must stay **constant** (the closed matter loop).
 * `--args="<ticks> <printEvery> <mutationRateDenom>"` (defaults 8000, 500, CytoConfig default;
 * pass 0 to disable mutation). Prints per checkpoint: pop, reservoir/cell atoms (conservation),
 * and avg biomass-bonds + avg cytoplasm-atoms + max radius (to see why cells do/don't divide).
 */
fun main(args: Array<String>) {
    val ticks = args.getOrNull(0)?.toIntOrNull() ?: 8000
    val every = args.getOrNull(1)?.toIntOrNull() ?: 500
    val baseCfg = CytoConfig()
    val cfg = args.getOrNull(2)?.toIntOrNull()?.let { baseCfg.copy(mutationRateDenom = it) } ?: baseCfg
    var state = createCytoInitialState()
    val sim = CytoSoaSim(cfg, state)

    fun cells() = state.components.getTable<CytoCellComponent>().asMap().values
    fun pop() = cells().size
    fun reservoir() = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.totalAtoms() ?: 0L
    fun cellAtoms(): Long {
        var s = 0L
        for (c in cells()) {
            for ((sp, n) in c.cytoplasm) s += sp.length.toLong() * n
            for ((sp, n) in c.biomass) s += sp.length.toLong() * n
        }
        return s
    }
    fun avgBiomassBonds(): Double {
        val cs = cells(); if (cs.isEmpty()) return 0.0
        return cs.sumOf { c -> c.biomass.entries.sumOf { (sp, n) -> (sp.length - 1).coerceAtLeast(0) * n } }.toDouble() / cs.size
    }
    fun avgCytoAtoms(): Double {
        val cs = cells(); if (cs.isEmpty()) return 0.0
        return cs.sumOf { c -> c.cytoplasm.entries.sumOf { (sp, n) -> sp.length * n } }.toDouble() / cs.size
    }
    fun maxRadius(): Float = cells().maxOfOrNull { it.logicalRadius.toFloat() } ?: 0f

    val atoms0 = reservoir() + cellAtoms()
    println("mutationRateDenom=${cfg.mutationRateDenom}")
    println("tick\tpop\treservoir\tcellAtoms\tΔ\tavgBio\tavgCyto\tmaxR")
    fun line(t: Int) {
        val total = reservoir() + cellAtoms()
        println("$t\t${pop()}\t${reservoir()}\t${cellAtoms()}\t${total - atoms0}\t" +
            "${(avgBiomassBonds() * 10).toInt() / 10.0}\t${(avgCytoAtoms() * 10).toInt() / 10.0}\t${(maxRadius() * 100).toInt() / 100f}")
    }
    line(0)
    for (t in 1..ticks) {
        state = sim.step()
        if (t % every == 0) line(t)
    }
    println("final: pop=${pop()}  total atoms=${reservoir() + cellAtoms()} (start $atoms0)")
}
