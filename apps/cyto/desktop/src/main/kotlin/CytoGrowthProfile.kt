package org.emerge.desktop

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.ParallelExecutor
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
    // Mutation OFF so the seq and parallel measurement passes evolve the *same* colony from the shared
    // snapshot (with mutation on they diverge — the known mutation-on gap — and their single-threaded
    // biology costs stop being comparable, muddying the forces seq-vs-par read).
    val cfg = CytoConfig(mutationRateDenom = 0)

    val executor = ParallelExecutor()
    println("natural-growth scaling on the live SoA reducer (grow<=$growTicks ticks or ${growBudgetMs}ms, measure=$measureTicks)")
    println("each factor grown once (sequential, deterministic), then the SAME world measured seq vs parallel.\n")
    println("%8s %7s %8s  %5s %10s %9s %9s   %s".format("matter×", "grown", "pop", "mode", "tick us", "%budget", "KB/tick", "top phases (avg us / share)"))
    println("-".repeat(130))
    System.out.flush()
    for (f in factors) {
        // Grow once with a plain sequential reducer (deterministic), then snapshot the colony.
        val grower = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(scaledMatter(f))
        val growStart = System.nanoTime()
        var grown = 0
        while (grown < growTicks && (System.nanoTime() - growStart) / 1_000_000 < growBudgetMs) {
            w = grower.tick(w, CytoInput.EMPTY); grown++
        }
        val snapshot = w.toSimState()
        val pop = w.count

        // Measure the same grown world both ways. Parallel path uses a real executor (default threshold).
        measure("seq ", f, grown, pop, snapshot, cfg, null, measureTicks)
        measure("par ", f, grown, pop, snapshot, cfg, executor, measureTicks)
        System.out.flush()
    }
    executor.close()
}

private fun measure(mode: String, f: Int, grown: Int, pop: Int, snapshot: SimState, cfg: CytoConfig, executor: ParallelExecutor?, ticks: Int) {
    val profiler = PipelineProfiler()
    val soa = CytoSoaReducer(cfg, executor = executor, profiler = profiler)
    var w = CytoWorld.fromSimState(snapshot)
    repeat(50) { w = soa.tick(w, CytoInput.EMPTY) }   // warm JIT + let the executor spin up
    profiler.reset()
    // Whole-tick main-thread allocation (KB/tick). Captures the spring solver's per-tick garbage on the
    // SEQ path exactly (all on this thread); on the PAR path the worker-thread gather alloc isn't counted.
    val alloc0 = allocatedBytes()
    for (t in 0 until ticks) {
        val s = System.nanoTime()
        w = soa.tick(w, CytoInput.EMPTY)
        profiler.recordTick(System.nanoTime() - s)
    }
    val allocKbPerTick = (allocatedBytes() - alloc0) / 1024 / ticks
    val r = profiler.report()
    val top = r.phases.sortedByDescending { it.avgNanos }.take(3)
        .joinToString("  ") { "%s %d/%.0f%%".format(it.name, it.avgNanos / 1000, it.sharePercent) }
    println("%8d %7d %8d  %5s %10d %8.0f%% %9d   %s".format(f, grown, pop, mode, r.tickAvgNanos / 1000, r.tickAvgNanos / 166_670.0, allocKbPerTick, top))
}

private fun allocatedBytes(): Long {
    val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    return bean.getThreadAllocatedBytes(Thread.currentThread().id)
}

/** A fresh world whose matter reservoir is seeded [factor]× richer (more nutrients ⇒ higher carrying
 *  capacity). Multiplies every leaf's molecule counts; geometry is unchanged, so the colony still grows
 *  where the light + matter are. */
private fun scaledMatter(factor: Int): SimState {
    val state = createCytoInitialState()
    if (factor == 1) return state
    val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid ?: return state
    val scaled = grid.copy()
    scaled.forEachLeaf { _, _, _, store ->
        val ids = IntArray(store.size) { store.idAt(it) }   // snapshot: scaling existing species keeps size stable
        for (id in ids) store.add(id, store.count(id) * (factor - 1))
    }
    val tables = HashMap(state.components.tables)
    tables[CytoMatterGridComponent::class] = ComponentTable.fromMap(
        linkedMapOf(GRID_SINGLETON to CytoMatterGridComponent(scaled)),
    )
    return state.copy(components = ComponentStore(tables))
}
