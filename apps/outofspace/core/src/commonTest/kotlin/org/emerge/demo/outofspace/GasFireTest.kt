package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.react
import org.emerge.demo.outofspace.world.heatCapacityAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A room full of fuel and air, and a spark.**
 *
 * Everything the vessel could burn until now had to be a *solid on a belt*. `oxidise` takes a
 * [org.emerge.demo.outofspace.world.StuffLayer] and reads the air only for its oxygen, so a room
 * could be filled with methane and oxygen at a thousand kelvin and nothing whatever would happen.
 *
 * That was tolerable while gaseous fuel was a curiosity. `offGas` made it the ordinary case: ammonia,
 * methane and carbon monoxide now pour out of every ore lump the vessel carries, and on a live save
 * they are a quarter of the atmosphere. A ship you cannot set fire to is the wrong ship.
 *
 * ### The shape, and why it is a fourth one
 *
 * Both reagents come out of the same field and every product goes back into it. Nothing crosses
 * between the cargo ledger and the air ledger, which is what makes this different in kind from
 * [org.emerge.demo.outofspace.chem.Oxidation] rather than a row of it — and it is why the only
 * number a pass of it reports is the energy it made.
 */
class GasFireTest {

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

    // ── It happens ───────────────────────────────────────────────────────────

    @Test
    fun `methane burns in a hot room`() {
        // CH4 + 2 O2 -> CO2 + 2 H2O. Well above methane's autoignition point, and with oxygen to
        // spare so nothing here is about contention.
        val (air, energy) = room(Fluid.Methane to 1L * kg, Fluid.Oxygen to 10L * kg, kelvin = 1200)

        react(air, energy)

        assertTrue(air[tile, Fluid.Methane] < 1L * kg, "the methane did not burn")
        assertTrue(air[tile, Fluid.Oxygen] < 10L * kg, "no oxygen was consumed")
        assertTrue(air[tile, Fluid.CarbonDioxide] > 0L, "no carbon dioxide was made")
        assertTrue(air[tile, Fluid.Water] > 0L, "no water was made")
    }

    @Test
    fun `hydrogen burns too, and makes only water`() {
        val (air, energy) = room(Fluid.Hydrogen to 1L * kg, Fluid.Oxygen to 10L * kg, kelvin = 1200)

        react(air, energy)

        assertTrue(air[tile, Fluid.Hydrogen] < 1L * kg, "the hydrogen did not burn")
        assertTrue(air[tile, Fluid.Water] > 0L, "no water was made")
        assertEquals(0L, air[tile, Fluid.CarbonDioxide], "hydrogen has no carbon in it")
    }

    // ── And it does not, when it should not ──────────────────────────────────

    @Test
    fun `without oxygen nothing burns`() {
        // The property that makes a sealed, inerted room a strategy rather than a special case, and
        // the same one `oxidise` already has for solids.
        val (air, energy) = room(Fluid.Methane to 1L * kg, kelvin = 1200)

        react(air, energy)

        assertEquals(1L * kg, air[tile, Fluid.Methane], "methane burned with nothing to burn in")
        assertEquals(0L, air[tile, Fluid.CarbonDioxide], "something was made out of nothing")
    }

    @Test
    fun `below its ignition point nothing burns`() {
        // Methane and oxygen sitting together at room temperature is what a fuel tank *is*.
        val (air, energy) = room(Fluid.Methane to 1L * kg, Fluid.Oxygen to 10L * kg, kelvin = 293)

        react(air, energy)

        assertEquals(1L * kg, air[tile, Fluid.Methane], "a fuel tank at room temperature went off")
    }

    // ── Conservation ─────────────────────────────────────────────────────────

    @Test
    fun `the fire is exactly as heavy as what went into it`() {
        // Nothing crosses a ledger here: the tile's gas is rearranged and that is all. So the
        // strongest statement available is also the simplest one, and it has to hold to the unit.
        val (air, energy) = room(Fluid.Methane to 1L * kg, Fluid.Oxygen to 10L * kg, kelvin = 1200)
        val before = total(air)

        repeat(20) { react(air, energy) }

        assertEquals(before, total(air), "the room does not weigh what it did before it caught fire")
        assertTrue(air[tile, Fluid.CarbonDioxide] > 0L, "nothing burned, so nothing was proved")
    }

