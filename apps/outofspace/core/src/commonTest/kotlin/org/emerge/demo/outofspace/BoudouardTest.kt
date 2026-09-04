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
import org.emerge.demo.outofspace.world.thermalMassAt
import org.emerge.demo.outofspace.world.energyAtKelvin

/**
 * **`CO₂ + C → 2 CO`, both reagents in the one lump.**
 *
 * ⛔ **This file used to be about a reagent in *each* store** — carbon dioxide in the room's air and
 * carbon on a belt — because a cargo layer could not hold CO₂ at 973 K: `offGas` evicted it eleven
 * hundred kelvin earlier, above its critical point of 304 K. Its own note called that out as the
 * problem with the version before it: *"a real behaviour, correctly measured, in a configuration the
 * simulation cannot produce."*
 *
 * ✅ **The configuration is producible now, and the cross-store version is gone.** Off-gassing is
 * opt-in (`PLAN_fluid_thrusters.md` §2.1) so a hot lump keeps its own carbon dioxide, and a store
 * reacts only with itself (§2.2) so that is the only way this row can fire at all. A hot carbon bed
 * making carbon monoxide out of its own exhaust is what it was always supposed to describe, and it
 * is now the thing the test does.
 *
 * ⚠️ **No ledger is crossed any more.** The whole reaction happens inside one cargo layer, so the
 * mass identity is the layer's own rather than a hand-over between two — which is why the two
 * crossing tests below became one conservation test.
 */
class BoudouardTest {

    private val tiles = 4
    private val tile = TileIndex(1)
    private val kg = Budget.KILOGRAM

    private class Room(val air: MassArray, val energy: EnergyArray, val belt: StuffLayer)

    /** Carbon dioxide **and** carbon in one lump on the belt, at [kelvin]. The room is empty. */
    private fun room(dioxide: Long, carbon: Long, kelvin: Int): Room {
        val air = MassArray(tiles)
        val energy = EnergyArray(tiles)

        val belt = StuffLayer.empty(tiles)
        if (dioxide > 0L) belt.add(tile, Species.CarbonDioxide, dioxide)
        if (carbon > 0L) belt.add(tile, Species.Carbon, carbon)
        if (dioxide > 0L || carbon > 0L) {
            belt.setEnergy(tile, energyAtKelvin(belt.thermalMassAt(tile), kelvin))
        }
        return Room(air, energy, belt)
    }

    private fun beltTotal(r: Room): Long {
        var sum = 0L
        for (sp in Species.ALL) sum += r.belt[tile, sp]
        return sum
    }

    private fun sweep(r: Room, passes: Int = 1) {
        repeat(passes) { react(r.air, r.energy, null, listOf(r.belt)) }
    }

    private fun airTotal(r: Room): Long {
        var sum = 0L
        for (f in Fluid.ALL) sum += r.air[tile, f]
        return sum
    }

    // ── It happens at all ────────────────────────────────────────────────────

    @Test
    fun `a lump carrying both reagents makes carbon monoxide out of itself`() {
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 1400)
        sweep(r)

        assertTrue(r.belt[tile, Species.CarbonMonoxide] > 0L, "no carbon monoxide was made")
        assertTrue(r.belt[tile, Species.CarbonDioxide] < 50 * kg, "the CO2 was untouched")
        assertTrue(r.belt[tile, Species.Carbon] < 50 * kg, "the lump's carbon was untouched")
        assertEquals(0L, airTotal(r), "a reaction inside a lump put something into the room")
    }

    @Test
    fun `no carbon on the belt is no reaction`() {
        // ⛔ The reagent is genuinely required, not merely counted. A row that fired anyway would be
        // making carbon monoxide out of carbon dioxide alone, which is an atom short.
        val r = room(dioxide = 50 * kg, carbon = 0L, kelvin = 1400)
        sweep(r)
        assertEquals(50 * kg, r.belt[tile, Species.CarbonDioxide])
        assertEquals(0L, r.belt[tile, Species.CarbonMonoxide])
    }

    @Test
    fun `a cool room does nothing`() {
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 900)
        sweep(r)
        assertEquals(50 * kg, r.belt[tile, Species.CarbonDioxide])
        assertEquals(50 * kg, r.belt[tile, Species.Carbon])
    }

    // ── The ledger ───────────────────────────────────────────────────────────

    @Test
    fun `the lump weighs the same before and after, to the gram`() {
        // ⛔ **What the two crossing tests became.** They asserted that the air gained exactly what
        // the belt lost, and that the carbon took its share of the belt's warmth with it — both
        // statements about a hand-over between two ledgers. There is no hand-over: the whole
        // reaction is inside one layer, so the identity is that the layer's mass does not move at
        // all, and neither does its heat except by the row's own enthalpy.
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 1400)
        val before = beltTotal(r)

        val step = react(r.air, r.energy, null, listOf(r.belt))

        assertTrue(r.belt[tile, Species.CarbonMonoxide] > 0L, "nothing reacted, so this proves nothing")
        assertEquals(before, beltTotal(r), "the lump does not weigh what it did")
        assertEquals(0L, step.toGasMass, "a reaction inside a lump booked a crossing to the air")
        assertEquals(0L, step.toSolidMass, "a reaction inside a lump booked a crossing from the air")
        assertEquals(0L, airTotal(r), "the room gained something from a lump reacting with itself")
    }

    // ── Its own brake ────────────────────────────────────────────────────────

    @Test
    fun `it puts itself out`() {
        // ⚠️ **The property the old test proved in a state the game cannot reach**, re-proved where
        // the reaction actually happens.
        //
        // +172 kJ/mol is endothermic, so every pass takes heat out of the lump it is happening in,
        // and with no fire there is nothing putting any back. It cools under its own 973 K onset and
        // stalls with most of both reagents untouched. A fire is what overrides this — and a fire
        // runs out of oxygen.
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 1400)
        sweep(r, passes = 400)

        val dioxide = r.belt[tile, Species.CarbonDioxide]
        assertTrue(dioxide > 40L * kg, "it consumed more than a brake would allow: $dioxide left")

        // Stopped, not merely slow: another four hundred passes move nothing at all.
        val monoxide = r.belt[tile, Species.CarbonMonoxide]
        sweep(r, passes = 400)
        assertEquals(dioxide, r.belt[tile, Species.CarbonDioxide], "it was still reacting")
        assertEquals(monoxide, r.belt[tile, Species.CarbonMonoxide], "it was still reacting")
    }

    // ── Contention ───────────────────────────────────────────────────────────

    @Test
    fun `a starved reagent bounds it without breaking the balance`() {
        // One gram of carbon against fifty kilos of CO2. What must not happen is the row taking the
        // CO2 it would have needed and giving back only what the carbon could support — running
        // rich, which breaks the atom balance in the direction where it still looks like it works.
        val r = room(dioxide = 50 * kg, carbon = 1L * Budget.GRAM, kelvin = 1400)
        val before = beltTotal(r)
        sweep(r)

        assertTrue(r.belt[tile, Species.Carbon] >= 0L, "the lump went negative")
        assertTrue(r.belt[tile, Species.CarbonMonoxide] > 0L, "nothing reacted, so this proves nothing")
        // 1 CO2 + 1 C -> 2 CO: every gram in is a gram out, and it all stays in the one lump.
        assertEquals(before, beltTotal(r), "running rich or lean would move the lump's mass")
    }
}
