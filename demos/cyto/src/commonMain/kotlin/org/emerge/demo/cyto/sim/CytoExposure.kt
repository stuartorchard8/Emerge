package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.primitives.Frac

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
    fun diamondAngle(x: Frac, y: Frac): Frac {
        if (x.raw == 0L && y.raw == 0L) return ZERO
        return if (y.raw >= 0L) {
            if (x.raw >= 0L) y / (x + y) else ONE - x / (-x + y)
        } else {
            if (x.raw < 0L) TWO - y / (-x - y) else THREE + x / (x - y)
        }
    }

    /** Exposure weight in [0,1] from the diamond-angles of a cell's neighbours, filled into
     *  [angles] as raw Frac longs (only `[0, count)` is read; the array is sorted in place). 0
     *  neighbours → 1 (a lone cell is fully exposed); fully ringed → ~0. */
    fun weight(angles: LongArray, count: Int): Frac {
        if (count <= 0) return ONE
        // insertion sort (count is tiny — a cell's neighbour degree)
        for (i in 1 until count) {
            val v = angles[i]
            var j = i - 1
            while (j >= 0 && angles[j] > v) { angles[j + 1] = angles[j]; j-- }
            angles[j + 1] = v
        }
        var maxGap = angles[0] + FULL_TURN_RAW - angles[count - 1]   // the wrap-around gap
        for (i in 1 until count) {
            val g = angles[i] - angles[i - 1]
            if (g > maxGap) maxGap = g
        }
        return Frac(maxGap).div(4).coerceIn(ZERO, ONE)
    }

    private val ZERO = Frac(0, 1)
    private val ONE = Frac(1, 1)
    private val TWO = Frac(2, 1)
    private val THREE = Frac(3, 1)
    private val FULL_TURN_RAW = Frac(4, 1).raw

    /** Max neighbours considered — value in [CytoTuning.EXPOSURE_MAX_NEIGHBOURS]. */
    const val MAX_NEIGHBOURS = CytoTuning.EXPOSURE_MAX_NEIGHBOURS
}
