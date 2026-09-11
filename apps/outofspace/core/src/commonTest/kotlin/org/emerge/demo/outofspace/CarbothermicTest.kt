package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.energyAtKelvin
import org.emerge.demo.outofspace.world.react
import org.emerge.demo.outofspace.world.thermalMassAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A carbothermic charge mixed to its own row runs out of both reagents at once.**
 *
 * ⛔ **The property a recipe machine has to be able to rely on, and which two rows did not have.**
 * The plan for a furnace that meters an exact stoichiometric charge rests on a claim that sounds
 * self-evident: mix the reagents in the ratio the reaction states and neither can run out first. It
 * is only true if the recipe's row is *the only row that fires in that chamber*.
 *
 * It was not, for fayalite and ferrosilite. Both made **CO₂** where every other carbothermic row
 * makes CO, and both sat above the Boudouard onset of 973 K — ferrosilite's own onset is 1200 K and
 * `CO₂ + C → 2 CO` is dominant above 1200 K, on the faster [COMBUSTION_BASE_RATE] against
 * reduction's [BASE_RATE]. So the slow row made carbon dioxide and the fast row immediately spent
 * more carbon eating it. A charge mixed to `2 FeSiO₃ : 3 C` stranded part-converted with its carbon
 * gone, and every reading a player had said the ratio was right.
 *
 * ✅ **Both rows make CO now and take twice the carbon**, which is the net of the two steps written
 * as one — `2 FeSiO₃ + 6 C → 2 Fe + 2 Si + 6 CO`. The table's own comment had suspected it for a
 * while: *"CO is the favoured product at these temperatures, and it is what the Boudouard row exists
 * to say."*
 *
 * ⚠️ **A store reacts only with itself and never vents**, so this is measurable in one lump with no
 * room involved — see `BoudouardTest`, whose fixture this is.
 */
class CarbothermicTest {

    private val tiles = 4
    private val tile = TileIndex(1)
    private val kg = Budget.KILOGRAM

    /** Formula-unit masses, so the charge below is stoichiometric by construction and not by hand. */
    private val row = REACTIONS.first { it.principal == Species.Ferrosilite }
    private val mineralUnits = row.reagents.first { it.first == Species.Ferrosilite }.second
    private val carbonUnits = row.reagents.first { it.first == Species.Carbon }.second

    private class Charge(val air: MassArray, val energy: EnergyArray, val lump: StuffLayer)

    /**
     * A lump of ferrosilite and carbon at [kelvin], in the row's own ratio scaled by [grams] per
     * formula-unit mass.
     *
     * ⚠️ **Derived from [REACTIONS] rather than typed**, so this fixture cannot drift away from the
     * table it is testing — which is exactly how the old ratio survived as long as it did.
     */
    private fun charge(grams: Long, kelvin: Int): Charge {
        val air = MassArray(tiles)
        val energy = EnergyArray(tiles)
        val lump = StuffLayer.empty(tiles)
        lump.add(tile, Species.Ferrosilite, mineralUnits * Species.Ferrosilite.molarMass * grams)
        lump.add(tile, Species.Carbon, carbonUnits * Species.Carbon.molarMass * grams)
        lump.setEnergy(tile, energyAtKelvin(lump.thermalMassAt(tile), kelvin))
        return Charge(air, energy, lump)
    }

    /**
     * [passes] of chemistry with the charge **held at [kelvin]**, which is what the element in a
     * furnace is for.
     *
     * ⛔ **Without the re-heat this reaction stops itself in about ten passes, and that is not a
     * fixture detail.** Ferrosilite reduction is worth 1724 kJ endothermic; left alone a charge at
     * 1250 K cools through its own 1200 K onset and quenches — measured, 1250 → 1185 K. So the
     * element is not paying for the ramp and then idling, it is paying for the *reaction*, for as
     * long as the hold lasts. `Furnace.refine` already does the right thing here (it heats whenever
     * the charge is under the setpoint, not only on the way up) but the energy bill for a recipe
     * hold is the enthalpy, not the warm-up.
     */
    private fun sweep(c: Charge, passes: Int, kelvin: Int = 1250) {
        repeat(passes) {
            c.lump.setEnergy(tile, energyAtKelvin(c.lump.thermalMassAt(tile), kelvin))
            react(c.air, c.energy, null, listOf(c.lump))
        }
    }

    private fun held(c: Charge, species: Species): Long = c.lump[tile, species]

    // ── The claim a recipe furnace rests on ──────────────────────────────────

    @Test
    fun `a stoichiometric charge converts past 95 per cent`() {
        // ⛔ **The acceptance test for the whole recipe-furnace premise.** A hold-until-95% release
        // condition is only safe if 95% is reachable; with the old CO₂ row the carbon was gone long
        // before the mineral was, and a furnace waiting for a percentage it could never reach would
        // have held its charge for ever.
        val c = charge(grams = kg, kelvin = 1250)
        val mineralAtLoad = held(c, Species.Ferrosilite)

        sweep(c, passes = 2000)

        val converted = mineralAtLoad - held(c, Species.Ferrosilite)
        assertTrue(
            converted * 100L / mineralAtLoad >= 95L,
            "only ${converted * 100L / mineralAtLoad}% of the ferrosilite converted",
        )
    }

    @Test
    fun `neither reagent is stranded by the other running out`() {
        // ⛔ **This is the one the Boudouard row used to break.** Carbon leaving faster than the row
        // spends it is the whole failure: the mineral is left with nothing to reduce it, and the
        // charge stops converting while still looking like a charge.
        val c = charge(grams = kg, kelvin = 1250)
        val mineralAtLoad = held(c, Species.Ferrosilite)
        val carbonAtLoad = held(c, Species.Carbon)

        sweep(c, passes = 2000)

        val mineralLeft = held(c, Species.Ferrosilite) * 1000L / mineralAtLoad
        val carbonLeft = held(c, Species.Carbon) * 1000L / carbonAtLoad
        // They are consumed on the stoichiometric line — `react` re-derives every reagent from the
        // binding one — so the two fractions track each other rather than merely both shrinking.
        assertTrue(
            carbonLeft - mineralLeft in -20L..20L,
            "the reagents did not deplete together: ${mineralLeft / 10}% mineral vs ${carbonLeft / 10}% carbon left",
        )
    }

    @Test
    fun `the charge never makes carbon dioxide, so Boudouard cannot fire`() {
        // ⛔ **The structural half, and the reason the fix is a chemistry change rather than a bound
        // on the furnace.** Boudouard needs CO₂ as its principal. A carbothermic row that makes CO
        // gives it nothing to work with, so there is no competing consumer of carbon in the chamber
        // at all — not a slow one, not a throttled one, none.
        val c = charge(grams = kg, kelvin = 1250)
        sweep(c, passes = 2000)

        assertEquals(0L, held(c, Species.CarbonDioxide), "the reduction made CO2 for Boudouard to eat")
        assertTrue(held(c, Species.CarbonMonoxide) > 0L, "the reduction made no carbon monoxide")
    }

    @Test
    fun `the lump weighs the same before and after`() {
        // Nothing vents out of a lump and no room is involved, so the whole conversion is internal
        // and the total is an invariant — which is also why a completion hold cannot be measured by
        // watching the charge's mass. See `Furnace`, where that trap is written down.
        val c = charge(grams = kg, kelvin = 1250)
        var before = 0L
        for (sp in Species.ALL) before += held(c, sp)

        sweep(c, passes = 2000)

        var after = 0L
        for (sp in Species.ALL) after += held(c, sp)
        assertEquals(before, after, "the charge changed weight while reacting with itself")
    }
}
