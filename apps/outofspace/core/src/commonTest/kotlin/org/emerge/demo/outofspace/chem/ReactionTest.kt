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

    @Test
    fun theReactionClosesAtomByAtom() {
        // C + O₂ → CO₂, checked against the molar masses rather than against 12, 32 and 44 written
        // out again here. If a mass ends up derived from a hand-copied fraction somewhere, this is
        // the assertion that says so.
        assertEquals(
            Species.Carbon.molarMass + Species.Oxygen.molarMass,
            Species.CarbonDioxide.molarMass,
            "one carbon and one O₂ do not weigh what a CO₂ weighs",
        )

        val burned = burn(carbonMass = 100L * Budget.KILOGRAM, oxygenMass = 100L * Budget.KILOGRAM, kelvin = 900)
        assertTrue(burned.reactant > 0L, "nothing burned at 900K with both reagents present")
        assertEquals(
            burned.reactant + burned.oxygen,
            burned.product,
            "the products do not weigh what the reactants did",
        )
        // The ratio, to the unit the flooring allows: mass of O₂ per mass of C is 32/12.
        assertEquals(
            burned.reactant * Species.Oxygen.molarMass / Species.Carbon.molarMass,
            burned.oxygen,
            "the reaction ran off the stoichiometric line",
        )
    }

    // ── Bounds ───────────────────────────────────────────────────────────────

    @Test
    fun coldCarbonDoesNotBurn() {
        val ambient = burn(100L * Budget.KILOGRAM, 100L * Budget.KILOGRAM, kelvin = 293)
        assertTrue(ambient.isNothing, "carbon burned at room temperature")
        // And right up to the line, which is where an off-by-one would live.
        assertTrue(burn(100L * Budget.KILOGRAM, 100L * Budget.KILOGRAM, CARBON_IGNITION_KELVIN - 1).isNothing)
        assertTrue(!burn(100L * Budget.KILOGRAM, 100L * Budget.KILOGRAM, CARBON_IGNITION_KELVIN).isNothing)
    }

    @Test
    fun aStuffyRoomSlowsTheFireRatherThanBreakingIt() {
        // The decision-2 property, and the one with real consequences: the reagent comes from the
        // air, so a room with a trace of oxygen in it burns a trace of carbon and no more. The
        // failure this guards is the reaction taking oxygen that is not there — which the air
        // ledger would report as a leak somewhere else entirely.
        val oxygen = 1L * Budget.GRAM
        val burned = burn(carbonMass = 1000L * Budget.KILOGRAM, oxygenMass = oxygen, kelvin = 2000)

        assertTrue(burned.oxygen <= oxygen, "the reaction consumed oxygen the tile did not have")
        assertTrue(burned.reactant > 0L, "a little oxygen should still burn a little carbon")
        assertEquals(
            burned.reactant * Species.Oxygen.molarMass / Species.Carbon.molarMass,
            burned.oxygen,
            "starved of air, the reaction ran rich instead of slowing down",
        )
    }

    @Test
    fun aFireNeverConsumesMoreThanIsThere() {
        // At an absurd temperature the fraction clamps at all of it. What it must not do is exceed
        // it: a reactant mass larger than the tile's is a negative mass one line later.
        val carbon = 5L * Budget.KILOGRAM
        val burned = burn(carbon, oxygenMass = 1000L * Budget.KILOGRAM, kelvin = 100_000)
        assertTrue(burned.reactant <= carbon, "burned ${burned.reactant} of a ${carbon} lump")
    }

    @Test
    fun aTraceOfCarbonBurnsNothingRatherThanARoundedGram() {
        // Flooring, deliberately: a fraction of a mass unit is zero and the lump keeps it. The
        // alternative is a reaction that mints matter at the bottom of every division, which at a
        // microgram a unit happens on every tile of a long belt at once.
        val burned = burn(carbonMass = 1L, oxygenMass = 100L * Budget.KILOGRAM, kelvin = 800)
        assertTrue(burned.isNothing, "a single unit of carbon burned into something")
    }
}
