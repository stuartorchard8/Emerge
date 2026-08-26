package org.emerge.demo.outofspace.chem

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The equation of state against measured reality, which nothing else here checks.**
 *
 * `SaturationTest` re-solves the equal-area condition and compares it to the tables. That is a
 * transcription check: it proves the tables are what the law says, and it is *incapable* of noticing
 * that the law is wrong — the exact shape [org.emerge.demo.outofspace.world.NumericLimitsTest]'s
 * history warns about, where "a tripwire built from the same assumptions as the code cannot see a
 * mistake in the assumptions". Every number below comes from outside the codebase.
 *
 * The claim being tested is legibility, not thermodynamic research: a player who sees water on a
 * deck should see it boil at about a hundred degrees, and a nitrogen line should read as cryogenic
 * rather than merely cold. [TOLERANCE_KELVIN] is set where a wrong answer stops being a rounding
 * story and starts being a different substance.
 *
 * ⚠️ **Reduced units throughout, so this needs no opinion about the game's pressure scale.** A
 * saturation pressure is quoted as a fraction of the species' own critical pressure, which is a
 * measured quantity that lives here and deliberately not in [CRITICAL] — an oracle the code shares
 * is not an oracle.
 */
class PhaseRealityTest {

    /**
     * What a species actually does, from the literature. Critical constants from NIST; vapour
     * pressures at the stated temperature likewise.
     */
    private class Reference(
        val species: Species,
        /** Measured critical point. */
        val criticalBar: Double,
        /** A temperature at which this species' vapour pressure is known, and that pressure. */
        val kelvin: Int,
        val bar: Double,
        val what: String,
    )

    private val references = listOf(
        // The headline: one atmosphere, one hundred degrees. This is the number the HUD has been
        // apologising for -- "this model boils water at -33C".
        Reference(Species.Water, criticalBar = 220.64, kelvin = 373, bar = 1.01325, what = "water boils at 100C"),
        // And the same substance at room temperature, where the error is a factor rather than a
        // shift: this is what `offGas` reads to decide how much water a room will hold.
        Reference(Species.Water, criticalBar = 220.64, kelvin = 293, bar = 0.02339, what = "water barely evaporates at 20C"),
        Reference(Species.Nitrogen, criticalBar = 33.96, kelvin = 77, bar = 1.01325, what = "liquid nitrogen boils at 77K"),
        Reference(Species.Oxygen, criticalBar = 50.43, kelvin = 90, bar = 1.01325, what = "liquid oxygen boils at 90K"),
        Reference(Species.Argon, criticalBar = 48.63, kelvin = 87, bar = 1.01325, what = "liquid argon boils at 87K"),
        // Above CO2's triple point (216.6 K, 5.18 bar), so this is a genuine liquid-vapour
        // equilibrium.
        Reference(Species.CarbonDioxide, criticalBar = 73.77, kelvin = 273, bar = 34.85, what = "CO2 in a cylinder at 0C"),

        // ── Below the triple point, where the condensed phase is a solid ──────────
        //
        // The curve does not stop at the triple point, it *bends*: subliming has to pay the heat of
        // fusion as well as the heat of vaporisation, so `ln P` falls away more steeply below the
        // triple point than the liquid line extrapolated through it would.
        //
        // Dry ice is the marquee case and the one everybody has a feel for. Carbon dioxide has no
        // liquid phase at one atmosphere at all — its triple point is at 5.18 bar — so a block of it
        // goes straight to gas at 194.7 K, and a model that extrapolates the liquid line answers
        // about 20 K too warm.
        Reference(Species.CarbonDioxide, criticalBar = 73.77, kelvin = 195, bar = 1.01325, what = "dry ice sublimes at -78C"),
        Reference(Species.Water, criticalBar = 220.64, kelvin = 263, bar = 2.599e-3, what = "ice at -10C"),
        Reference(Species.Water, criticalBar = 220.64, kelvin = 250, bar = 7.60e-4, what = "ice at -23C"),
    )

    /** The model's saturation pressure at [kelvin], in bar, using [criticalBar] to leave reduced units. */
    private fun modelBar(species: Species, kelvin: Int, criticalBar: Double): Double? {
        val tr = reducedTemperature(kelvin, species) ?: return null
        val pr = saturationPressure(tr, species) ?: return null
        return pr.toDouble() / SCALE.toDouble() * criticalBar
    }

