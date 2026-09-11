package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.react
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A reaction the air declines to host** — `CH₄ → C + 2 H₂`, restored 2026-09-11.
 *
 * The row this game's reaction plan was written about, and the one that had been deleted for it.
 * `PLAN_unified_reactions.md` opens on methane pyrolysis as the prompt for making reactions
 * store-agnostic; making them store-agnostic is then what killed it, because the pass found the
 * methane in the *air* and the air cannot hold soot — `addTo` looks up `Species.fluid` for carbon,
 * finds nothing, and returns, dropping the mass silently with the ledgers none the wiser.
 *
 * The fix that was reached for at the time was a rule against the row: a fluid principal may only
 * have fluid products. ⛔ **That is a claim about a species and the problem is about a store.** A
 * cargo layer holds every species. So the row is legal, and the *air* refuses it: `runsIn` asks, per
 * store, whether the store can hold what the row makes, and a room full of methane simply does
 * nothing rather than doing something and losing the result.
 *
 * ### What this buys, and why it is not awkward
 *
 * Methane cracks where a player would actually crack it — in a packet on a rail, or in a hopper held
 * at temperature — and both products stay in the packet, so the soot and the hydrogen ride on
 * together and a separator can sort them. A gas-phase pyrolysis in an open room is the odd case; a
 * sealed vessel at 1300 K is how it is done.
 *
 * ⚠️ **The alternative was widening `Fluid` to carry soot** (the plan's parked increment 2), and it
 * is still parked — now for a better reason than "no row needs it". Carbon has no critical point, and
 * `Settling.kt` returns early for a species that has none, so airborne soot would never condense out.
 * It would hang in the room permanently with no route back onto a belt: a silent leak traded for an
 * unremovable one.
 *
 * Sibling of `AmmoniaCrackingTest`, which proved the same shape for the row whose products the air
 * *can* hold — that is why ammonia went first.
 */
class MethanePyrolysisTest {

    private val tiles = 8
    private val tile = TileIndex(2)
    private val kg = Budget.KILOGRAM

    /** A packet: methane in a cargo layer, at a temperature nothing is going to take it off. */
    private fun packet(methane: Long, kelvin: Int): StuffLayer {
        val layer = StuffLayer.empty(tiles)
        layer[tile, Species.Methane] = methane
        layer.setEnergy(tile, layer.heatCapacityAt(tile) * kelvin)
        return layer
    }

    private fun room(methane: Long, kelvin: Int): Pair<MassArray, EnergyArray> {
        val air = MassArray(tiles)
        air.add(tile, Fluid.Methane, methane)
        val energy = EnergyArray(tiles)
        energy[tile] = heatCapacityAt(air, tile) * kelvin
        return air to energy
    }

    private fun vacuum() = MassArray(tiles)

    private fun sweepPacket(layer: StuffLayer, air: MassArray = vacuum()) =
        react(air, EnergyArray(tiles), null, listOf(layer))

    private fun airTotal(air: MassArray): Long {
        var sum = 0L
        for (f in Fluid.ALL) sum += air[tile, f]
        return sum
    }

    // ── In a packet, where it works ──────────────────────────────────────────

    @Test
    fun `methane in a hot packet cracks to soot and hydrogen`() {
        val layer = packet(100 * kg, kelvin = 1500)
        val step = sweepPacket(layer)

        assertTrue(layer[tile, Species.Methane] < 100 * kg, "the methane did not react")
        // ⛔ The assertion that was impossible to make for three weeks. Carbon in a store, from a gas.
        assertTrue(layer[tile, Species.Carbon] > 0L, "no soot came out")
        assertTrue(layer[tile, Species.Hydrogen] > 0L, "no hydrogen came out")
        assertTrue(!step.isNothing, "the pass reported nothing happening")
    }

    @Test
    fun `a packet below the onset does nothing`() {
        // 1200 K: hot enough to be doing something interesting elsewhere in the vessel, and a hundred
        // short of this row. The rate law's one compare.
        val layer = packet(100 * kg, kelvin = 1200)
        val step = sweepPacket(layer)
        assertEquals(100 * kg, layer[tile, Species.Methane])
        assertEquals(0L, layer[tile, Species.Carbon])
        assertTrue(step.isNothing)
    }

    @Test
    fun `the packet weighs exactly what it did`() {
        // Reagent and products all in one store, so no ledger is crossed and the total is not merely
        // close — it is **equal**. `apportion` is what makes it exact: the products are shares of the
        // reactant's own mass and their sum is the total by construction.
        val layer = packet(100 * kg, kelvin = 1500)
        sweepPacket(layer)
        assertEquals(100 * kg, layer.massAt(tile))
    }

    @Test
    fun `the atoms balance across the reaction`() {
        // CH₄ → C + 2 H₂. Carbon is 12 of the 16 grams and hydrogen the other 4, so what comes out
        // has to sit in that ratio whatever the rate did — the check that catches a `split`
        // apportioning by the wrong weights, which yields the right species in the wrong amounts for
        // ever, silently.
        val layer = packet(100 * kg, kelvin = 1600)
        sweepPacket(layer)

        val carbon = layer[tile, Species.Carbon]
        val hydrogen = layer[tile, Species.Hydrogen]
        val consumed = 100 * kg - layer[tile, Species.Methane]
        assertEquals(consumed, carbon + hydrogen)

        val expectedCarbon = consumed * Species.Carbon.molarMass / Species.Methane.molarMass
        // Within a gram, which is the most a telescoping apportionment can be out by.
        assertTrue(
            carbon >= expectedCarbon - Budget.GRAM && carbon <= expectedCarbon + Budget.GRAM,
            "expected about $expectedCarbon of carbon, got $carbon",
        )
    }

    @Test
    fun `cracking cools the packet`() {
        // +75 kJ/mol, endothermic — a tenth of what calcining limestone costs, but the sign is what
        // matters. ⛔ `releasedEnergy` is positive when a reaction gives energy back, and a sign error
        // here is a packet that heats itself by cracking its own methane: a perpetual motion machine
        // that looks like it works.
        val layer = packet(100 * kg, kelvin = 1500)
        val before = layer.energyAt(tile)
        val step = sweepPacket(layer)

        assertTrue(step.releasedEnergy < 0L, "cracking claimed to release energy")
        assertTrue(layer.energyAt(tile) < before, "the packet did not cool")
        assertEquals(before + step.releasedEnergy, layer.energyAt(tile))
    }

    // ── In a room, where it is declined ──────────────────────────────────────

    @Test
    fun `methane in a hot room does not crack`() {
        val (air, energy) = room(100 * kg, kelvin = 1500)
        val step = react(air, energy)

        assertEquals(100 * kg, air[tile, Fluid.Methane], "the room cracked methane it cannot hold")
        assertTrue(step.isNothing, "the pass did something in a store that cannot host the row")
    }

    @Test
    fun `a room full of methane loses not one gram`() {
        // ⛔ **The regression, stated as the number that would have been wrong.** Before the store
        // rule, this pass consumed methane, produced carbon, handed it to `addTo`, and `addTo`
        // dropped it — so the room got lighter every tick and no ledger said so. Declining the row
        // is not the same as running it and losing the result, and this is the difference.
        val (air, energy) = room(100 * kg, kelvin = 1500)
        val before = airTotal(air)
        repeat(8) { react(air, energy) }
        assertEquals(before, airTotal(air), "mass went missing from the air")
    }

    @Test
    fun `the room still burns methane it has oxygen for`() {
        // ⚠️ **Declining one row must not decline the store.** The fires have a fluid principal too,
        // and if `runsIn` were asked about the *store* rather than about the row and the store, a
        // room would have stopped burning anything the moment pyrolysis came back.
        val air = MassArray(tiles)
        air.add(tile, Fluid.Methane, 10 * kg)
        air.add(tile, Fluid.Oxygen, 90 * kg)
        val energy = EnergyArray(tiles)
        energy[tile] = heatCapacityAt(air, tile) * 1500

        react(air, energy)

        assertTrue(air[tile, Fluid.Methane] < 10 * kg, "the methane did not burn")
        assertTrue(air[tile, Fluid.CarbonDioxide] > 0L, "no CO2 came out")
        assertTrue(air[tile, Fluid.Water] > 0L, "no water came out")
    }
}
