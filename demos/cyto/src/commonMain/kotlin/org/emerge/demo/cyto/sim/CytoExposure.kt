package org.emerge.demo.cyto.sim

/**
 * How **exposed** a cell is — i.e. how much open space surrounds it, so that only surface cells of a
 * colony harvest the environment (buried cells are fed by inward diffusion). This makes a colony's
 * energy income scale with its *surface* rather than its *volume*, which is the density dependence
 * that stops exponential population growth (see MORPHOGENESIS.md).
 *
 * Exposure = the largest angular gap between a cell's connected neighbours, as a fraction of the full
 * turn: a cell ringed by neighbours on all sides has only small gaps → ~0 (buried); a cell with an
 * open arc → large (exposed). To keep it cheap (this runs per cell per tick) it avoids `atan2`: it
 * sorts neighbours by a monotonic **diamond angle** ([diamondAngle]) — one divide, no trig,
 * scale-invariant, so it's computed straight off raw deltas and is identical on the AoS/SoA paths.
 */
object CytoExposure {

    /** Monotonic pseudo-angle of (x, y) in [0, 4), increasing CCW — an `atan2`-free, trig-free,
     *  scale-invariant ordering key (the "diamond angle"). 1 unit ≈ 90°. */
    fun diamondAngle(x: Float, y: Float): Float {
        if (x == 0f && y == 0f) return 0f
        return if (y >= 0f) {
            if (x >= 0f) y / (x + y) else 1f - x / (-x + y)
        } else {
            if (x < 0f) 2f - y / (-x - y) else 3f + x / (x - y)
        }
    }

    /** Exposure weight in [0,1] from the diamond-angles of a cell's neighbours, filled into
     *  [angles] (only `[0, count)` is read; the array is sorted in place). 0 neighbours → 1 (a lone
     *  cell is fully exposed); fully ringed → ~0. */
    fun weight(angles: FloatArray, count: Int): Float {
        if (count <= 0) return 1f
        // insertion sort (count is tiny — a cell's neighbour degree)
        for (i in 1 until count) {
            val v = angles[i]
            var j = i - 1
            while (j >= 0 && angles[j] > v) { angles[j + 1] = angles[j]; j-- }
            angles[j + 1] = v
        }
        var maxGap = angles[0] + 4f - angles[count - 1]   // the wrap-around gap
        for (i in 1 until count) {
            val g = angles[i] - angles[i - 1]
            if (g > maxGap) maxGap = g
        }
        return (maxGap * 0.25f).coerceIn(0f, 1f)
    }

    /** Max neighbours considered (a cell with more is buried → tiny exposure regardless). */
    const val MAX_NEIGHBOURS = 32
}
