package org.emerge.sim.core.ecs

import kotlin.math.sqrt

/**
 * Partitions an all-pairs nested loop `for i in 0..n, for j in i+1..n` into
 * [chunkCount] contiguous i-ranges such that each chunk sees roughly the same
 * pair count. Returns an `IntArray` of length `chunkCount + 1`; chunk `c` covers
 * `[out[c], out[c+1])`. First entry is always 0, last is always `n`.
 *
 * Plain equal-width contiguous chunking is badly imbalanced for a triangular
 * workload: chunk 0 pairs with `[1, n)`, chunk `K-1` barely pairs at all — up to
 * ~`K` times more work in chunk 0 than chunk `K-1`. With 4× oversubscription and
 * a work-stealing pool that residual imbalance still costs us ~30 % efficiency
 * at n=500. Triangular chunking closes that gap analytically: for a total of
 * `T = n*(n-1)/2` pairs, set chunk `c`'s start to the smallest `i_c` such that
 * `pairs(0..i_c) ≈ (c/K) * T`. Solving the quadratic gives `i_c ≈ n*(1 - sqrt(1 - c/K))`.
 *
 * Safe for any `n >= 0`; when `n < 2` every chunk is empty.
 */
fun triangularChunkBounds(n: Int, chunkCount: Int): IntArray {
    require(chunkCount >= 1) { "chunkCount must be >= 1, was $chunkCount" }
    val out = IntArray(chunkCount + 1)
    if (n <= 0) return out
    out[0] = 0
    for (c in 1 until chunkCount) {
        val fraction = c.toDouble() / chunkCount
        val boundary = (n * (1.0 - sqrt(1.0 - fraction))).toInt()
        // Keep strictly monotonic so each chunk has at most 1 more i than an
        // exact-analytical split would give, and never goes backwards.
        out[c] = boundary.coerceAtLeast(out[c - 1]).coerceAtMost(n)
    }
    out[chunkCount] = n
    return out
}
