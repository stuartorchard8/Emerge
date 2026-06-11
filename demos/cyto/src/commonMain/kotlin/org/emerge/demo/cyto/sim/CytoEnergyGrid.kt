package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.primitives.Frac
import kotlin.math.exp
import kotlin.math.floor

/**
 * The world's **energy reservoir** — a live, depletable fixed-point grid over the torus (the
 * depletable successor to the static [CytoLightField]).
 *
 * Collectors **draw** energy from the grid cell they sit in (depleting it); cells **return** energy to
 * the grid as they respire / overflow / die. There is no emission and no loss anywhere else, so total
 * energy (reservoir + cells) is **conserved** — a closed system. That gives a hard carrying capacity
 * (biomass can't exceed the energy that exists) and closes the 1-cell-thick-filament hole (a filament
 * can't pull more energy than lies along its length). Access is per **discrete** grid cell (no bilinear
 * interpolation), so every draw/deposit is `Frac`-exact and conservation holds to the unit.
 *
 * (Not yet wired into the biology — see MORPHOGENESIS.md build order. This is the reservoir + the
 * draw/deposit primitives, with conservation provable in isolation; the sim coupling is the next step.)
 */
class CytoEnergyGrid private constructor(private val grid: LongArray) {

    /** Grid-cell index for a logical world position (nearest cell, torus-wrapped). */
    fun indexOf(logicalX: Float, logicalY: Float): Int {
        val gx = wrapIndex(floor((logicalX / SPAN + 0.5f) * RES).toInt())
        val gy = wrapIndex(floor((logicalY / SPAN + 0.5f) * RES).toInt())
        return gy * RES + gx
    }

    /** Draw up to [amount] from cell [idx]; returns what was actually available and debits the grid
     *  by exactly that (never negative). The single source of energy entering a cell. */
    fun draw(idx: Int, amount: Frac): Frac {
        val taken = amount.coerceAtLeast(ZERO).coerceAtMost(Frac(grid[idx]))
        grid[idx] -= taken.raw
        return taken
    }

    /** Return [amount] of energy to cell [idx] (respiration / overflow waste / death). The single
     *  source of energy leaving a cell back to the world. */
    fun deposit(idx: Int, amount: Frac) {
        grid[idx] += amount.raw
    }

    fun at(idx: Int): Frac = Frac(grid[idx])

    /** Total energy held in the reservoir — `reservoir.total() + Σ cell energy` is the invariant. */
    fun total(): Frac {
        var s = 0L
        for (v in grid) s += v
        return Frac(s)
    }

    /** Snapshot of the raw column (for save / the AoS↔SoA equivalence projection). */
    fun rawColumn(): LongArray = grid.copyOf()

    companion object {
        private val ZERO = Frac(0, 1)

        // Geometry shared with the (static) light field: same torus grid + source layout.
        val RES = CytoLightField.RES
        val SPAN = CytoLightField.SPAN
        val HALF = CytoLightField.HALF

        /** A fresh reservoir seeded from the source layout: Gaussian energy bumps at the quarter-point
         *  sources (the initial energy that then cycles through the cells; total is fixed thereafter). */
        fun seeded(): CytoEnergyGrid {
            val grid = LongArray(RES * RES)
            val cellSize = SPAN / RES
            val inv = 1f / (CytoLightField.FALLOFF * CytoLightField.FALLOFF)
            val peak = CytoLightField.STRENGTH.toFloat()
            for (gy in 0 until RES) {
                val wy = -HALF + (gy + 0.5f) * cellSize
                for (gx in 0 until RES) {
                    val wx = -HALF + (gx + 0.5f) * cellSize
                    var sum = 0f
                    for ((sx, sy) in CytoLightField.SOURCES) {
                        val dx = wrapDelta(wx - sx); val dy = wrapDelta(wy - sy)
                        sum += peak * exp(-(dx * dx + dy * dy) * inv)
                    }
                    grid[gy * RES + gx] = Frac.fromFloat(sum).raw
                }
            }
            return CytoEnergyGrid(grid)
        }

        /** Reconstruct from a saved raw column. */
        fun fromRaw(raw: LongArray): CytoEnergyGrid = CytoEnergyGrid(raw.copyOf())

        private fun wrapIndex(i: Int): Int = ((i % RES) + RES) % RES
        private fun wrapDelta(d: Float): Float {
            var x = d
            while (x > HALF) x -= SPAN
            while (x < -HALF) x += SPAN
            return x
        }
    }
}
