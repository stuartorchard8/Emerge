package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.fluid.VolumeField
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The saturation dome, and the property the solver's stability rests on.
 *
 * Three things are worth testing here and they are quite different in kind. That the checked-in
 * table is *right* — which is checked by re-solving the equal-area condition from the equation of
 * state, not by comparing against a recorded run, so a corrupted entry cannot pass by agreeing with
 * itself. That the flattening did not disturb anything outside the dome, which is what allowed it
 * to land without moving a single existing pressure in the game. And that `dP/dρ` is nowhere
 * negative below the critical temperature, which is the whole point: a negative slope there is an
 * imaginary speed of sound, and it is why a pool could not previously be stepped at all.
 */
class SaturationTest {

    private val full = VolumeField.FULL

    // ---- the oracle: van der Waals in specific volume, with an exact integral ----

    private fun p(v: Double, tr: Double) = 8 * tr / (3 * v - 1) - 3 / (v * v)

    /** ∫P dv, in closed form. Exact, so the equal-area test is not limited by a quadrature. */
    private fun integral(v: Double, tr: Double) = (8 * tr / 3) * ln(3 * v - 1) + 3 / v

    private fun bisect(a0: Double, b0: Double, f: (Double) -> Double): Double {
        var a = a0
        var b = b0
        var fa = f(a)
        repeat(90) {
            val m = 0.5 * (a + b)
            val fm = f(m)
            if ((fa < 0) == (fm < 0)) { a = m; fa = fm } else b = m
        }
        return 0.5 * (a + b)
    }

    /** The two turning points of the isotherm: pressure minimum (liquid side), maximum (gas side). */
    private fun spinodal(tr: Double): Pair<Double, Double> {
        val f = { v: Double -> (3 * v - 1) * (3 * v - 1) - 4 * tr * v * v * v }
        return bisect(1.0 / 3 + 1e-15, 1.0, f) to bisect(1.0, 200.0 / tr, f)
    }

    /**
     * Saturation pressure solved from scratch: the pressure whose horizontal line cuts the isotherm
     * into two equal areas. That condition is `∫v dP = 0` between the branches — equal Gibbs free
     * energy, the statement that neither phase is preferred — and it has exactly one solution.
     */
    private fun solveSaturation(tr: Double): Triple<Double, Double, Double> {
        val (vLow, vHigh) = spinodal(tr)
        fun branches(pr: Double): Pair<Double, Double> =
            bisect(1.0 / 3 + 1e-15, vLow) { p(it, tr) - pr } to bisect(vHigh, 1e14) { p(it, tr) - pr }
        var lo = p(vLow, tr) + 1e-13
        var hi = p(vHigh, tr) - 1e-13
        repeat(120) {
            val mid = 0.5 * (lo + hi)
            val (vl, vg) = branches(mid)
            val defect = (integral(vg, tr) - integral(vl, tr)) - mid * (vg - vl)
            if (defect > 0) lo = mid else hi = mid
        }
        val pr = 0.5 * (lo + hi)
        val (vl, vg) = branches(pr)
        return Triple(pr, 1 / vl, 1 / vg)
    }

    // ---- the tests ----

    @Test
    fun `the table satisfies the equal-area condition it was built from`() {
        // Sampled off the table's own knots as well as on them, so an entry that is individually
        // plausible but sits wrong against its neighbours is still caught by the interpolation.
        var worst = 0.0
        var worstAbsolute = 0.0
        // Starts at Tr = 0.40 rather than at zero, and that is a statement about the model rather
        // than a convenience. Every fluid the vessel carries is a *solid* below roughly this point
        // — water freezes at Tr 0.42, nitrogen at 0.50, carbon dioxide at 0.71 — and there is no
        // solid phase here, so the curve down there describes a substance that does not exist. What
        // matters below 0.40 is only that the numbers stay small, positive and monotone so the
        // solver stays upright, which `pressure never falls as a fluid is compressed` covers over
        // the whole range.
        for (i in 40..99) {
            val tr = i / 100.0
            val temperatureR = (tr * SCALE).toLong()
            val (expectedP, expectedLiquid, expectedVapour) = solveSaturation(tr)

            val actualP = saturationPressure(temperatureR)!!
            val actualLiquid = saturatedLiquidDensity(temperatureR)!!
            val actualVapour = saturatedVapourDensity(temperatureR)!!

            // Relative, with an absolute floor, because neither bound alone is meaningful across
            // eleven orders of magnitude. In the cold tail the true value is a handful of SCALE
            // units — Psat at Tr=0.16 is 1.77 — and a relative bound there asks the table to beat
            // its own quantisation. The floor is set at 100 units, which is worth about 0.012 atm
            // for water: comfortably below the 0.10 atm the near-critical knots already cost, so it
            // is not where the accuracy of this table is decided.
            fun agrees(actual: Long, expected: Double, tolerance: Double): Boolean =
                abs(actual - expected * SCALE) <= maxOf(100.0, tolerance * expected * SCALE)

            if (expectedP * SCALE > 1000) {
                worst = maxOf(worst, abs(actualP - expectedP * SCALE) / (expectedP * SCALE))
            }
            worstAbsolute = maxOf(worstAbsolute, abs(actualP - expectedP * SCALE))
            assertTrue(agrees(actualP, expectedP, 0.01), "Psat at Tr=$tr: $actualP vs ${expectedP * SCALE}")
            // The two densities get a looser bound than the pressure, and for a specific reason
            // rather than because they are sloppier. The width of the dome closes as √(1 − Tr), so
            // both branches meet the critical point with *infinite* slope, and a table sampled
            // evenly in Tr cannot track a cusp — the error is concentrated in the last few knots
            // and would fall away if the table were sampled evenly in √(1 − Tr) instead. Left
            // as-is because nothing in the vessel runs near a critical point, and the pressure,
            // which is what the solver's stability actually stands on, is unaffected at 0.03%.
            assertTrue(agrees(actualLiquid, expectedLiquid, 0.08), "rhoL at Tr=$tr")
            assertTrue(agrees(actualVapour, expectedVapour, 0.08), "rhoV at Tr=$tr")
        }
        // Both budgets stated outright, so that refining the table shows up as these falling rather
        // than as nothing visible changing. The absolute one is dominated by the near-critical
        // knots, where the dome is steepest, not by the cold tail.
        assertTrue(worst < 0.01, "worst relative saturation-pressure error was $worst")
        assertTrue(worstAbsolute < 40_000, "worst absolute saturation-pressure error was $worstAbsolute SCALE units")
    }