    /** The temperature at which the model says [species] reaches [bar], or null if it never does. */
    private fun modelBoilingKelvin(species: Species, bar: Double, criticalBar: Double): Int? {
        val tc = CRITICAL[species]?.kelvin ?: return null
        var lo = 1
        var hi = tc - 1
        if ((modelBar(species, hi, criticalBar) ?: return null) < bar) return null
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val p = modelBar(species, mid, criticalBar) ?: return null
            if (p < bar) lo = mid + 1 else hi = mid
        }
        return lo
    }

    @Test
    fun `every fluid boils at the temperature it really boils at`() {
        val wrong = mutableListOf<String>()
        for (r in references) {
            val got = modelBoilingKelvin(r.species, r.bar, r.criticalBar)
            if (got == null) {
                wrong += "${r.species}: the model never reaches ${r.bar} bar below its critical point " +
                    "(${r.what}, expected ${r.kelvin}K)"
                continue
            }
            val off = got - r.kelvin
            if (abs(off) > TOLERANCE_KELVIN) {
                wrong += "${r.species}: ${r.what} — model says ${got}K, reality says ${r.kelvin}K " +
                    "(${if (off > 0) "+" else ""}${off}K)"
            }
        }
        if (wrong.isNotEmpty()) fail("the equation of state disagrees with reality:\n  " + wrong.joinToString("\n  "))
    }

    @Test
    fun `water at room temperature is not a boiling liquid`() {
        // Stated separately because it is the failure the rest of the game actually feels. `offGas`
        // reads the saturated vapour density to decide how much water a room will take before it
        // stops accepting any; if that ceiling is out by two orders of magnitude then every wet ore
        // lump in the vessel empties itself into the air and the atmosphere is wrong by tonnes.
        val got = modelBar(Species.Water, 293, 220.64) ?: fail("water has no saturation pressure at 293K")
        val real = 0.02339
        assertTrue(
            got < real * 10,
            "water's vapour pressure at 20C reads $got bar against a real $real bar — " +
                "${(got / real).toInt()}x too high, so a room holds that much more water vapour " +
                "than it should before anything condenses",
        )
    }

    // ── The solid phase itself ───────────────────────────────────────────────

    @Test
    fun `below its triple point a fluid is a solid and not a very cold liquid`() {
        // The triple point is the temperature below which there is no liquid phase at any pressure
        // at all. A model without one does not merely mislabel it: it hands the transport pass a
        // liquid, which flows, where the world has a solid, which does not.
        val wrong = mutableListOf<String>()
        for (species in listOf(Species.Water, Species.CarbonDioxide, Species.Nitrogen, Species.Argon)) {
            val c = CRITICAL[species]!!
            val cold = c.triplePointKelvin - 20
            val tr = reducedTemperature(cold, species)!!
            // Dense enough to be condensed at all — the solid branch itself.
            val dense = condensedDensity(tr, species)!!
            val phase = phaseAt(dense, tr, species)
            if (phase != FluidPhase.Solid) {
                wrong += "$species at ${cold}K is ${c.triplePointKelvin - cold}K below its triple " +
                    "point and reads as $phase"
            }
        }
        if (wrong.isNotEmpty()) fail("a fluid below its triple point is not solid:\n  " + wrong.joinToString("\n  "))
    }

    @Test
    fun `above the triple point the condensed phase is still a liquid`() {
        // The other half, so that the test above cannot be satisfied by calling everything solid.
        for (species in listOf(Species.Water, Species.CarbonDioxide, Species.Nitrogen, Species.Argon)) {
            val c = CRITICAL[species]!!
            val warm = (c.triplePointKelvin + c.kelvin) / 2
            val tr = reducedTemperature(warm, species)!!
            val dense = condensedDensity(tr, species)!!
            assertTrue(
                phaseAt(dense, tr, species) == FluidPhase.Liquid,
                "$species at ${warm}K is between its triple point and its critical point and " +
                    "should be a liquid; got ${phaseAt(dense, tr, species)}",
            )
        }
    }

    private companion object {
        /**
         * How far a boiling point may sit from the measured one.
         *
         * Ten degrees, because that is roughly where a wrong answer stops reading as a model being
         * approximate and starts reading as a different substance — water boiling at 80C is a coarse
         * simulation, water boiling at -33C is not water. Peng-Robinson lands inside this for every
         * row above; van der Waals misses every single one, by 15K at best and 118K at worst.
         */
        const val TOLERANCE_KELVIN = 10
    }
}
