package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.emerge.demo.outofspace.chem.Species

/**
 * The two properties added so that a building can be made of **anything** — thermal conductivity and
 * the temperature at which a thing stops being solid — checked rather than trusted.
 *
 * ⛔ **A table of 340 hand-authored numbers will contain typos, and the failure mode is silent.** The
 * arrangement that makes a typo detectable is an *oracle*: somewhere else in the codebase that
 * already knows the answer, so the value is asserted against something rather than restated. There
 * is one real oracle here — [CRITICAL]'s triple points — and it covers 22 species. For the rest
 * these are range and ordering checks, which cannot catch a wrong digit but can catch a missing
 * entry, a misplaced underscore and a unit confusion, which are the mistakes that actually happen.
 */
class SpeciesPropertyTest {

    /**
     * ⛔ **The load-bearing one.** For every species that has phase data, the melting point and the
     * triple point are the same physical fact and must not be free to drift apart.
     *
     * They differ by a fraction of a kelvin in reality — the triple point is where solid, liquid and
     * vapour coexist and the melting point is that same boundary at one atmosphere — so at integer
     * kelvin they are simply equal, and anything else is a typo in one table or the other.
     */
    @Test
    fun `every melting point agrees with the triple point the phase tables already state`() {
        val wrong = CRITICAL.entries
            .filter { (species, critical) -> species.meltingKelvin != critical.triplePointKelvin }
            .map { (species, critical) ->
                "${species.name}: melts at ${species.meltingKelvin}, triple point ${critical.triplePointKelvin}"
            }
        assertTrue(
            wrong.isEmpty(),
            "melting point disagrees with CRITICAL's triple point:\n  " + wrong.joinToString("\n  "),
        )
    }

    /** Both are stated for every species, or the default of zero is silently standing in for one. */
    @Test
    fun `nothing was left without a conductivity or a melting point`() {
        val missing = Species.ALL
            .filter { it.milliWattsPerMetreKelvin <= 0 || it.meltingKelvin <= 0 }
            .map { "${it.name} (k=${it.milliWattsPerMetreKelvin}, melts=${it.meltingKelvin})" }
        assertTrue(missing.isEmpty(), "species with an unset property: ${missing.joinToString()}")
    }

    /**
     * ⚠️ **A unit check, and the mistake it is really looking for is a dropped or extra `_000`.**
     *
     * Everything solid sits between solid chlorine (0.089 W/m/K) and silver (429). A value outside
     * that band by an order of magnitude is a species written in watts where the column is
     * milliwatts, or the reverse — which is invisible by eye in a table this long and would make one
     * substance conduct a thousand times better than any real material.
     */
    @Test
    fun `every conductivity is inside the range real solids occupy`() {
        val silver = Species.Silver.milliWattsPerMetreKelvin
        val outside = Species.ALL
            .filter { it.milliWattsPerMetreKelvin < 20 || it.milliWattsPerMetreKelvin > silver }
            .map { "${it.name} at ${it.milliWattsPerMetreKelvin} mW/m/K" }
        assertTrue(
            outside.isEmpty(),
            "nothing conducts worse than solid helium or better than silver: ${outside.joinToString()}",
        )
    }

    /**
     * ⛔ **Metals conduct and rock does not, and that ordering is the only thing the estimates are
     * really claiming.** A class estimate cannot be checked against a measurement; what it *can* be
     * held to is the ordering it exists to express. If a silicate ever conducts like a metal, the
     * class table has been edited into nonsense whatever the individual numbers say.
     */
    @Test
    fun `the structural metals conduct far better than the rock they come out of`() {
        val metals = listOf(Species.Copper, Species.Aluminum, Species.Iron, Species.Silver, Species.Gold)
        val rock = listOf(Species.Forsterite, Species.Quartz, Species.Anorthite, Species.Serpentine, Species.Calcite)
        val worstMetal = metals.minOf { it.milliWattsPerMetreKelvin }
        val bestRock = rock.maxOf { it.milliWattsPerMetreKelvin }
        assertTrue(
            worstMetal > bestRock * 5,
            "the worst metal ($worstMetal) does not clearly beat the best rock ($bestRock)",
        )
    }

