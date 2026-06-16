package org.emerge.demo.cyto.sim.soa

import com.sun.management.ThreadMXBean
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.ecs.PipelineProfiler
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Throwaway perf probe (NOT a gate) — grow a realistic colony, then profile each tick phase
 * for time and per-thread allocation bytes. Run with:
 *   ./gradlew :demos:cyto:jvmTest --tests "*CytoBench*" -i
 */
class CytoBench {

    @Test
    fun profile() {
        val cfg = CytoConfig()   // live config (mutationRateDenom = 100_000)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())

        // Grow to a realistic carrying capacity.
        val grow = 22000
        repeat(grow) {
            w = soa.tick(w, CytoInput.EMPTY)
            if (it % 2000 == 0) java.io.File("/tmp/cytobench_grow.txt").appendText("tick=$it cells=${w.count}\n")
        }

        val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
        tmx.isThreadAllocatedMemoryEnabled = true
        val tid = Thread.currentThread().id
        val profiler = PipelineProfiler()
        profiler.allocReader = { tmx.getThreadAllocatedBytes(tid) }
        val profiled = CytoSoaReducer(cfg, profiler = profiler)

        // warmup
        repeat(200) { w = profiled.tick(w, CytoInput.EMPTY) }
        profiler.reset()

        val measure = 600
        val allocStart = tmx.getThreadAllocatedBytes(tid)
        repeat(measure) {
            val t0 = System.nanoTime()
            w = profiled.tick(w, CytoInput.EMPTY)
            profiler.recordTick(System.nanoTime() - t0)
        }
        val allocPerTick = (tmx.getThreadAllocatedBytes(tid) - allocStart) / measure

        val r = profiler.report()
        val sb = StringBuilder()
        sb.appendLine("grewTicks=$grow cells=${w.count}")
        sb.appendLine("tick avg=%.2f ms p50=%.2f p95=%.2f p99=%.2f".format(
            r.tickAvgNanos / 1e6, r.tickP50Nanos / 1e6, r.tickP95Nanos / 1e6, r.tickP99Nanos / 1e6))
        sb.appendLine("per-cell avg=%.3f us".format(r.tickAvgNanos / 1e3 / w.count))
        sb.appendLine("alloc/tick=%.2f MB  (%.0f B/cell)".format(allocPerTick / 1e6, allocPerTick.toDouble() / w.count))
        sb.appendLine("--- phases (avg us | max us | share%) ---")
        for (p in r.phases.sortedByDescending { it.sharePercent }) {
            sb.appendLine("%-12s %8.2f %8.2f %6.1f%%".format(p.name, p.avgNanos / 1e3, p.maxNanos / 1e3, p.sharePercent))
        }
        java.io.File("/tmp/cytobench_out.txt").writeText(sb.toString())
    }
}
