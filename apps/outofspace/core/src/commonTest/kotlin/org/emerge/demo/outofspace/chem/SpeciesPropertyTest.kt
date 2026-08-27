package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

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
}
