package org.emerge.desktop

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.sim.SimState

/**
 * Natural-growth scaling profiler: seed the matter reservoir richer (×factor), let a colony grow on the
 * **live [CytoSoaReducer]** until it plateaus at the new carrying capacity, then profile the steady tick.
 * Sweeps the nutrient factor to find the population at which a tick approaches the 60fps budget
 * (16,667µs) and which phase dominates there — the "where's the real bottleneck at scale" instrument.
 *
 * Unlike `profileCytoScale` (which synthetically replicates a save), this grows organically: cells feed,
 * divide, die, and pack around the four matter sources, so density + topology are real.
 *
 * `--args="<factorsCsv> [growTicks] [measureTicks]"`, e.g. `... "1,4,16,64 4000 400"`.
 */
fun main(args: Array<String>) {
    val factors = (args.getOrNull(0) ?: "1,2,4,8").split(",").map { it.trim().toInt() }
    val growTicks = args.getOrNull(1)?.toIntOrNull() ?: 800
    val measureTicks = args.getOrNull(2)?.toIntOrNull() ?: 100
    val growBudgetMs = args.getOrNull(3)?.toLongOrNull() ?: 20_000L   // wall-time cap per factor's grow loop
    val cfg = CytoConfig()  // live config (mutation on); we measure the SoA tick, not AoS-equivalence.

    println("natural-growth scaling on the live SoA reducer (grow<=$growTicks ticks or ${growBudgetMs}ms, measure=$measureTicks)\n")
    println("%8s %7s %8s %10s %9s   %s".format("matter×", "grown", "pop", "tick us", "%budget", "top phases (avg us / share)"))
    println("-".repeat(110))
    System.out.flush()
    for (f in factors) {
        val profiler = PipelineProfiler()
        val soa = CytoSoaReducer(cfg, profiler = profiler)

        var w = CytoWorld.fromSimState(scaledMatter(f))
        val growStart = System.nanoTime()
        var grown = 0
        while (grown < growTicks && (System.nanoTime() - growStart) / 1_000_000 < growBudgetMs) {
            w = soa.tick(w, CytoInput.EMPTY); grown++
        }
        profiler.reset()
        for (t in 0 until measureTicks) {
            val s = System.nanoTime()
            w = soa.tick(w, CytoInput.EMPTY)
            profiler.recordTick(System.nanoTime() - s)
        }
        val r = profiler.report()
        val top = r.phases.sortedByDescending { it.avgNanos }.take(3)
            .joinToString("  ") { "%s %d/%.0f%%".format(it.name, it.avgNanos / 1000, it.sharePercent) }
        println("%8d %7d %8d %10d %8.0f%%   %s".format(f, grown, w.count, r.tickAvgNanos / 1000, r.tickAvgNanos / 166_670.0, top))
        System.out.flush()
    }
}

/** A fresh world whose matter reservoir is seeded [factor]× richer (more nutrients ⇒ higher carrying
 *  capacity). Multiplies every seeded grid-cell's molecule counts; geometry (the four source clumps) is
 *  unchanged, so the colony still grows where the light + matter are. */
private fun scaledMatter(factor: Int): SimState {
    val state = createCytoInitialState()
    if (factor == 1) return state
    val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid ?: return state
    val n = CytoMatterGrid.RES * CytoMatterGrid.RES
    val scaled = Array(n) { idx -> HashMap(grid.cellAt(idx).mapValues { it.value * factor }) }
    val tables = HashMap(state.components.tables)
    tables[CytoMatterGridComponent::class] = ComponentTable.fromMap(
        linkedMapOf(GRID_SINGLETON to CytoMatterGridComponent(CytoMatterGrid.fromCells(scaled))),
    )
    return state.copy(components = ComponentStore(tables))
}
