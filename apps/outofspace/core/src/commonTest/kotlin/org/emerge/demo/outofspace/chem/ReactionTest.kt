package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first reaction, as arithmetic — increment 1 of `PLAN_ambient_chemistry.md`.
 *
 * Three kinds of thing are worth checking and they are not the same kind. That the rate table is
 * *right*, which is done by re-deriving it from the law it was generated from rather than by
 * comparing it against a recorded run — the `SaturationTest` argument, and the reason a corrupted
 * entry cannot pass here by agreeing with itself. That the stoichiometry closes **atom by atom**
 * against the molar masses `Species` already holds, which is the `MineralTest` argument: mass
 * fractions written by hand are a second source of truth with no oracle. And that the reaction is
 * bounded by what is actually present, in both directions, since a reaction that consumes more
 * oxygen than a room holds is how a conservation law gets broken quietly.
 */
class ReactionTest {

    // ── The rate curve ───────────────────────────────────────────────────────

    /** The law the table was generated from. Floating point, because a test may and the sim may not. */
    private fun arrhenius(reduced: Double): Double = exp(ACTIVATION * (1.0 - 1.0 / reduced))

    @Test
    fun theRateTableIsTheLawItClaimsToBe() {
        // Sampled at the knots and between them: the knots check the table, the midpoints check
        // that reading it interpolates rather than steps.
        for (i in 0..64) {
            val reduced = 1.0 + 3.0 * i / 64.0
            val onset = 700
            val kelvin = (onset * reduced).toInt()
            val expected = arrhenius(kelvin.toDouble() / onset)
            val actual = rateMultiplier(kelvin, onset).toDouble() / SCALE

            // Loose enough to allow the linear chords between knots, tight enough that a wrong
            // entry or a mis-scaled read cannot hide: the interpolation error of a convex curve
            // across 33 knots is a fraction of a percent, and everything else is orders out.
            assertTrue(
                abs(actual - expected) <= 0.01 * expected + 0.01,
                "at ${kelvin}K (reduced $reduced) the table says $actual, the law says $expected",
            )
        }
    }

    @Test
    fun theCurveIsFlatBelowOnsetAndHeldAboveTheTable() {
        assertEquals(SCALE, rateMultiplier(300, 700), "below onset the multiplier is one, not zero")
        assertEquals(SCALE, rateMultiplier(700, 700), "at onset the multiplier is exactly one")

        // Past the top knot the curve is held rather than run on. Two very different temperatures
        // well past it must give the same answer, or the model is extrapolating.
        val top = rateMultiplier(700 * 4, 700)
        assertEquals(top, rateMultiplier(700 * 40, 700), "the multiplier extrapolates past its table")
        assertTrue(top > 80L * SCALE, "the top of the range should be a hundredfold, got ${top / SCALE}")
    }

    @Test
    fun hotterIsNeverSlower() {
        // The property the whole design rests on: temperature is the thing that decides the rate,
        // so the curve may not wobble. A non-monotonic table would make a fire that cools down as
        // it heats up, and no test of a single point would see it.
        var previous = 0L
        for (kelvin in 700..3000 step 7) {
            val now = rateMultiplier(kelvin, 700)
            assertTrue(now >= previous, "the rate fell between ${kelvin - 7}K and ${kelvin}K")
            previous = now
        }
    }

    // ── Stoichiometry ────────────────────────────────────────────────────────

    // ⛔ **The reaction cases that were here have moved to `UnifiedReactionTest`** —
    // `PLAN_unified_reactions.md`, increment 4. They were written against `CARBON_BURN` and the
    // `burn` shorthand, both of which are gone: `Oxidation` existed because its two reagents came
    // from two different stores, and that is what the pass does for every row now.
    //
    // ⚠️ **They were also four near-identical copies.** Every table closed itself atom by atom
    // against its own class's field names, and a fifth table arrived with none — which is how six
    // gas fires reached a live save without the reference knowing they existed. One table, one
    // proof, and it covers rows nobody has written yet.
    //
    // What stays here is the part that was never about a shape: the Arrhenius climb every reaction
    // in the game shares, checked against the law it is generated from.
}
