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
 *   ./gradlew :apps:drockets:desktop:benchDrocketsOverlay
 *     --args="path/to/save.bin [warmup] [measure] [filter]"
 *
 * Defaults: warmup=120 ticks, measure=600 ticks, filter=LIVING_STEINER.
 */
fun main(args: Array<String>) {
    val savePath = args.getOrNull(0) ?: "apps/drockets/desktop/drockets-save.bin"
    val warmup = args.getOrNull(1)?.toIntOrNull() ?: 120
    val measure = args.getOrNull(2)?.toIntOrNull() ?: 600
    val filterArg = args.getOrNull(3)
    val withSolver = args.getOrNull(4)?.lowercase() != "hierarchical"

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
    println("mode:    ${if (withSolver) "FORCE_DIRECTED (solver enabled)" else "HIERARCHICAL (solver skipped)"}")
    println()

    val filters = if (filterArg != null) {
        listOf(CladogramFilterMode.valueOf(filterArg))
    } else {
        CladogramFilterMode.entries.toList()
    }

    for (filter in filters) {
        runForFilter(snapshot, filter, warmup, measure, withSolver)
        println()
    }
}

private fun runForFilter(
    snapshot: org.emerge.demo.drockets.DrocketsSnapshot,
    filter: CladogramFilterMode,
    warmup: Int,
    measure: Int,
    withSolver: Boolean,
) {
    val controller = DrocketsController()
    controller.restoreSnapshot(DrocketsSaveCodec.encode(snapshot))

    val cache = LivingAncestryCache()
    val monotone = MonotoneFilter()
    val layoutMemo = CladogramLayoutMemo()
    val forceSolver = ForceDirectedLayoutSolver()

    val physicsMs = DoubleArray(measure)
    val advanceMs = DoubleArray(measure)
    val layoutMs = DoubleArray(measure)
    val monotoneMs = DoubleArray(measure)
    val solveMs = DoubleArray(measure)
    val visibleN = IntArray(measure)
    val visibleBeforeN = IntArray(measure)
    val livingN = IntArray(measure)
    val nodesN = IntArray(measure)
    val lucasN = IntArray(measure)
    val descExpandedN = IntArray(measure)
    val ensureMs = DoubleArray(measure)
    val filterComputeMs = DoubleArray(measure)
    val birthBfsN = IntArray(measure)
    val deathBfsN = IntArray(measure)
    val flipCandN = IntArray(measure)
    val birthsN = IntArray(measure)
    val deathsN = IntArray(measure)

    var lastFrame = controller.tick()
    var lastLivings = lastFrame.lineage.livingLineageIds.size
    val livingsDelta = IntArray(measure)

    fun runFrame(record: Boolean, idx: Int) {
        val physicsStart = TimeSource.Monotonic.markNow()
        controller.stepPhysics()
        val physicsEnd = physicsStart.elapsedNow().inWholeNanoseconds

        val advanceStart = TimeSource.Monotonic.markNow()
        controller.advanceLineageFromPhysics()
        val advanceEnd = advanceStart.elapsedNow().inWholeNanoseconds

        val layoutStart = TimeSource.Monotonic.markNow()
        val frame = controller.currentFrame()
        val layout = layoutMemo.get(frame.lineage)
        val layoutEnd = layoutStart.elapsedNow().inWholeNanoseconds

        val visibleSizeBefore = monotone.visibleSize()
        val livingsNow = frame.lineage.livingLineageIds.size

        val monotoneStart = TimeSource.Monotonic.markNow()
        val visible = monotone.apply(filter, frame.lineage, layout, cache)
        val monotoneEnd = monotoneStart.elapsedNow().inWholeNanoseconds

        val solveStart = TimeSource.Monotonic.markNow()
        if (withSolver) forceSolver.step(layout, frame.lineage, visible)
        val solveEnd = solveStart.elapsedNow().inWholeNanoseconds

        if (record) {
            physicsMs[idx] = physicsEnd / 1_000_000.0
            advanceMs[idx] = advanceEnd / 1_000_000.0
            layoutMs[idx] = layoutEnd / 1_000_000.0
            monotoneMs[idx] = monotoneEnd / 1_000_000.0
            solveMs[idx] = solveEnd / 1_000_000.0
            visibleN[idx] = visible.size
            visibleBeforeN[idx] = visibleSizeBefore
            livingN[idx] = livingsNow
            nodesN[idx] = frame.lineage.nodes.size
            livingsDelta[idx] = livingsNow - lastLivings
            lucasN[idx] = monotone.subCache.lastFocusedLucaCount
            descExpandedN[idx] = monotone.subCache.lastFocusedTotalDescendantsExpanded
            ensureMs[idx] = monotone.subCache.lastEnsureCurrentNanos / 1_000_000.0
            filterComputeMs[idx] = monotone.subCache.lastFilterComputeNanos / 1_000_000.0
            birthBfsN[idx] = monotone.subCache.lastBirthBfsTotalVisited
            deathBfsN[idx] = monotone.subCache.lastDeathBfsTotalVisited
            flipCandN[idx] = monotone.subCache.lastFlipOutCandidateCount
            birthsN[idx] = monotone.subCache.lastBirthsThisCall
            deathsN[idx] = monotone.subCache.lastDeathsThisCall
        }
        lastLivings = livingsNow
        lastFrame = frame
    }

    for (i in 0 until warmup) runFrame(record = false, idx = 0)
    for (i in 0 until measure) runFrame(record = true, idx = i)

    println("--- $filter ---")
    println("visible size (over measurement window):")
    println("  min=${visibleN.min()}  avg=${visibleN.average().toInt()}  max=${visibleN.max()}")
    println()
    printStats("physics ", physicsMs)
    printStats("advance ", advanceMs)
    printStats("layout  ", layoutMs)
    printStats("monotone", monotoneMs)
    printStats(" ensure ", ensureMs)
    printStats(" compute", filterComputeMs)
    printStats("solve   ", solveMs)
    val total = DoubleArray(measure) {
        physicsMs[it] + advanceMs[it] + layoutMs[it] + monotoneMs[it] + solveMs[it]
    }
    printStats("total   ", total)

    // Spike report: top-10 ticks by monotone time, with sim-state context.
    val ranked = (0 until measure).sortedByDescending { monotoneMs[it] }.take(10)
    println()
    println("top-10 monotone spikes:")
    println("  idx     ms  ensure  compute  births  deaths  birthBFS  deathBFS  flipCand")
    for (idx in ranked) {
        println(
            "  %3d  %6.3f  %6.3f   %6.3f    %4d    %4d      %4d      %4d     %5d".format(
                idx, monotoneMs[idx], ensureMs[idx], filterComputeMs[idx],
                birthsN[idx], deathsN[idx],
                birthBfsN[idx], deathBfsN[idx], flipCandN[idx],
            )
        )
    }
    println()
    println("births/tick:  avg=%.2f  max=%d".format(birthsN.average(), birthsN.max()))
    println("deaths/tick:  avg=%.2f  max=%d".format(deathsN.average(), deathsN.max()))
    println("birth BFS visited/tick: avg=%d  max=%d".format(birthBfsN.average().toInt(), birthBfsN.max()))
    println("death BFS visited/tick: avg=%d  max=%d".format(deathBfsN.average().toInt(), deathBfsN.max()))
    println("flipOut candidates/tick: avg=%d  max=%d".format(flipCandN.average().toInt(), flipCandN.max()))
    println()
    println("LUCAs / call (Focused only):  avg=${lucasN.average()}  max=${lucasN.max()}")
    println("Descendants expanded / call:  avg=${descExpandedN.average().toInt()}  max=${descExpandedN.max()}")
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
