package org.emerge.desktop

import org.emerge.demo.cyto.CytoSaveCodec
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoSimParamsComponent
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.PARAMS_SINGLETON
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.sim.SimState
import java.io.File

/**
 * Matter-conservation check for Cyto. Loads a save, tallies total atoms per element (a/b/c) across the
 * matter grid + every cell's cytoplasm + biomass, runs the SoA reducer N ticks (mutation as saved), and
 * re-tallies. Any element whose total changes is a conservation leak.
 *
 * --args="<savePath> [ticks]"  (defaults: platform/desktop-app/cyto-save.bin, 1000)
 *
 * Broad by design (per-element totals over the whole world) — catches typical leaks; a leak that swaps
 * atoms between elements in exactly matching amounts would slip through, but that is not a realistic
 * failure mode here.
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "platform/desktop-app/cyto-save.bin"
    val ticks = args.getOrNull(1)?.toIntOrNull() ?: 1000

    val initial: SimState = CytoSaveCodec.decode(File(path).readBytes())

    val cellsBefore = initial.components.getTable<CytoCellComponent>().asMap().size
    val mutDenom = initial.components.getTable<CytoSimParamsComponent>()[PARAMS_SINGLETON]?.mutationRateDenom ?: -1
    val mutDesc = when {
        mutDenom < 0 -> "inherit CytoConfig default (${CytoConfig().mutationRateDenom})"
        mutDenom == 0 -> "OFF"
        else -> "ON (1/$mutDenom per gene)"
    }

    println("=== Cyto Conservation Check ===")
    println("source: $path")
    println("cells: $cellsBefore   mutation: $mutDesc   ticks: $ticks\n")

    val before = elementTotals(initial)

    // Run the sim.
    val reducer = CytoSoaReducer(CytoConfig())
    var w = CytoWorld.fromSimState(initial)
    val t0 = System.nanoTime()
    repeat(ticks) { w = reducer.tick(w) }
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
    val finalState = w.toSimState()

    val after = elementTotals(finalState)
    val cellsAfter = finalState.components.getTable<CytoCellComponent>().asMap().size

    // Report.
    val n = maxOf(before.size, after.size)
    var leaked = false
    println("  %-4s %16s %16s %14s".format("elem", "before", "after", "delta"))
    println("  " + "-".repeat(52))
    for (i in 0 until n) {
        val b = before.getOrElse(i) { 0L }
        val a = after.getOrElse(i) { 0L }
        if (b == 0L && a == 0L) continue
        val d = a - b
        if (d != 0L) leaked = true
        val label = CytoSeed.SEED_MONOMERS.getOrElse(i) { "?$i" }
        println("  %-4s %16d %16d %14d".format(label, b, a, d))
    }
    val tb = before.sum(); val ta = after.sum()
    println("  " + "-".repeat(52))
    println("  %-4s %16d %16d %14d".format("Σ", tb, ta, ta - tb))
    println()
    println("cells: $cellsBefore -> $cellsAfter   (${elapsedMs}ms for $ticks ticks)")
    if (leaked || tb != ta)
        println("\n❌ NOT CONSERVED — total drift ${ta - tb} atoms over $ticks ticks.")
    else
        println("\n✅ CONSERVED — every element total unchanged over $ticks ticks.")
}

/** Per-element (monomer) atom totals across grid + all cells' cytoplasm + biomass. Index i = the monomer
 *  element id [SpeciesRegistry.atomIndexOf] returns — same axis the grid's [elementTotals] uses, so grid and
 *  cell tallies line up. Alphabet-agnostic (works for a/b/c, r/g/b, …). */
private fun elementTotals(state: SimState): LongArray {
    val out = LongArray(8)
    state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.elementTotals(out)
    for (cell in state.components.getTable<CytoCellComponent>().asMap().values) {
        for ((sp, c) in cell.cytoplasm) for (ch in sp) out[SpeciesRegistry.atomIndexOf(ch)] += c.toLong()
        for ((sp, c) in cell.biomass) for (ch in sp) out[SpeciesRegistry.atomIndexOf(ch)] += c.toLong()
    }
    return out
}
