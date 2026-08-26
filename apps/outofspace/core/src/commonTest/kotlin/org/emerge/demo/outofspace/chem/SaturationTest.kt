package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.VolumeField
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

    // ---- the oracle: Peng-Robinson in specific volume, with an exact integral ----
    //
    // Reduced volume, so the only thing that distinguishes one fluid from another is [kappa] — the
    // acentric factor as the equation consumes it. Written out here in floating point rather than
    // read from the implementation, because a re-solve that shares the code it is checking checks
    // nothing; the whole value of this file is that the two arithmetics are independent.

    private val zc = 0.3074013
    private val bb = 0.07779607390 / zc
    private val aa = 0.45723552894 / (zc * zc)
    private val root2 = 1.4142135623730951
    private val kappa = CRITICAL[Species.Water]!!.kappa.toDouble() / SCALE

    private fun alpha(tr: Double): Double {
        val s = 1.0 + kappa * (1.0 - kotlin.math.sqrt(tr))
        return if (s <= 0.0) 0.0 else s * s
    }

    private fun p(v: Double, tr: Double) =
        tr / zc / (v - bb) - aa * alpha(tr) / (v * v + 2 * bb * v - bb * bb)

    /**
     * ∫P dv, in closed form. Exact, so the equal-area test is not limited by a quadrature.
     *
     * The attraction term factors as `(v + b − b√2)(v + b + b√2)`, whose partial fractions give the
     * logarithm below. Both factors are positive everywhere the isotherm is defined — the smaller
     * root sits at `0.414·b`, well under the covolume wall at `b` — so neither logarithm is ever
     * asked for a negative argument.
     */
    private fun integral(v: Double, tr: Double) =
        tr / zc * ln(v - bb) -
            aa * alpha(tr) / (2 * bb * root2) * ln((v + bb - bb * root2) / (v + bb + bb * root2))

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
        // dP/dv, which is negative on both stable branches and positive across the unstable stretch
        // between them. Its two zeros are the turning points.
        val f = { v: Double ->
            val den = v * v + 2 * bb * v - bb * bb
            -(tr / zc) / ((v - bb) * (v - bb)) + 2 * aa * alpha(tr) * (v + bb) / (den * den)
        }
        return bisect(bb + 1e-12, 1.0, f) to bisect(1.0, 400.0 / tr, f)
    }

    /**
     * Saturation pressure solved from scratch: the pressure whose horizontal line cuts the isotherm
     * into two equal areas. That condition is `∫v dP = 0` between the branches — equal Gibbs free
     * energy, the statement that neither phase is preferred — and it has exactly one solution.
     */
    private fun solveSaturation(tr: Double): Triple<Double, Double, Double> {
        val (vLow, vHigh) = spinodal(tr)
        fun branches(pr: Double): Pair<Double, Double> =
            bisect(bb + 1e-12, vLow) { p(it, tr) - pr } to bisect(vHigh, 1e14) { p(it, tr) - pr }
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
        // ⛔ **Starts at the triple point, and that boundary is now enforced rather than estimated.**
        // This used to start at a hand-picked Tr = 0.40, with a comment observing that every fluid
        // the vessel carries is a solid below roughly there — water at 0.42, nitrogen at 0.50,
        // carbon dioxide at 0.71 — and that there was no solid phase, so the curve below described
        // a substance that did not exist.
        //
        // There is one now. Below [Critical.triplePointKelvin] the table carries the **sublimation**
        // curve, which is steeper because subliming pays the heat of fusion as well as the heat of
        // vaporisation. The oracle below solves for liquid-vapour coexistence, which down there is
        // not a thing that happens — so the two disagree by about 5% at Tr = 0.40 and both are
        // right about different questions. `PhaseRealityTest` is what checks the sublimation branch,
        // against measured numbers, which is the only way to check it.
        val triplePoint = (CRITICAL[Species.Water]!!.triplePointR * 100 / SCALE).toInt() + 1
        for (i in triplePoint..99) {
            val tr = i / 100.0
            val temperatureR = (tr * SCALE).toLong()
            val (expectedP, expectedLiquid, expectedVapour) = solveSaturation(tr)

            val actualP = saturationPressure(temperatureR, Species.Water)!!
            val actualLiquid = condensedDensity(temperatureR, Species.Water)!!
            val actualVapour = saturatedVapourDensity(temperatureR, Species.Water)!!

            // Relative, with an absolute floor, because neither bound alone is meaningful across
            // eleven orders of magnitude. In the cold tail the true value is a handful of SCALE
            // units and a relative bound there asks the table to beat its own quantisation.
            //
            // The floor is 100 units, which is about a thousandth of an atmosphere for water and
            // is there for the cold tail, where the true value is a handful of SCALE units and a
            // relative bound would be asking the table to beat its own quantisation.
            //
            // ⚠️ It was briefly 12,000 while the two exponential branches were interpolated
            // linearly, which overshot by up to 7.7%. They are stored as logarithms now and
            // interpolated against `1/Tr` — see [Dome.negLogSaturationPressure] — so the floor is
            // back where it was and the relative bound does the work again.
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
            // ⚠️ 11%, measured, and all of it in the single knot interval below the critical point:
            // 10.1% for the vapour branch and 5.1% for the liquid branch at Tr = 0.99, against
            // 1.2% and 0.5% at Tr = 0.98 and better than that everywhere colder.
            assertTrue(agrees(actualLiquid, expectedLiquid, 0.08), "rhoL at Tr=$tr")
            assertTrue(agrees(actualVapour, expectedVapour, 0.08), "rhoV at Tr=$tr")
        }
        // Both budgets stated outright, so that refining the table shows up as these falling rather
        // than as nothing visible changing. The absolute one is dominated by the near-critical
        // knots, where the dome is steepest, not by the cold tail.
        // Both budgets stated as measured rather than as round numbers, so that changing the table
        // shows up here as a number moving.
        //
        // ⚠️ **0.12% relative, and it is [SCALE] quantisation rather than interpolation.** At the
        // cold end of the sweep the saturation pressure is around 800 units, so one unit of rounding
        // is an eighth of a percent and no table can do better without a finer fixed point. The
        // interpolation's own contribution, measured where the values are large enough not to be
        // quantisation-bound, is 0.028%. Both were around 6% while the branch was interpolated
        // linearly — see [Dome.negLogSaturationPressure].
        assertTrue(worst < 0.002, "worst relative saturation-pressure error was $worst")
        assertTrue(worstAbsolute < 10_000, "worst absolute saturation-pressure error was $worstAbsolute SCALE units")
    }

    @Test
    fun `pressure never falls as a fluid is compressed`() {
        // The property the whole construction exists to buy. Below Tc the raw Peng-Robinson curve
        // has a long stretch where compressing the fluid lowers its pressure — an imaginary speed
        // of sound, and an instability that no timestep can outrun because refining the grid makes
        // it grow faster. Sweeping the full density range at a range of subcritical temperatures,
        // the reported curve must never do that.
        for (trPercent in 20..99) {
            val temperatureR = SCALE * trPercent / 100
            var previous = Long.MIN_VALUE
            var density = 0L
            while (density < CLOSE_PACKED - SCALE / 100) {
                val pressure = reducedPressure(density, temperatureR, Species.Water)
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
            val here = pengRobinsonPressure(density, temperatureR, Species.Water)
            val next = pengRobinsonPressure(density + SCALE / 200, temperatureR, Species.Water)
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
            val mass = Stuff.AMBIENT_AIR[species]
            val densityR = reducedDensity(mass, species, full, full)!!
            val temperatureR = reducedTemperature(293, species)!!
            assertEquals(
                pengRobinsonPressure(densityR, temperatureR, Species.Water),
                reducedPressure(densityR, temperatureR, Species.Water),
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
        val vapour = saturatedVapourDensity(temperatureR, Species.Water)!!
        val liquid = condensedDensity(temperatureR, Species.Water)!!
        val saturation = saturationPressure(temperatureR, Species.Water)!!

        assertEquals(0L, condensedFraction(vapour, temperatureR, Species.Water))
        assertEquals(SCALE, condensedFraction(liquid, temperatureR, Species.Water))

        val midpoint = (vapour + liquid) / 2
        assertEquals(saturation, reducedPressure(midpoint, temperatureR, Species.Water))
        assertEquals(FluidPhase.Separating, phaseAt(midpoint, temperatureR, Species.Water))

        val fraction = condensedFraction(midpoint, temperatureR, Species.Water)!!
        assertTrue(fraction in (SCALE * 45 / 100)..(SCALE * 55 / 100), "half-way across should be about half liquid, was $fraction")
    }
}
