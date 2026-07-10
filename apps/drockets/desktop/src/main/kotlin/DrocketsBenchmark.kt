package org.emerge.desktop

import org.emerge.demo.drockets.DrocketsController
import org.emerge.demo.drockets.DrocketsReducer
import org.emerge.demo.drockets.createDrocketsInitialState
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.sim.SimState

/**
 * Headless benchmark harness for the Drockets demo.
 *
 * Runs the reducer in two variants (sequential, parallel), each with a warmup phase
 * followed by a measured window, and prints a per-phase breakdown plus tick-latency
 * percentiles. No rendering, no networking, no input — just raw simulation cost.
 *
 * Usage:
 *   ./gradlew :apps:drockets:desktop:benchDrockets                     # default 500 drockets
 *   ./gradlew :apps:drockets:desktop:benchDrockets --args="200"        # 200 drockets
 *   ./gradlew :apps:drockets:desktop:benchDrockets --args="500 600 3000"
 *     # 500 drockets, 600 warmup ticks, 3000 measured ticks
 */
fun main(args: Array<String>) {
    val drocketCount = args.getOrNull(0)?.toIntOrNull() ?: 500
    val warmupTicks = args.getOrNull(1)?.toIntOrNull() ?: 600
    val measureTicks = args.getOrNull(2)?.toIntOrNull() ?: 3000

    val cpus = Runtime.getRuntime().availableProcessors()
    println("=== drockets benchmark ===")
    println("drockets:     $drocketCount")
    println("warmup ticks: $warmupTicks")
    println("measured:     $measureTicks")
    println("cpus:         $cpus")
    println("budget/tick:  16.67 ms  (60 Hz)")
    println()

    val sequential = runVariant("sequential", drocketCount, warmupTicks, measureTicks, parallel = false)
    val parallel = runVariant("parallel", drocketCount, warmupTicks, measureTicks, parallel = true)

    printSpeedup(sequential, parallel)
}

private data class VariantResult(
    val label: String,
    val tickAvgMs: Double,
    val tickP95Ms: Double,
    val report: PipelineProfiler.Report,
)

private fun runVariant(
    label: String,
    drocketCount: Int,
    warmupTicks: Int,
    measureTicks: Int,
    parallel: Boolean,
): VariantResult {
    val cfg = DrocketsController.DROCKETS_CONFIG
    val executor = if (parallel) ParallelExecutor() else null
    val profiler = PipelineProfiler()
    val reducer = DrocketsReducer(executor = executor, profiler = profiler)

    var state: SimState = createDrocketsInitialState(drocketCount)

    // Warmup: tick until the JIT has seen hot paths and steady-state allocation/GC
    // behaviour has settled. The profiler is active throughout warmup but we reset
    // before the measurement window.
    for (i in 0 until warmupTicks) {
        state = reducer.reduce(cfg, state, emptyMap())
    }
    profiler.reset()

    // Measure: per-tick wall time via System.nanoTime around reducer.reduce, so the
    // tick sample includes the builder build() cost too (which the phase timers
    // don't cover). That gap is the SimBuilder overhead — typically tiny but
    // useful to see if it ever isn't.
    for (i in 0 until measureTicks) {
        val t0 = System.nanoTime()
        state = reducer.reduce(cfg, state, emptyMap())
        profiler.recordTick(System.nanoTime() - t0)
    }

    executor?.close()

    val report = profiler.report()
    printReport(label, drocketCount, report)

    return VariantResult(
        label = label,
        tickAvgMs = report.tickAvgNanos / 1_000_000.0,
        tickP95Ms = report.tickP95Nanos / 1_000_000.0,
        report = report,
    )
}

private fun printReport(label: String, drocketCount: Int, r: PipelineProfiler.Report) {
    println("--- $label (${drocketCount} drockets, ${r.tickCount} ticks) ---")
    println(
        "tick  avg=%6.3f ms  p50=%6.3f  p95=%6.3f  p99=%6.3f  max=%6.3f  min=%6.3f".format(
            r.tickAvgNanos / 1e6,
            r.tickP50Nanos / 1e6,
            r.tickP95Nanos / 1e6,
            r.tickP99Nanos / 1e6,
            r.tickMaxNanos / 1e6,
            r.tickMinNanos / 1e6,
        ),
    )
    println()
    println("  %-16s  %8s  %8s  %7s".format("phase", "avg ms", "max ms", "share"))
    println("  " + "-".repeat(44))
    for (p in r.phases) {
        println(
            "  %-16s  %8.3f  %8.3f  %6.1f%%".format(
                p.name,
                p.avgNanos / 1e6,
                p.maxNanos / 1e6,
                p.sharePercent,
            ),
        )
    }
    val phaseSum = r.phases.sumOf { it.avgNanos }
    val overhead = r.tickAvgNanos - phaseSum
    println(
        "  %-16s  %8.3f  %8s  %7s".format(
            "overhead*",
            overhead / 1e6,
            "",
            "",
        ),
    )
    println("  * tick wall time not accounted to any phase (builder build, reduce plumbing)")
    println()
}

private fun printSpeedup(seq: VariantResult, par: VariantResult) {
    val avgSpeedup = seq.tickAvgMs / par.tickAvgMs
    val p95Speedup = seq.tickP95Ms / par.tickP95Ms
    println("--- parallel speedup ---")
    println("avg tick:  %.2fx  (%.3f ms -> %.3f ms)".format(avgSpeedup, seq.tickAvgMs, par.tickAvgMs))
    println("p95 tick:  %.2fx  (%.3f ms -> %.3f ms)".format(p95Speedup, seq.tickP95Ms, par.tickP95Ms))

    val parBudgetUsed = par.tickAvgMs / 16.667
    val parP95BudgetUsed = par.tickP95Ms / 16.667
    println(
        "parallel:  avg uses %.0f%% of 16.67ms budget,  p95 uses %.0f%%".format(
            parBudgetUsed * 100.0,
            parP95BudgetUsed * 100.0,
        ),
    )
    println()

    // Per-phase comparison — where did parallelism actually help.
    val seqByName = seq.report.phases.associateBy { it.name }
    println("--- per-phase seq vs par avg ms ---")
    println("  %-16s  %10s  %10s  %7s".format("phase", "seq ms", "par ms", "speedup"))
    println("  " + "-".repeat(48))
    for (pPar in par.report.phases) {
        val pSeq = seqByName[pPar.name] ?: continue
        val seqMs = pSeq.avgNanos / 1e6
        val parMs = pPar.avgNanos / 1e6
        val speedup = if (parMs > 0.0) seqMs / parMs else 0.0
        println("  %-16s  %10.3f  %10.3f  %6.2fx".format(pPar.name, seqMs, parMs, speedup))
    }
    println()
}
