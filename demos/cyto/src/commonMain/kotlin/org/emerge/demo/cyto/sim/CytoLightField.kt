package org.emerge.demo.cyto.sim

import kotlin.math.exp
import kotlin.math.floor

/**
 * The environmental **light field** — the energy source of the world. A static scalar grid over the
 * torus: the sum of radial decay kernels from a few fixed light [SOURCES]. Cells with a Collector
 * gene read the light at their position and turn it into energy ("no free lunch" — energy is
 * collected from the environment, not minted).
 *
 * It is **non-depletable** for now (cells read it, don't drain it), so the field doesn't depend on
 * the cells at all — it's a fixed steady state we precompute **once** and sample in O(1) per cell
 * (no per-tick distance maths, no per-tick field update, nothing new in the save file). Being a pure
 * function of fixed sources, it is byte-identical in the AoS and SoA reducers by construction.
 *
 * Coordinates are Cyto **logical** units: the torus spans [-HALF, HALF) per axis ([SPAN] wide) and
 * wraps. The four sources sit at the torus quarter-points — a 2×2 equidistant grid (each source
 * [HALF] apart from its neighbours, including across the wrap), so there are bright cores around the
 * sources and dark, contested midpoints between them: a resource gradient that drives competition.
 *
 * (When we go **depletable** later — collecting drains the field, death returns energy to it — this
 * becomes live per-tick state and a closed energy system. The grid + sampling + rendering built here
 * carry straight over; only the "it updates each tick from the cells" part is added.)
 */
class CytoLightField private constructor(private val grid: FloatArray) {

    /** Light at a logical world position (bilinear, torus-wrapped). */
    fun sampleAt(logicalX: Float, logicalY: Float): Float {
        val u = (logicalX / SPAN + 0.5f) * RES
        val v = (logicalY / SPAN + 0.5f) * RES
        val x0 = wrapIndex(floor(u).toInt()); val x1 = wrapIndex(x0 + 1)
        val y0 = wrapIndex(floor(v).toInt()); val y1 = wrapIndex(y0 + 1)
        val fx = u - floor(u); val fy = v - floor(v)
        val a = grid[y0 * RES + x0]; val b = grid[y0 * RES + x1]
        val c = grid[y1 * RES + x0]; val d = grid[y1 * RES + x1]
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return top + (bot - top) * fy
    }

    /** The raw grid value at grid cell ([gx],[gy]) — for rendering the field as a heatmap. */
    fun gridAt(gx: Int, gy: Int): Float = grid[wrapIndex(gy) * RES + wrapIndex(gx)]

    companion object {
        /** Grid resolution per axis (the field is smooth, so a coarse grid is plenty). */
        const val RES = 64

        /** Logical torus extent: [-HALF, HALF) per axis, [SPAN] = 2·HALF wide (see [CytoUnits]). */
        const val HALF = CytoUnits.CELLS_PER_AXIS.toFloat()
        const val SPAN = 2f * HALF

        /** Light sources at the torus quarter-points (a 2×2 equidistant grid). */
        val SOURCES: List<Pair<Float, Float>> = run {
            val q = HALF / 2f   // ±512 for a 1024-half torus
            listOf(-q to -q, -q to q, q to -q, q to q)
        }

        /** Peak light at a source (≈ energy/tick a Collector sitting on it produces). */
        const val STRENGTH = 6f

        /** Gaussian falloff radius (logical units): light is strong within ~σ of a source and decays
         *  to ~0 well before the midpoint (HALF/2 away), leaving dark contested zones between sources. */
        const val FALLOFF = 200f

        private var cached: CytoLightField? = null

        /** The world's light field (memoised — the source layout is fixed). */
        fun default(): CytoLightField = cached ?: build().also { cached = it }

        private fun build(): CytoLightField {
            val grid = FloatArray(RES * RES)
            val cellSize = SPAN / RES
            val inv = 1f / (FALLOFF * FALLOFF)
            for (gy in 0 until RES) {
                val wy = -HALF + (gy + 0.5f) * cellSize
                for (gx in 0 until RES) {
                    val wx = -HALF + (gx + 0.5f) * cellSize
                    var sum = 0f
                    for ((sx, sy) in SOURCES) {
                        val dx = wrapDelta(wx - sx); val dy = wrapDelta(wy - sy)
                        sum += STRENGTH * exp(-(dx * dx + dy * dy) * inv)
                    }
                    grid[gy * RES + gx] = sum
                }
            }
            return CytoLightField(grid)
        }

        private fun wrapIndex(i: Int): Int = ((i % RES) + RES) % RES

        /** Shortest signed distance on the torus (period [SPAN]). */
        private fun wrapDelta(d: Float): Float {
            var x = d
            while (x > HALF) x -= SPAN
            while (x < -HALF) x += SPAN
            return x
        }
    }
}
