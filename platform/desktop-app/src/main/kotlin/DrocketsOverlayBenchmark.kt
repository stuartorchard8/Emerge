package org.emerge.desktop

import org.emerge.demo.drockets.CladogramFilterMode
import org.emerge.demo.drockets.CladogramLayout
import org.emerge.demo.drockets.CladogramLayoutMemo
import org.emerge.demo.drockets.DrocketsController
import org.emerge.demo.drockets.DrocketsSaveCodec
import org.emerge.demo.drockets.ForceDirectedLayoutSolver
import org.emerge.demo.drockets.LivingAncestryCache
import org.emerge.demo.drockets.MonotoneFilter
import java.io.File
import kotlin.time.TimeSource

/**
 * Headless overlay-cost benchmark. Loads a snapshot, ticks the controller, and
 * drives the per-frame overlay pipeline (filter cache + monotone wrapper +
 * force-directed solver) directly — no GL — so we can attribute the ~50ms
 * spikes to a specific stage.
 *
 * Usage:
 *   ./gradlew :platform:desktop-app:benchDrocketsOverlay
 *     --args="path/to/save.bin [warmup] [measure] [filter]"
 *
 * Defaults: warmup=120 ticks, measure=600 ticks, filter=LIVING_STEINER.
 */
fun main(args: Array<String>) {
    val savePath = args.getOrNull(0) ?: "platform/desktop-app/drockets-save.bin"
    val warmup = args.getOrNull(1)?.toIntOrNull() ?: 120
    val measure = args.getOrNull(2)?.toIntOrNull() ?: 600
    val filterArg = args.getOrNull(3)

    val saveFile = File(savePath)
    require(saveFile.exists()) { "Save file not found: ${saveFile.absolutePath}" }
    val snapshot = DrocketsSaveCodec.decode(saveFile.readBytes())

    println("=== drockets overlay benchmark ===")
    println("save:    ${saveFile.absolutePath} (${saveFile.length() / 1024} KiB)")
    println("tick:    ${snapshot.tick.value}")
    println("nodes:   ${snapshot.lineage.nodes.size}")
    println("living:  ${snapshot.lineage.livingLineageIds.size}")
    println("warmup:  $warmup ticks")
    println("measure: $measure ticks")
    println()

    val filters = if (filterArg != null) {
        listOf(CladogramFilterMode.valueOf(filterArg))
    } else {
        CladogramFilterMode.entries.toList()
    }

    for (filter in filters) {
        runForFilter(snapshot, filter, warmup, measure)
        println()
    }
}

private fun runForFilter(
    snapshot: org.emerge.demo.drockets.DrocketsSnapshot,
    filter: CladogramFilterMode,
    warmup: Int,
    measure: Int,
) {
    val controller = DrocketsController()
    controller.restoreSnapshot(DrocketsSaveCodec.encode(snapshot))

    val cache = LivingAncestryCache()
    val monotone = MonotoneFilter()
    val layoutMemo = CladogramLayoutMemo()
    val forceSolver = ForceDirectedLayoutSolver()

    val tickMs = DoubleArray(measure)
    val layoutMs = DoubleArray(measure)
    val cacheMs = DoubleArray(measure)
    val monotoneMs = DoubleArray(measure)
    val solveMs = DoubleArray(measure)
    val visibleN = IntArray(measure)
    val rawN = IntArray(measure)

    var lastFrame = controller.tick()

    fun runFrame(record: Boolean, idx: Int) {
        val tickStart = TimeSource.Monotonic.markNow()
        val frame = controller.tick()
        val tickEnd = tickStart.elapsedNow().inWholeNanoseconds

        val layoutStart = TimeSource.Monotonic.markNow()
        val layout = layoutMemo.get(frame.lineage)
        val layoutEnd = layoutStart.elapsedNow().inWholeNanoseconds

        val cacheStart = TimeSource.Monotonic.markNow()
        val raw = when (filter) {
            CladogramFilterMode.LIVING_ANCESTRY -> cache.ancestryVisibleFor(frame.lineage, layout)
            CladogramFilterMode.LIVING_STEINER -> cache.steinerVisibleFor(frame.lineage, layout)
            CladogramFilterMode.LIVING_FOCUSED -> cache.lucaFocusedVisibleFor(frame.lineage, layout)
            CladogramFilterMode.LIVING_AND_CONNECTORS -> cache.connectorsVisibleFor(frame.lineage, layout)
            CladogramFilterMode.ALL -> cache.allVisibleFor(frame.lineage, layout)
            CladogramFilterMode.LIVING_ONLY -> cache.livingOnlyVisibleFor(frame.lineage, layout)
        }
        val cacheEnd = cacheStart.elapsedNow().inWholeNanoseconds

        val monotoneStart = TimeSource.Monotonic.markNow()
        val visible = monotone.apply(raw, filter, frame.lineage, cache)
        val monotoneEnd = monotoneStart.elapsedNow().inWholeNanoseconds

        val solveStart = TimeSource.Monotonic.markNow()
        forceSolver.step(layout, frame.lineage, visible)
        val solveEnd = solveStart.elapsedNow().inWholeNanoseconds

        if (record) {
            tickMs[idx] = tickEnd / 1_000_000.0
            layoutMs[idx] = layoutEnd / 1_000_000.0
            cacheMs[idx] = cacheEnd / 1_000_000.0
            monotoneMs[idx] = monotoneEnd / 1_000_000.0
            solveMs[idx] = solveEnd / 1_000_000.0
            visibleN[idx] = visible.size
            rawN[idx] = raw.size
        }
        lastFrame = frame
    }

    for (i in 0 until warmup) runFrame(record = false, idx = 0)
    for (i in 0 until measure) runFrame(record = true, idx = i)

    println("--- $filter ---")
    println("visible size (over measurement window):")
    println("  min=${visibleN.min()}  avg=${visibleN.average().toInt()}  max=${visibleN.max()}")
    println("raw size (pre-monotone):")
    println("  min=${rawN.min()}  avg=${rawN.average().toInt()}  max=${rawN.max()}")
    println()
    printStats("tick    ", tickMs)
    printStats("layout  ", layoutMs)
    printStats("cache   ", cacheMs)
    printStats("monotone", monotoneMs)
    printStats("solve   ", solveMs)
    val total = DoubleArray(measure) {
        tickMs[it] + layoutMs[it] + cacheMs[it] + monotoneMs[it] + solveMs[it]
    }
    printStats("total   ", total)
}

private fun printStats(label: String, samples: DoubleArray) {
    val sorted = samples.sortedArray()
    val n = sorted.size
    val avg = sorted.average()
    val p50 = sorted[n / 2]
    val p95 = sorted[(n * 95) / 100]
    val p99 = sorted[(n * 99) / 100]
    val max = sorted[n - 1]
    println("$label avg=%6.3f ms  p50=%6.3f  p95=%6.3f  p99=%6.3f  max=%7.3f".format(avg, p50, p95, p99, max))
}
