package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.ecs.ParallelExecutor

/**
 * The three column-partition parallel patterns the cyto spike proved, lifted into the SoA
 * framework. Each partitions a dense column `0 until n` into contiguous slot ranges and is
 * **bit-identical to its own sequential fallback** — the property the cross-peer determinism
 * gate depends on. A null [ParallelExecutor] or a count below the threshold runs sequentially.
 *
 * Which pattern a system uses is dictated by its write shape:
 *  - [disjoint] — each slot writes only its own outputs (no cross-slot writes) → partition,
 *    no merge.
 *  - [AdditivePartition] — a unit of work writes *two* endpoints' accumulators (e.g. a spring
 *    impulses both its cells) → per-chunk thread-local `Long` buffers summed on the main
 *    thread; integer add is order-independent, so the sum is bit-identical.
 *  - [detectThenApply] — parallel *detect* into per-chunk lists, then *apply* sequentially in
 *    chunk-then-detection order (e.g. contacts: detect is read-only, application mutates).
 */
object ColumnPartition {
    /** Below this slot count, fork/join dispatch overhead outweighs the parallel win. */
    const val DEFAULT_THRESHOLD = 256

    /**
     * Contiguous chunk boundaries over `0 until n` for [chunks] workers (matching the spike):
     * `(c * ceil(n/chunks))` clamped to `n`. Some trailing ranges may be empty — callers skip
     * `start >= end`.
     */
    fun chunkBounds(n: Int, chunks: Int): IntArray {
        val c = chunks.coerceAtMost(n).coerceAtLeast(1)
        val step = (n + c - 1) / c
        val bounds = IntArray(c + 1)
        for (i in 0..c) bounds[i] = (i * step).coerceAtMost(n)
        return bounds
    }

    /**
     * Pattern 1 — disjoint per-slot writes. Runs [body] over partitioned `[start, end)` ranges.
     * [body] must write only outputs owned by slots in its range (no cross-slot writes), so no
     * merge is needed.
     */
    fun disjoint(
        n: Int,
        executor: ParallelExecutor?,
        threshold: Int = DEFAULT_THRESHOLD,
        body: (start: Int, end: Int) -> Unit,
    ) {
        if (executor == null || n < threshold) {
            if (n > 0) body(0, n)
            return
        }
        val bounds = chunkBounds(n, executor.parallelism)
        val tasks = ArrayList<() -> Unit>(bounds.size - 1)
        for (c in 0 until bounds.size - 1) {
            val s = bounds[c]; val e = bounds[c + 1]
            if (s < e) tasks.add { body(s, e) }
        }
        executor.invokeAll(tasks)
    }

    /**
     * Pattern 3 — detect then apply. [detect] is called over partitioned ranges (read-only /
     * worker-safe) collecting records into a per-chunk list; [apply] then runs over every
     * record sequentially in chunk-then-detection order, so the mutation order matches the
     * single-threaded sweep exactly.
     */
    fun <T> detectThenApply(
        n: Int,
        executor: ParallelExecutor?,
        threshold: Int = DEFAULT_THRESHOLD,
        detect: (start: Int, end: Int, out: MutableList<T>) -> Unit,
        apply: (T) -> Unit,
    ) {
        if (executor == null || n < threshold) {
            if (n == 0) return
            val out = ArrayList<T>()
            detect(0, n, out)
            for (r in out) apply(r)
            return
        }
        val bounds = chunkBounds(n, executor.parallelism)
        val chunks = bounds.size - 1
        val buckets = arrayOfNulls<MutableList<*>>(chunks)
        val tasks = ArrayList<() -> Unit>(chunks)
        for (c in 0 until chunks) {
            val s = bounds[c]; val e = bounds[c + 1]; if (s >= e) continue
            val cc = c
            tasks.add { val local = ArrayList<T>(); detect(s, e, local); buckets[cc] = local }
        }
        executor.invokeAll(tasks)
        for (c in 0 until chunks) {
            @Suppress("UNCHECKED_CAST")
            val bucket = buckets[c] as MutableList<T>? ?: continue
            for (r in bucket) apply(r)
        }
    }
}

/**
 * Pattern 2 — additive cross-slot accumulation. Holds reusable per-chunk `Long` buffers (one
 * set per worker, [channels] arrays each) so the parallel path allocates nothing per tick.
 * [run]'s body accumulates into the buffers it's handed (its own chunk's, in parallel; the
 * real [targets] directly, when sequential); the main thread then sums every chunk's buffers
 * onto [targets]. Because `Long` addition is associative and commutative, the result is
 * independent of chunk count and order — bit-identical to the sequential `body(0, n, targets)`.
 */
class AdditivePartition(private val channels: Int) {
    private var chunkBufs: Array<Array<LongArray>> = emptyArray() // [chunk][channel]
    private var bufChunks = 0
    private var bufN = 0

    fun run(
        n: Int,
        executor: ParallelExecutor?,
        targets: Array<LongArray>,
        threshold: Int = ColumnPartition.DEFAULT_THRESHOLD,
        body: (start: Int, end: Int, out: Array<LongArray>) -> Unit,
    ) {
        require(targets.size == channels) { "expected $channels target channels, got ${targets.size}" }
        if (executor == null || n < threshold) {
            if (n > 0) body(0, n, targets)
            return
        }
        val bounds = ColumnPartition.chunkBounds(n, executor.parallelism)
        val chunks = bounds.size - 1
        ensureBuffers(chunks, n)
        val tasks = ArrayList<() -> Unit>(chunks)
        for (c in 0 until chunks) {
            val s = bounds[c]; val e = bounds[c + 1]; if (s >= e) continue
            val cc = c
            tasks.add { body(s, e, chunkBufs[cc]) }
        }
        executor.invokeAll(tasks)
        for (c in 0 until chunks) {
            val bufs = chunkBufs[c]
            for (ch in 0 until channels) {
                val src = bufs[ch]; val dst = targets[ch]
                for (k in 0 until n) dst[k] += src[k]
            }
        }
    }

    private fun ensureBuffers(chunks: Int, n: Int) {
        if (bufChunks != chunks || bufN < n) {
            chunkBufs = Array(chunks) { Array(channels) { LongArray(n) } }
            bufChunks = chunks; bufN = n
        } else {
            for (c in 0 until chunks) {
                val bufs = chunkBufs[c]
                for (ch in 0 until channels) bufs[ch].fill(0L, 0, n)
            }
        }
    }
}