    /**
     * ⚠️ The ices are the other end of the same ordering, and the one a player will meet first: a
     * building made of water ice has to be a poor conductor *and* fail at a temperature a room
     * reaches, or the whole point of allowing it is lost.
     */
    @Test
    fun `the volatiles fail at temperatures a vessel actually sees`() {
        assertEquals(273, Species.Water.meltingKelvin, "water ice does not melt at zero celsius")
        assertTrue(
            Species.Water.meltingKelvin < 400,
            "a hull of water ice would survive a warm room, which is the case this exists for",
        )
        for (volatile in listOf(Species.Methane, Species.Nitrogen, Species.CarbonDioxide, Species.Ammonia)) {
            assertTrue(
                volatile.meltingKelvin < Species.Water.meltingKelvin,
                "${volatile.name} outlasts water ice, which is the wrong way round",
            )
        }
    }

    /**
     * ⛔ **Firebrick must outlast the furnace it lines**, or the one machine that makes heat is the
     * one machine heat destroys.
     *
     * ⚠️ **And it does not, by a hundred and ten kelvin.** A forsterite refractory softens at about
     * 1890 K and `REACTIONS` has a row at 2000 K — quartz reduction, the head of the chain that
     * reaches silicon, magnesium and titanium. This is asserted in the direction it actually holds,
     * with the collision named, so that whoever wires melting in meets it here rather than in a save.
     */
    @Test
    fun `firebrick softens below the hottest reaction the game asks a furnace for`() {
        val hottest = REACTIONS.maxOf { it.onsetKelvin }
        assertTrue(
            Species.Firebrick.meltingKelvin < hottest,
            "firebrick now outlasts the hottest row (${Species.Firebrick.meltingKelvin} vs $hottest) — " +
                "if that is deliberate this test is the thing to delete, but it was not true when written",
        )
    }

    /**
     * **Which fluids are carrying a condensed-phase heat capacity**, and the fact that six of them are.
     *
     * ⛔ **[Species.specificHeat] is one number per species, and for a fluid that cannot be right.**
     * Liquid water is 4182 J/kg/K and steam is 2080 — a factor of two — and the column has to pick.
     * Seventeen of the twenty-three fluids picked the gas; six picked whatever phase the substance
     * happens to be in at room temperature, which is what you get from looking up "specific heat of
     * X". Every one of the six matches its condensed value to within a digit or two: water 4182 is
     * liquid water, bromine 474 is liquid bromine, iodine 214 is *solid* iodine, mercury 140 is
     * liquid mercury, zinc 388 and cadmium 232 are the solid metals.
     *
     * ⚠️ **This is pinned rather than fixed, on purpose.** There is no single number that is right
     * for a species genuinely living in two phases, so "correcting" these would trade one silent
     * wrongness for another — zinc is solid ore on a belt far more often than it is vapour in a
     * roasting bed. The fix is an enthalpy curve per species, which is `PLAN_specific_heat.md` and
     * is a re-tune of every thermal behaviour in the game rather than an edit to a column.
     *
     * ⚠️ **Zinc and Cadmium are NOT in the exception set and are wrong anyway**, because this test
     * cannot resolve them: the equipartition prediction is itself good to only about 25% for a
     * polyatomic — sulfur dioxide, whose value is *right*, is 23% off it — and zinc and cadmium sit
     * at 21% and 25%. They are named in the doc above and caught by the plan, not by this assertion.
     * A sharper instrument means a hand-typed table of gas-phase values, which is the duplication
     * this check exists to avoid.
     *
     * ⛔ The exception set is **asserted**, not skipped, so it can neither grow unnoticed nor shrink
     * without somebody having to come here and say why — `settleCohesion`'s arrangement for its own
     * two known-bad fluids, and for the same reason.
     */
    @Test
    fun `only the fluids we know about carry a condensed-phase heat capacity`() {
        // Cp = (adiabaticK / 2) * R / M, which is what `adiabaticK` already means: it is stored as
        // 2*Cp/R precisely so this arithmetic stays in integers. R is 8.314 J/mol/K, and molarMass
        // is grams per mole, so the 8314 carries the g -> kg with it.
        val known = setOf(Species.Water, Species.Bromine, Species.Iodine, Species.Mercury)
        val diverging = mutableSetOf<Species>()
        for (fluid in Fluid.ALL) {
            val species = fluid.species
            val predicted = species.adiabaticK * 8314 / (2 * species.molarMass)
            val off = (species.specificHeat - predicted) * 100 / predicted
            if (off > 30 || off < -30) diverging.add(species)
        }
        assertEquals(
            known, diverging,
            "the set of fluids whose heat capacity is not a gas-phase value has changed",
        )
    }
}
