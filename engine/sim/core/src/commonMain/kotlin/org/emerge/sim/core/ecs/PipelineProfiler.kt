package org.emerge.sim.core.ecs

/**
 * Lightweight aggregator for per-phase and per-tick simulation timings. Pass an
 * instance to [runSequential] or [runParallel] and the runner writes one sample
 * per phase per tick. Feed whole-tick wall-time samples through [recordTick]
 * from the benchmark harness (the runner doesn't time the enclosing tick itself
 * because the reducer's build/emit bookkeeping sits outside the runner).
 *
 * Not thread-safe: every method must be called from the same thread. Isolated-
 * phase worker threads never touch the profiler — only the outer dispatcher does,
 * which is already single-threaded.
 *
 * Overhead per phase call is ~one monotonic clock read plus a map lookup; cheap
 * enough to leave on in real runs, but the recommended pattern is opt-in via a
 * reducer constructor arg so production builds pay nothing.
 */
class PipelineProfiler {
    private val phases = LinkedHashMap<String, PhaseAccum>()
    private val tickSamples = ArrayList<Long>()

    private class PhaseAccum(
        var totalNanos: Long = 0L,
        var maxNanos: Long = 0L,
        var count: Long = 0L,
    )

    /** Records one [nanos] sample against the named phase. */
    fun recordPhase(name: String, nanos: Long) {
        val acc = phases.getOrPut(name) { PhaseAccum() }
        acc.totalNanos += nanos
        acc.count += 1
        if (nanos > acc.maxNanos) acc.maxNanos = nanos
    }

    /** Records one whole-tick [nanos] sample. Samples are kept for percentile computation. */
    fun recordTick(nanos: Long) {
        tickSamples += nanos
    }

    /** Clears every accumulator. Use between warmup and the measurement window. */
    fun reset() {
        phases.clear()
        tickSamples.clear()
    }

    /** Number of tick samples recorded since the last [reset]. */
    val tickCount: Int get() = tickSamples.size

    data class Report(
        val tickCount: Int,
        val tickAvgNanos: Long,
        val tickMinNanos: Long,
        val tickMaxNanos: Long,
        val tickP50Nanos: Long,
        val tickP95Nanos: Long,
        val tickP99Nanos: Long,
        val phases: List<PhaseLine>,
    )

    /**
     * Per-phase rollup. [avgNanos] / [maxNanos] are across recorded samples.
     * [sharePercent] is this phase's share of the SUM of all phase totals — useful
     * for "where is the tick spending its time" but note that with parallel
     * dispatch the per-phase wall time is already the max across workers, so
     * shares summed over all phases add up to ~tick wall time (not CPU time).
     */
    data class PhaseLine(
        val name: String,
        val avgNanos: Long,
        val maxNanos: Long,
        val sharePercent: Double,
        val sampleCount: Long,
    )

    fun report(): Report {
        val sorted = tickSamples.sorted()
        val n = sorted.size
        val tickAvg = if (n > 0) sorted.sum() / n else 0L
        val tickMin = sorted.firstOrNull() ?: 0L
        val tickMax = sorted.lastOrNull() ?: 0L
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val p99 = percentile(sorted, 0.99)

        val totalPhaseNanos = phases.values.sumOf { it.totalNanos }.coerceAtLeast(1L)
        val lines = phases.map { (name, acc) ->
            PhaseLine(
                name = name,
                avgNanos = if (acc.count > 0) acc.totalNanos / acc.count else 0L,
                maxNanos = acc.maxNanos,
                sharePercent = (acc.totalNanos * 100.0) / totalPhaseNanos,
                sampleCount = acc.count,
            )
        }

        return Report(
            tickCount = n,
            tickAvgNanos = tickAvg,
            tickMinNanos = tickMin,
            tickMaxNanos = tickMax,
            tickP50Nanos = p50,
            tickP95Nanos = p95,
            tickP99Nanos = p99,
            phases = lines,
        )
    }

    private fun percentile(sortedAsc: List<Long>, p: Double): Long {
        if (sortedAsc.isEmpty()) return 0L
        val idx = ((sortedAsc.size - 1) * p).toInt().coerceIn(0, sortedAsc.size - 1)
        return sortedAsc[idx]
    }
}
