package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.ecs.ParallelExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase-0 gate for the column-partition helpers: every parallel path must be bit-identical to
 * its sequential fallback. (On JS the executor is a no-op, so the assertions hold trivially;
 * on JVM they exercise real fork/join.)
 */
class ColumnPartitionTest {

    private val n = 2000 // above DEFAULT_THRESHOLD so the parallel path actually forks

    @Test
    fun disjointMatchesSequential() {
        val executor = ParallelExecutor()
        try {
            val seq = IntArray(n)
            ColumnPartition.disjoint(n, executor = null) { s, e -> for (i in s until e) seq[i] = i * i }
            val par = IntArray(n)
            ColumnPartition.disjoint(n, executor, threshold = 1) { s, e -> for (i in s until e) par[i] = i * i }
            assertEquals(seq.toList(), par.toList())
        } finally {
            executor.close()
        }
    }

    @Test
    fun additiveMatchesSequential() {
        // Ring of edges: edge k joins slot k and (k+1)%n; each adds a deterministic value to
        // BOTH endpoints — a cross-slot write that needs the per-chunk buffer + merge.
        fun valueOf(k: Int): Long = (k.toLong() * 1_000_003L) xor 0x5DEECE66DL
        val body: (Int, Int, Array<LongArray>) -> Unit = { s, e, out ->
            for (i in s until e) {
                val j = (i + 1) % n
                val v = valueOf(i)
                out[0][i] += v
                out[0][j] += v
            }
        }

        val seq = arrayOf(LongArray(n))
        AdditivePartition(channels = 1).run(n, executor = null, targets = seq, body = body)

        val executor = ParallelExecutor()
        try {
            val par = arrayOf(LongArray(n))
            AdditivePartition(channels = 1).run(n, executor, targets = par, threshold = 1, body = body)
            assertEquals(seq[0].toList(), par[0].toList())
            // sanity: every slot got two edge contributions (its own + its predecessor's)
            assertTrue(seq[0].all { it != 0L })
        } finally {
            executor.close()
        }
    }

    @Test
    fun additiveReusesBuffersAcrossRuns() {
        val executor = ParallelExecutor()
        try {
            val part = AdditivePartition(channels = 2)
            val body: (Int, Int, Array<LongArray>) -> Unit = { s, e, out ->
                for (i in s until e) { out[0][i] += i.toLong(); out[1][i] += 2L * i }
            }
            val a = arrayOf(LongArray(n), LongArray(n))
            part.run(n, executor, targets = a, threshold = 1, body = body)
            // second run on fresh targets must not carry over the first run's buffer contents
            val b = arrayOf(LongArray(n), LongArray(n))
            part.run(n, executor, targets = b, threshold = 1, body = body)
            assertEquals(a[0].toList(), b[0].toList())
            assertEquals(a[1].toList(), b[1].toList())
            assertEquals((n - 1).toLong(), b[0][n - 1])
        } finally {
            executor.close()
        }
    }

    @Test
    fun detectThenApplyMatchesSequentialOrder() {
        val executor = ParallelExecutor()
        try {
            val detect: (Int, Int, MutableList<Int>) -> Unit = { s, e, out ->
                for (i in s until e) if (i % 3 == 0) out.add(i)
            }
            val seq = ArrayList<Int>()
            ColumnPartition.detectThenApply(n, executor = null, detect = detect) { seq.add(it) }
            val par = ArrayList<Int>()
            ColumnPartition.detectThenApply(n, executor, threshold = 1, detect = detect) { par.add(it) }
            // contiguous chunks applied in chunk order ⇒ identical to the sequential sweep
            assertEquals(seq, par)
        } finally {
            executor.close()
        }
    }
}
