package org.emerge.desktop

import org.emerge.demo.cyto.CytoSaveCodec
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.sim.SimState
import java.io.File

/**
 * Headless per-phase profiler for the Cyto reducer. Loads a save (or grows a fresh world),
 * warms the JIT, then runs N measured ticks with a [PipelineProfiler] attached and prints the
 * per-phase time breakdown — sequential and (optionally) parallel — plus GC pressure. This is the
 * instrument for "where does a Cyto tick spend its time": run it on the save the user reports as
 * slow and read the share column.
 *
 * `--args="<savePath|fresh> [warmupTicks] [measureTicks]"`
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "platform/desktop-app/cyto-save.bin"
    val warmup = args.getOrNull(1)?.toIntOrNull() ?: 300
    val measure = args.getOrNull(2)?.toIntOrNull() ?: 2000

    val initial: SimState = if (path == "fresh") createCytoInitialState()
        else CytoSaveCodec.decode(File(path).readBytes())

    val cellMap = initial.components.getTable<CytoCellComponent>().asMap()
    val cells = cellMap.size
    val springs = initial.components.getTable<SpringConstraintComponent>().asMap().values.sumOf { it.springs.size } / 2
    val geneCounts = cellMap.values.map { it.genome.size }.sorted()
    val cytoSizes = cellMap.values.map { it.cytoplasm.size }.sorted()
    val cytoTotals = cellMap.values.map { it.cytoplasm.values.sum() }.sorted()
    println("loaded $path: $cells cells, $springs connections")
    println("  genome size:   min=${geneCounts.firstOrNull()} med=${geneCounts.getOrNull(geneCounts.size/2)} max=${geneCounts.lastOrNull()} total=${geneCounts.sum()}")
    println("  cyto species:  min=${cytoSizes.firstOrNull()} med=${cytoSizes.getOrNull(cytoSizes.size/2)} max=${cytoSizes.lastOrNull()}")
    println("  cyto molecules:min=${cytoTotals.firstOrNull()} med=${cytoTotals.getOrNull(cytoTotals.size/2)} max=${cytoTotals.lastOrNull()}")
    println("warmup=$warmup measure=$measure ticks\n")

    val cfg = CytoConfig()
    val input = mapOf(PlayerId(0) to CytoInput.EMPTY)

    // ── sequential (production single-thread path) ──
    runVariant("SEQUENTIAL", initial, cfg, input, warmup, measure, executor = null)

    // ── parallel (executor-backed connection + spring fan-out) ──
    val executor = ParallelExecutor()
    try {
        runVariant("PARALLEL (${executor.parallelism} workers)", initial, cfg, input, warmup, measure, executor)
    } finally {
        executor.close()
    }

    // ── struct-of-arrays (in-place columns incl. biology; lifecycle/interaction bridged) ──
    runSoaVariant(initial, cfg, input, warmup, measure)
}

private fun runSoaVariant(initial: SimState, cfg: CytoConfig, input: Map<PlayerId, CytoInput>, warmup: Int, measure: Int) {
    val profiler = PipelineProfiler()
    profiler.allocReader = { allocatedBytes() }
    val reducer = org.emerge.demo.cyto.sim.soa.CytoSoaReducer(cfg, profiler = profiler)
    val cytoInput = input.values.first()

    var w = org.emerge.demo.cyto.sim.soa.CytoWorld.fromSimState(initial)
    for (t in 0 until warmup) w = reducer.tick(w, cytoInput)
    profiler.reset()

    val gc = gcSnapshot()
    val allocStart = allocatedBytes()
    val wallStart = System.nanoTime()
    for (t in 0 until measure) {
        val tickStart = System.nanoTime()
        w = reducer.tick(w, cytoInput)
        profiler.recordTick(System.nanoTime() - tickStart)
    }
    val wallNanos = System.nanoTime() - wallStart
    val allocDelta = allocatedBytes() - allocStart
    val gcDelta = gcSnapshot() - gc

    val report = profiler.report()
    val cells = w.count
    println("── SOA (in-place incl. biology; lifecycle/interaction bridged) ──")
    println("  end population: $cells cells")
    println("  wall: ${wallNanos / 1_000_000} ms over $measure ticks")
    println("  tick: avg=${us(report.tickAvgNanos)}us  p50=${us(report.tickP50Nanos)}us  p95=${us(report.tickP95Nanos)}us  p99=${us(report.tickP99Nanos)}us  max=${us(report.tickMaxNanos)}us")
    println("  fps headroom: ${"%.1f".format(16_667_000.0 / report.tickAvgNanos)}x of a 60fps frame budget")
    println("  gc: ${gcDelta.count} collections, ${gcDelta.millis} ms paused")
    println("  alloc: ${allocDelta / 1_000_000} MB total, ${allocDelta / measure / 1024} KB/tick")
    println()
    println("  %-14s %10s %10s %7s %10s".format("phase", "avg us", "max us", "share", "KB/tick"))
    println("  " + "-".repeat(56))
    for (line in report.phases.sortedByDescending { it.avgNanos }) {
        println("  %-14s %10d %10d %6.1f%% %10d".format(line.name, line.avgNanos / 1000, line.maxNanos / 1000, line.sharePercent, line.avgBytes / 1024))
    }
    println()
}

private fun runVariant(
    label: String,
    initial: SimState,
    cfg: CytoConfig,
    input: Map<PlayerId, CytoInput>,
    warmup: Int,
    measure: Int,
    executor: ParallelExecutor?,
) {
    val profiler = PipelineProfiler()
    if (executor == null) profiler.allocReader = { allocatedBytes() } // single-threaded only: per-thread gauge
    val reducer = CytoReducer(profiler = profiler, executor = executor)

    // Warm the JIT on a throwaway state so steady-state numbers aren't polluted by compilation.
    var state = initial
    for (t in 0 until warmup) state = reducer.reduce(cfg, state, input)
    profiler.reset()

    val gc = gcSnapshot()
    val allocStart = allocatedBytes()
    val wallStart = System.nanoTime()
    for (t in 0 until measure) {
        val tickStart = System.nanoTime()
        state = reducer.reduce(cfg, state, input)
        profiler.recordTick(System.nanoTime() - tickStart)
    }
    val wallNanos = System.nanoTime() - wallStart
    val allocDelta = allocatedBytes() - allocStart
    val gcDelta = gcSnapshot() - gc

    val report = profiler.report()
    val cells = state.components.getTable<CytoCellComponent>().asMap().size
    println("── $label ──")
    println("  end population: $cells cells")
    println("  wall: ${wallNanos / 1_000_000} ms over $measure ticks")
    println("  tick: avg=${us(report.tickAvgNanos)}us  p50=${us(report.tickP50Nanos)}us  p95=${us(report.tickP95Nanos)}us  p99=${us(report.tickP99Nanos)}us  max=${us(report.tickMaxNanos)}us")
    println("  fps headroom: ${"%.1f".format(16_667_000.0 / report.tickAvgNanos)}x of a 60fps frame budget")
    println("  gc: ${gcDelta.count} collections, ${gcDelta.millis} ms paused")
    println("  alloc: ${allocDelta / 1_000_000} MB total, ${allocDelta / measure / 1024} KB/tick")
    println()
    println("  %-14s %10s %10s %7s %10s".format("phase", "avg us", "max us", "share", "KB/tick"))
    println("  " + "-".repeat(56))
    for (line in report.phases.sortedByDescending { it.avgNanos }) {
        println("  %-14s %10d %10d %6.1f%% %10d".format(line.name, line.avgNanos / 1000, line.maxNanos / 1000, line.sharePercent, line.avgBytes / 1024))
    }
    println()
}

private fun us(nanos: Long) = nanos / 1000

private data class Gc(val count: Long, val millis: Long) {
    operator fun minus(o: Gc) = Gc(count - o.count, millis - o.millis)
}

/** Bytes allocated by the current (single benchmark) thread — a noise-immune allocation gauge on
 *  HotSpot. The sequential run is single-threaded, so this captures essentially all of its allocation. */
private fun allocatedBytes(): Long {
    val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    return bean.getThreadAllocatedBytes(Thread.currentThread().id)
}

private fun gcSnapshot(): Gc {
    var count = 0L
    var millis = 0L
    for (bean in java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
        if (bean.collectionCount >= 0) count += bean.collectionCount
        if (bean.collectionTime >= 0) millis += bean.collectionTime
    }
    return Gc(count, millis)
}
