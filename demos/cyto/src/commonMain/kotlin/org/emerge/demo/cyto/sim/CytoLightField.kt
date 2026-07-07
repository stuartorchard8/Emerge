package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.primitives.Frac
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
class CytoLightField private constructor(private val grid: LongArray) {

    /** Light at a logical world position at sim-time [tick]. When [CytoTuning.LIGHT_MOVING], it's a single
     *  daylight BAND sweeping across x (a day/night terminator); otherwise the static 4-source field
     *  (tick ignored, the precomputed grid). */
    fun sampleAt(logicalX: Float, logicalY: Float, tick: Long): Frac {
        if (CytoTuning.LIGHT_MOVING) {
            // Vertical band centred at bandCenterX(tick), y-independent so it lights the whole column it
            // passes over; a single torus-wrapped gaussian (no precomputed grid needed for one source).
            val dx = wrapDelta(logicalX - bandCenterX(tick))
            val g = exp(-(dx * dx) / (FALLOFF * FALLOFF))
            return Frac.fromFloat(STRENGTH.toFloat() * g)
        }
        val u = (logicalX / SPAN + 0.5f) * RES
        val v = (logicalY / SPAN + 0.5f) * RES
        val x0 = wrapIndex(floor(u).toInt()); val x1 = wrapIndex(x0 + 1)
        val y0 = wrapIndex(floor(v).toInt()); val y1 = wrapIndex(y0 + 1)
        val fx = (u - floor(u)).toDouble(); val fy = (v - floor(v)).toDouble()
        val a = grid[y0 * RES + x0].toDouble(); val b = grid[y0 * RES + x1].toDouble()
        val c = grid[y1 * RES + x0].toDouble(); val d = grid[y1 * RES + x1].toDouble()
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return Frac((top + (bot - top) * fy).toLong())
    }

    /** The grid value at grid cell ([gx],[gy]) — for rendering the field as a heatmap. */
    fun gridAt(gx: Int, gy: Int): Frac = Frac(grid[wrapIndex(gy) * RES + wrapIndex(gx)])

    companion object {
        /** Grid resolution per axis — value in [CytoTuning.GRID_RES]. */
        const val RES = CytoTuning.GRID_RES

        /** Logical torus extent: [-HALF, HALF) per axis, [SPAN] = 2·HALF wide (see [CytoUnits]). */
        const val HALF = CytoUnits.CELLS_PER_AXIS.toFloat()
        const val SPAN = 2f * HALF

        /** Light sources at the torus quarter-points (a 2×2 equidistant grid). */
        val SOURCES: List<Pair<Float, Float>> = run {
            val q = HALF / 2f
            listOf(-q to -q, -q to q, q to -q, q to q)
        }

        /** Peak light at a source — value in [CytoTuning.LIGHT_STRENGTH]. */
        val STRENGTH = CytoTuning.LIGHT_STRENGTH

        /** Gaussian falloff radius (logical units) — value in [CytoTuning.LIGHT_FALLOFF]. */
        const val FALLOFF = CytoTuning.LIGHT_FALLOFF

        /** x-position (logical) of the moving daylight band's centre at sim-time [tick] — sweeps
         *  −HALF → HALF and wraps once per [CytoTuning.LIGHT_ORBIT_PERIOD] ticks. Deterministic (integer
         *  modulo; no accumulation drift). Also used by renderers to draw the band. */
        fun bandCenterX(tick: Long): Float {
            val period = CytoTuning.LIGHT_ORBIT_PERIOD
            val phase = ((tick % period) + period) % period   // [0, period), guards negative ticks
            return -HALF + phase.toFloat() / period.toFloat() * SPAN
        }

        private var cached: CytoLightField? = null

        /** The world's light field (memoised — the source layout is fixed). */
        fun default(): CytoLightField = cached ?: build().also { cached = it }

        private fun build(): CytoLightField {
            val grid = LongArray(RES * RES)
            val cellSize = SPAN / RES
            val inv = 1f / (FALLOFF * FALLOFF)
            val strength = STRENGTH.toFloat()
            for (gy in 0 until RES) {
                val wy = -HALF + (gy + 0.5f) * cellSize
                for (gx in 0 until RES) {
                    val wx = -HALF + (gx + 0.5f) * cellSize
                    var sum = 0f
                    for ((sx, sy) in SOURCES) {
                        val dx = wrapDelta(wx - sx); val dy = wrapDelta(wy - sy)
                        sum += strength * exp(-(dx * dx + dy * dy) * inv)
                    }
                    grid[gy * RES + gx] = Frac.fromFloat(sum).raw
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