    @Test
    fun `every carbon atom that went in comes out in the carbon dioxide`() {
        val (air, energy) = room(Fluid.Methane to 1L * kg, Fluid.Oxygen to 10L * kg, kelvin = 1200)
        val methaneBefore = air[tile, Fluid.Methane]

        repeat(20) { react(air, energy) }

        val burned = methaneBefore - air[tile, Fluid.Methane]
        // CH4 is 12/16 carbon by mass; CO2 is 12/44. Same atoms, different company.
        val carbonIn = burned * 12 / 16
        val carbonOut = air[tile, Fluid.CarbonDioxide] * 12 / 44
        assertTrue(
            kotlin.math.abs(carbonIn - carbonOut) < burned / 1000,
            "carbon went missing: $carbonIn in against $carbonOut out",
        )
    }

    // ── It is a fire, so it is hot ───────────────────────────────────────────

    @Test
    fun `a fire warms the room it is in`() {
        val (air, energy) = room(Fluid.Methane to 1L * kg, Fluid.Oxygen to 10L * kg, kelvin = 1200)
        val before = energy[tile]

        val step = react(air, energy)

        // Positive is released — see [ChemistryStep.releasedEnergy], whose prose said the opposite
        // until this test disagreed with it. `reactionEnergy` adds this into `generatedEnergy`,
        // which is a source term, so a fire making energy has to come out positive.
        assertTrue(step.releasedEnergy > 0L, "burning is exothermic; the step reported ${step.releasedEnergy}")
        assertTrue(energy[tile] > before, "the room did not get hotter")
    }

    // ── And it is actually wired up ──────────────────────────────────────────

    @Test
    fun `a hot fuel-air room catches fire through the real tick`() {
        // ⚠️ Everything above tests the function. This tests that anything *calls* it. That is not
        // paranoia: the mineral vaporizer went its entire life without telling either mass ledger
        // what it was doing, and no test was pointed at the machine rather than at the arithmetic.
        val grid = Grid(8, 6)
        val cfg = OutofspaceConfig(initialGrid = grid)
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0)); deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y)); deck += Hull(grid.tile(grid.width - 1, y))
        }

        val masses = MassArray(grid.size)
        val energies = EnergyArray(grid.size)
        for (y in 1 until grid.height - 1) for (x in 1 until grid.width - 1) {
            val t = grid.tile(x, y)
            masses.add(t, Fluid.Methane, 200L * Budget.GRAM)
            masses.add(t, Fluid.Oxygen, 2L * kg)
            energies[t] = heatCapacityAt(masses, t) * 1200
        }

        var s = VesselState(
            grid, deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            air = Stuff.from(masses, energies),
            creative = true,
        )
        val middle = grid.tile(4, 3)
        val before = s.air.massOf(middle, Fluid.Methane)
        assertTrue(before > 0L, "the fixture put no methane in the room")

        repeat(OutofspaceReducer.CHEM_PERIOD * 8) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        assertTrue(
            s.air.massOf(middle, Fluid.Methane) < before,
            "a sealed room of methane and oxygen at 1200 K did not catch: the tick never burns anything",
        )
        assertTrue(s.air.massOf(middle, Fluid.CarbonDioxide) > 0L, "nothing was made")
    }

    @Test
    fun `two fuels share one room's oxygen rather than the first one taking it`() {
        // The Jacobi rule again. Starved of oxygen, both fuels must get some — a pass that reacted
        // each in turn against a dwindling supply would hand the lot to whichever came first in the
        // table, which is a rule no player can predict.
        val (air, energy) = room(
            Fluid.Methane to 5L * kg,
            Fluid.Hydrogen to 5L * kg,
            Fluid.Oxygen to 1L * kg,
            kelvin = 1200,
        )

        react(air, energy)

        assertTrue(air[tile, Fluid.Methane] < 5L * kg, "the methane got none of the oxygen")
        assertTrue(air[tile, Fluid.Hydrogen] < 5L * kg, "the hydrogen got none of the oxygen")
        assertTrue(air[tile, Fluid.Oxygen] >= 0L, "the oxygen went negative — it was oversubscribed")
    }
}