    @Test
    fun `pressure never falls as a fluid is compressed`() {
        // The property the whole construction exists to buy. Below Tc the raw van der Waals curve
        // has a long stretch where compressing the fluid lowers its pressure — an imaginary speed
        // of sound, and an instability that no timestep can outrun because refining the grid makes
        // it grow faster. Sweeping the full density range at a range of subcritical temperatures,
        // the reported curve must never do that.
        for (trPercent in 20..99) {
            val temperatureR = SCALE * trPercent / 100
            var previous = Long.MIN_VALUE
            var density = 0L
            while (density < CLOSE_PACKED - SCALE / 100) {
                val pressure = reducedPressure(density, temperatureR)
                assertTrue(
                    pressure >= previous,
                    "pressure fell at rho=$density, Tr=$trPercent%: $previous -> $pressure",
                )
                previous = pressure
                density += SCALE / 200
            }
        }
    }

    @Test
    fun `the raw equation really does fall, so the test above is not vacuous`() {
        // Guards the guard: if reducedPressure were accidentally wired to something monotonic for a
        // trivial reason, the sweep above would pass while proving nothing. The uncorrected curve
        // must still show the defect the correction removes.
        val temperatureR = SCALE * 45 / 100
        var falls = 0
        var density = SCALE / 100
        while (density < CLOSE_PACKED - SCALE / 100) {
            val here = vanDerWaalsPressure(density, temperatureR)
            val next = vanDerWaalsPressure(density + SCALE / 200, temperatureR)
            if (next < here) falls++
            density += SCALE / 200
        }
        assertTrue(falls > 100, "raw van der Waals should fall across a wide band; it fell $falls times")
    }

    @Test
    fun `outside the dome nothing moved`() {
        // The compatibility claim. Ordinary air is nowhere near condensing, so the Maxwell
        // correction must be invisible to it — otherwise adopting this would have silently shifted
        // every pressure in the game.
        for (species in listOf(Species.Nitrogen, Species.Oxygen, Species.CarbonDioxide)) {
            val grams = AirField.AMBIENT_AIR[species]
            val densityR = reducedDensity(grams, species, full, full)!!
            val temperatureR = reducedTemperature(293, species)!!
            assertEquals(
                vanDerWaalsPressure(densityR, temperatureR),
                reducedPressure(densityR, temperatureR),
                "$species at ambient must be untouched by the saturation dome",
            )
        }
    }

    @Test
    fun `a cell crossing the dome converts instead of compressing`() {
        // What the flat stretch means physically, and the reason it is not merely a numerical
        // dodge: across the whole dome the pressure is one value, and what changes with density is
        // the proportion of the cell that is liquid. That sweep from all-vapour to all-liquid is
        // boiling, run backwards.
        val temperatureR = reducedTemperature(293, Species.Water)!!
        val vapour = saturatedVapourDensity(temperatureR)!!
        val liquid = saturatedLiquidDensity(temperatureR)!!
        val saturation = saturationPressure(temperatureR)!!

        assertEquals(0L, liquidFraction(vapour, temperatureR))
        assertEquals(SCALE, liquidFraction(liquid, temperatureR))

        val midpoint = (vapour + liquid) / 2
        assertEquals(saturation, reducedPressure(midpoint, temperatureR))
        assertEquals(FluidPhase.Separating, phaseAt(midpoint, temperatureR))

        val fraction = liquidFraction(midpoint, temperatureR)!!
        assertTrue(fraction in (SCALE * 45 / 100)..(SCALE * 55 / 100), "half-way across should be about half liquid, was $fraction")
    }
}
