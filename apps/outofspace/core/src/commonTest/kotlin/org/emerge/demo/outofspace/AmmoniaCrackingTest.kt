package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.reactInFluid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A reaction swept over the store its matter is actually in** — increment 1 of
 * `PLAN_unified_reactions.md`.
 *
 * `2 NH₃ → N₂ + 3 H₂` has been in the game since the decomposition table landed, at an onset of
 * 1100 K. It has never fired anywhere a player could see. `DECOMPOSITIONS` is swept by `oxidise`
 * over the **cargo** layers; `offGas` runs over those same layers on the same pass and evicts
 * ammonia from them above its critical point of **405 K**. Seven hundred kelvin below the onset,
 * the reactant is gone.
 *
 * The only place it could ever run is inside a sealed tile, where `holdsAirOut` forbids `offGas`
 * from emptying anything — so ammonia cracking worked if and only if it happened inside a wall.
 *
 * Nothing about the row was wrong. It balances atom for atom, its enthalpy is quoted against its own
 * formula mass, and `DecompositionTest` passes on it. What was wrong was the *store it claimed*, and
 * a row cannot claim a store any more: `Reaction` states a principal, and the pass asks where that
 * principal is.
 *
 * ### Why this row and not methane
 *
 * Methane pyrolysis has exactly the same bug and is deliberately still broken. Its carbon is not
 * something the atmosphere can hold, so moving it needs the fluid field widened first — parked, see
 * the plan's decision 4. Ammonia's products are both fluids, so it proves the shape end to end
 * without dragging that decision along with it.
 *
 * ### And it is the first endothermic gas reaction
 *
 * Every fire is exothermic, so until now nothing had ever taken energy *out* of a room's air by
 * reacting. `applyAirEnthalpy`'s clamp was written as a guard against a hypothetical; this is what
 * it was waiting for.
 */
class AmmoniaCrackingTest {

    private val tiles = 4
    private val tile = TileIndex(1)
    private val kg = Budget.KILOGRAM

    private fun room(vararg parts: Pair<Fluid, Long>, kelvin: Int): Pair<MassArray, EnergyArray> {
        val air = MassArray(tiles)
        for ((fluid, mass) in parts) air.add(tile, fluid, mass)
        val energy = EnergyArray(tiles)
        energy[tile] = heatCapacityAt(air, tile) * kelvin
        return air to energy
    }

    private fun total(air: MassArray): Long {
        var sum = 0L
        for (f in Fluid.ALL) sum += air[tile, f]
        return sum
    }

    @Test
    fun `ammonia in a hot room cracks`() {
        val (air, energy) = room(Fluid.Ammonia to 100 * kg, kelvin = 1400)
        val step = reactInFluid(air, energy)

        assertTrue(air[tile, Fluid.Ammonia] < 100 * kg, "the ammonia did not react")
        assertTrue(air[tile, Fluid.Nitrogen] > 0L, "no nitrogen came out")
        assertTrue(air[tile, Fluid.Hydrogen] > 0L, "no hydrogen came out")
        // ⚠️ The whole point of the increment, stated as a number: this used to be zero everywhere
        // in the vessel except inside a bulkhead.
        assertTrue(!step.isNothing, "the pass reported nothing happening")
    }

    @Test
    fun `a cool room does nothing at all`() {
        // 1000 K is hot enough to be interesting and a hundred short of the onset. The rate law's
        // one compare, and where nearly every tile in the game stops.
        val (air, energy) = room(Fluid.Ammonia to 100 * kg, kelvin = 1000)
        val before = air[tile, Fluid.Ammonia]
        val step = reactInFluid(air, energy)
        assertEquals(before, air[tile, Fluid.Ammonia])
        assertTrue(step.isNothing)
    }

    @Test
    fun `the tile's gas weighs exactly what it did`() {
        // The strongest statement available about a pass whose reagents and products all come out of
        // and go back into one field: no ledger is crossed, so the total is not merely close, it is
        // **equal**. `apportion` is what makes it exact — the products are shares of the reactant's
        // own mass and their sum is the total by construction, so no rounding can leak a gram.
        val (air, energy) = room(Fluid.Ammonia to 100 * kg, kelvin = 1400)
        val before = total(air)
        reactInFluid(air, energy)
        assertEquals(before, total(air))
    }

    @Test
    fun `cracking takes heat out of the room`() {
        // +46 kJ/mol: endothermic, so the air must end up holding *less* energy than it started
        // with, and the pass must report a negative number. ⛔ `releasedEnergy` is positive when a
        // reaction gives energy back; a sign error here reads as a room that heats itself by
        // cracking its own ammonia, which is a perpetual motion machine that looks like it works.
        val (air, energy) = room(Fluid.Ammonia to 100 * kg, kelvin = 1400)
        val before = energy[tile]
        val step = reactInFluid(air, energy)
        assertTrue(step.releasedEnergy < 0L, "cracking claimed to release energy")
        assertTrue(energy[tile] < before, "the room did not cool")
        // What the ledger hears is what was actually taken, which is what the clamp makes possible.
        assertEquals(before + step.releasedEnergy, energy[tile])
    }

    @Test
    fun `the atoms balance across the reaction`() {
        // 2 NH₃ → N₂ + 3 H₂. Nitrogen is 28 of the 34 grams and hydrogen the other 6, so what comes
        // out has to sit in that ratio whatever the rate did — the check that catches a `split`
        // apportioning by the wrong weights, which would yield the right species in the wrong
        // amounts, for ever, silently.
        val (air, energy) = room(Fluid.Ammonia to 100 * kg, kelvin = 1500)
        reactInFluid(air, energy)
        val nitrogen = air[tile, Fluid.Nitrogen]
        val hydrogen = air[tile, Fluid.Hydrogen]
        val consumed = 100 * kg - air[tile, Fluid.Ammonia]
        assertEquals(consumed, nitrogen + hydrogen)
        // Within a gram, which is the most a telescoping apportionment can be out by.
        val expectedNitrogen = consumed * Species.Nitrogen.molarMass /
            (Species.Nitrogen.molarMass + 3L * Species.Hydrogen.molarMass)
        assertTrue(
            (nitrogen - expectedNitrogen) * (nitrogen - expectedNitrogen) <= 1L,
            "nitrogen was $nitrogen, expected about $expectedNitrogen",
        )
    }

    @Test
    fun `an empty room is not a reaction`() {
        val (air, energy) = room(Fluid.Nitrogen to 10 * kg, kelvin = 1400)
        val step = reactInFluid(air, energy)
        assertTrue(step.isNothing)
        assertEquals(10 * kg, air[tile, Fluid.Nitrogen])
    }
}
