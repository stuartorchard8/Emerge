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
 * **A reagent in each store** — the case that proves increment 4 of `PLAN_unified_reactions.md`.
 *
 * `CO₂ + C → 2 CO`. The carbon dioxide is in the room's air and the carbon is on a belt, and until
 * now there was no shape in the game that could say that. Every earlier table draws its reagents
 * from **one** store: [org.emerge.demo.outofspace.chem.Reduction] takes two solids out of one cargo
 * layer, which is where this row lived — so it asked for carbon dioxide *as cargo* at 973 K, and CO₂
 * is evicted from a cargo layer above its critical point of **304 K**.
 *
 * ⚠️ **It had a green test, and the test was the problem.** `ReductionSweepTest` proved the row was
 * its own thermal brake by putting CO₂ straight into a cargo layer at 1400 K — a state no vessel can
 * reach, because `offGas` empties it eleven hundred kelvin earlier. A real behaviour, correctly
 * measured, in a configuration the simulation cannot produce. `Combustion.kt` meanwhile credits this
 * row with quietly filling the vessel's rooms with carbon monoxide since `14306ded`. It never has.
 *
 * ### What is different about it
 *
 * It **crosses a ledger**, and not as a special case: the carbon leaves the cargo identity and comes
 * back as part of the CO in the air identity, purely because that is where its reagents were. Every
 * other crossing in the game is owned by a pass built for crossing — `offGas` one way, `oxidise`'s
 * oxygen the other. This one falls out of the placement rule.
 */
class BoudouardTest {

    private val tiles = 4
    private val tile = TileIndex(1)
    private val kg = Budget.KILOGRAM

    private class Room(val air: MassArray, val energy: EnergyArray, val belt: StuffLayer)

    /** Carbon dioxide in the room, carbon on the belt, both at [kelvin]. */
    private fun room(dioxide: Long, carbon: Long, kelvin: Int): Room {
        val air = MassArray(tiles)
        if (dioxide > 0L) air.add(tile, Fluid.CarbonDioxide, dioxide)
        val energy = EnergyArray(tiles)
        energy[tile] = heatCapacityAt(air, tile) * kelvin

        val belt = StuffLayer.empty(tiles)
        if (carbon > 0L) {
            belt.add(tile, Species.Carbon, carbon)
            belt.setEnergy(tile, belt.heatCapacityAt(tile) * kelvin)
        }
        return Room(air, energy, belt)
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
    fun `gas in the room reacts with carbon on the belt`() {
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 1400)
        sweep(r)

        assertTrue(r.air[tile, Fluid.CarbonMonoxide] > 0L, "no carbon monoxide was made")
        assertTrue(r.air[tile, Fluid.CarbonDioxide] < 50 * kg, "the CO2 was untouched")
        assertTrue(r.belt[tile, Species.Carbon] < 50 * kg, "the belt's carbon was untouched")
    }

    @Test
    fun `no carbon on the belt is no reaction`() {
        // ⛔ The reagent is genuinely required, not merely counted. A row that fired anyway would be
        // making carbon monoxide out of carbon dioxide alone, which is an atom short.
        val r = room(dioxide = 50 * kg, carbon = 0L, kelvin = 1400)
        sweep(r)
        assertEquals(50 * kg, r.air[tile, Fluid.CarbonDioxide])
        assertEquals(0L, r.air[tile, Fluid.CarbonMonoxide])
    }

    @Test
    fun `a cool room does nothing`() {
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 900)
        sweep(r)
        assertEquals(50 * kg, r.air[tile, Fluid.CarbonDioxide])
        assertEquals(50 * kg, r.belt[tile, Species.Carbon])
    }

    // ── The ledger ───────────────────────────────────────────────────────────

    @Test
    fun `the carbon that left the belt is in the air, to the gram`() {
        // ⛔ The crossing, stated as an identity. The air gained exactly what the belt lost — the
        // mass did not come from nowhere and none of it fell down the gap between two ledgers.
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 1400)
        val airBefore = airTotal(r)
        val beltBefore = r.belt[tile, Species.Carbon]

        val step = react(r.air, r.energy, null, listOf(r.belt))

        val leftTheBelt = beltBefore - r.belt[tile, Species.Carbon]
        assertTrue(leftTheBelt > 0L, "nothing left the belt")
        assertEquals(leftTheBelt, airTotal(r) - airBefore, "the air did not gain what the belt lost")
        // And the pass says so, which is what closes `solidBecameGas` against `gasBecameSolid`.
        assertEquals(leftTheBelt, step.toGasMass)
    }

    @Test
    fun `the heat rides across with the matter`() {
        // A cold belt shedding matter into a hot room must not warm the room for free, nor cool
        // itself for free — the carbon takes its share of the belt's warmth with it. `offGas`'s
        // rule, applied to a reaction that moves mass the same way.
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 1400)
        val beltEnergyBefore = r.belt.energyAt(tile)
        val step = react(r.air, r.energy, null, listOf(r.belt))
        assertTrue(step.toGasEnergy > 0L, "the carbon crossed without its heat")
        assertEquals(beltEnergyBefore - step.toGasEnergy, r.belt.energyAt(tile))
    }

    // ── Its own brake ────────────────────────────────────────────────────────

    @Test
    fun `it puts itself out`() {
        // ⚠️ **The property the old test proved in a state the game cannot reach**, re-proved where
        // the reaction actually happens.
        //
        // +172 kJ/mol is endothermic, so every pass takes heat out of the room it is happening in,
        // and with no fire there is nothing putting any back. It cools under its own 973 K onset and
        // stalls with most of both reagents untouched. A fire is what overrides this — and a fire
        // runs out of oxygen.
        val r = room(dioxide = 50 * kg, carbon = 50 * kg, kelvin = 1400)
        sweep(r, passes = 400)

        val dioxide = r.air[tile, Fluid.CarbonDioxide]
        assertTrue(dioxide > 40L * kg, "it consumed more than a brake would allow: $dioxide left")

        // Stopped, not merely slow: another four hundred passes move nothing at all.
        val monoxide = r.air[tile, Fluid.CarbonMonoxide]
        sweep(r, passes = 400)
        assertEquals(dioxide, r.air[tile, Fluid.CarbonDioxide], "it was still reacting")
        assertEquals(monoxide, r.air[tile, Fluid.CarbonMonoxide], "it was still reacting")
    }

    // ── Contention ───────────────────────────────────────────────────────────

    @Test
    fun `a starved reagent bounds it without breaking the balance`() {
        // One gram of carbon against fifty kilos of CO2. What must not happen is the row taking the
        // CO2 it would have needed and giving back only what the carbon could support — running
        // rich, which breaks the atom balance in the direction where it still looks like it works.
        val r = room(dioxide = 50 * kg, carbon = 1L * Budget.GRAM, kelvin = 1400)
        val airBefore = airTotal(r)
        sweep(r)

        assertTrue(r.belt[tile, Species.Carbon] >= 0L, "the belt went negative")
        // 1 CO2 + 1 C -> 2 CO: every gram in is a gram out, whichever store it came from.
        val leftTheBelt = 1L * Budget.GRAM - r.belt[tile, Species.Carbon]
        assertEquals(leftTheBelt, airTotal(r) - airBefore)
    }
}
