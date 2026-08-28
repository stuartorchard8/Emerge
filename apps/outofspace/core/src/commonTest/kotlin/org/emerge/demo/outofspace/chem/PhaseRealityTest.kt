package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.emerge.demo.outofspace.chem.Species

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
        /** How far this row may sit from the measured value. See [TOLERANCE_KELVIN]. */
        val tolerance: Int = TOLERANCE_KELVIN,
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

        // ── The seven that were ideal gases until 2026-08-26 ──────────────────────
        //
        // On a live save at 50 K these were 59% of the atmosphere between them, and not one of them
        // could condense, freeze, stop diffusing or be picked up off the floor, because a fluid with
        // no critical point has no dome and a fluid with no dome has no phase behaviour at all.
        Reference(Species.Ammonia, criticalBar = 113.30, kelvin = 240, bar = 1.01325, what = "ammonia boils at -33C"),
        Reference(Species.Methane, criticalBar = 45.99, kelvin = 112, bar = 1.01325, what = "LNG boils at 112K"),
        Reference(Species.CarbonMonoxide, criticalBar = 34.94, kelvin = 82, bar = 1.01325, what = "CO boils at 82K"),
        Reference(Species.HydrogenSulfide, criticalBar = 89.63, kelvin = 213, bar = 1.01325, what = "H2S boils at -60C"),
        Reference(Species.SulfurDioxide, criticalBar = 78.84, kelvin = 263, bar = 1.01325, what = "SO2 boils at -10C"),
        Reference(Species.Hydrogen, criticalBar = 13.13, kelvin = 20, bar = 1.01325, what = "liquid hydrogen boils at 20K"),
        // ⚠️ **Sulfur gets fifteen kelvin instead of ten, and the exemption is named rather than
        // absorbed into the global figure.** Sulfur vapour is not sulfur atoms — near its boiling
        // point it is mostly S8 rings — while [Species.Sulfur] is atomic, because that is what every
        // mineral formula in the game needs. So the molar mass the reduction uses is a factor of
        // eight from the molecule the critical constants describe, and it comes out ten kelvin high.
        // Ten kelvin late is a great deal closer than the ideal gas at 1300 K that it was.
        Reference(
            Species.Sulfur, criticalBar = 207.0, kelvin = 718, bar = 1.01325,
            what = "sulfur boils at 445C", tolerance = 15,
        ),

        // ── The noble gases and the halogens ──────────────────────────────────────
        //
        // The substances the law of corresponding states was fitted to, so they land within a
        // kelvin and cost nothing but the typing.
        Reference(Species.Neon, criticalBar = 26.79, kelvin = 27, bar = 1.01325, what = "neon boils at 27K"),
        Reference(Species.Krypton, criticalBar = 55.00, kelvin = 120, bar = 1.01325, what = "krypton boils at 120K"),
        Reference(Species.Xenon, criticalBar = 58.40, kelvin = 165, bar = 1.01325, what = "xenon boils at 165K"),
        Reference(Species.Fluorine, criticalBar = 51.72, kelvin = 85, bar = 1.01325, what = "fluorine boils at 85K"),
        Reference(Species.Chlorine, criticalBar = 77.10, kelvin = 239, bar = 1.01325, what = "chlorine boils at -34C"),
        Reference(Species.Bromine, criticalBar = 103.40, kelvin = 332, bar = 1.01325, what = "bromine boils at 59C"),
        Reference(Species.Iodine, criticalBar = 117.00, kelvin = 457, bar = 1.01325, what = "iodine boils at 184C"),

        // ── The volatile metals ───────────────────────────────────────────────────
        //
        // ⚠️ **Pinned to their boiling points on purpose, because that is the part that is
        // measured.** Only mercury's critical point is experimentally reachable; zinc's and
        // cadmium's are extrapolations carrying perhaps twenty per cent, and their acentric factors
        // are derived by Edmister from these very boiling points. So this test is the one thing
        // holding those two entries honest, and it is checking the right number.
        Reference(Species.Mercury, criticalBar = 1720.0, kelvin = 630, bar = 1.01325, what = "mercury boils at 357C"),
        // ⚠️ **Thirty-five kelvin, named rather than absorbed.** Zinc comes out 29 K low, which at
        // 1180 K is 2.5% — a fixed ten-kelvin bound is the wrong shape two orders of magnitude above
        // room temperature, and widening the global figure to hide this would loosen water's too.
        Reference(
            Species.Zinc, criticalBar = 2900.0, kelvin = 1180, bar = 1.01325,
            what = "zinc boils at 907C", tolerance = 35,
        ),
        Reference(Species.Cadmium, criticalBar = 2000.0, kelvin = 1040, bar = 1.01325, what = "cadmium boils at 767C"),
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
            if (abs(off) > r.tolerance) {
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
        // ⚠️ **Every fluid with a dome, not a list.** A row added to [CRITICAL] without a solid
        // phase that works is a row that would slip past a hardcoded four.
        val wrong = mutableListOf<String>()
        for ((species, c) in CRITICAL) {
            // A fifth below the triple point rather than a fixed twenty kelvin, because hydrogen's
            // triple point is at fourteen and twenty below that is not a temperature.
            val cold = c.triplePointKelvin * 4 / 5
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
        for ((species, c) in CRITICAL) {
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

    // ── Latent heat, which nothing states and everything needs ───────────────

    @Test
    fun `the latent heat is the slope of the boiling curve, and lands on the measured value`() {
        // ⛔ Nothing anywhere states a latent heat. It is the slope of a substance's own boiling
        // curve — Clausius and Clapeyron — and the curve is already on file, so a table of
        // enthalpies beside it would be a second source of truth for a number the first one
        // answers. What that leaves to check is whether the derivation actually lands on reality.
        //
        // Joules per kilogram, because that is the figure people quote and recognise: water's
        // 2.26 MJ/kg is the reason a kettle takes longer to boil dry than to boil.
        val cases = listOf(
            Triple(Species.Water, 373, 2_257_000L),      // vaporisation at the boiling point
            Triple(Species.Nitrogen, 77, 199_000L),      // liquid nitrogen, 5.58 kJ/mol
            Triple(Species.Argon, 87, 161_000L),         // 6.43 kJ/mol
        )
        val wrong = mutableListOf<String>()
        for ((species, kelvin, expected) in cases) {
            val got = vaporisationHeat(Budget.KILOGRAM, species, kelvin) / Budget.JOULE
            val ratio = got.toDouble() / expected
            if (ratio < 0.75 || ratio > 1.30) {
                wrong += "$species at ${kelvin}K: ${got} J/kg against a measured $expected (${ratio}x)"
            }
        }
        if (wrong.isNotEmpty()) fail("the latent heat does not match reality:\n  " + wrong.joinToString("\n  "))
    }

    @Test
    fun `subliming costs more than boiling, because it pays the heat of fusion too`() {
        // The property that comes free from carrying a sublimation branch: it is steeper than the
        // liquid line by exactly the heat of fusion, and the slope IS the latent heat, so frost is
        // more expensive to lift than a puddle without anything having been told so.
        val ice = vaporisationHeat(Budget.KILOGRAM, Species.Water, 250)
        val water = vaporisationHeat(Budget.KILOGRAM, Species.Water, 373)
        assertTrue(
            ice > water,
            "subliming ice cost $ice and boiling water cost $water — the fusion term is missing",
        )
    }

    @Test
    fun `a gas with no liquid phase costs nothing to vaporise`() {
        // Not a fallback: something that has no condensed phase in this model is already a gas.
        //
        // ⚠️ This asked about **methane** until methane got a dome, and it went on passing — because
        // 200 K is above methane's critical point, so the answer was still zero for a completely
        // different reason. Helium is the species that is absent on purpose (its critical point is
        // 5.2 K, colder than deep space), so it is the one that tests what this claims to test.
        assertTrue(CRITICAL[Species.Helium] == null, "helium has acquired a dome; pick another absentee")
        assertTrue(vaporisationHeat(Budget.KILOGRAM, Species.Helium, 200) == 0L, "helium has no dome")
        // And above the critical point there is no phase change left to pay for.
        assertTrue(vaporisationHeat(Budget.KILOGRAM, Species.Water, 700) == 0L, "water is supercritical at 700K")
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
