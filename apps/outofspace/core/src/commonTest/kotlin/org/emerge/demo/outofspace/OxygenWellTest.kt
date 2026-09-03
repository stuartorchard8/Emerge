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
 * **One tile's oxygen, and everybody who wants it.**
 *
 * `Reaction.kt` has said this since contention landed:
 *
 * > ⛔ **Never resolve contention by iteration order.** Whoever ran first would get the whole
 * > supply, which is a rule nobody can predict.
 *
 * It was enforced between the rows of a table and violated between the *passes*. `OutofspaceSim` ran
 * `oxidise(rails)`, then `oxidise(hoppers)`, then `combust`, each against the same array and each
 * apportioning whatever the one before had left. Rail matter had first refusal on a tile's oxygen,
 * hoppers second, gas fires last — so a room with a burning belt in it starved its own methane fire,
 * however much methane there was and however hot the room.
 *
 * ⚠️ **The fix arrived twice.** Increment 3 shared the oxygen between three passes with a scale
 * computed ahead of them. Increment 4 deleted the three passes, so there is nothing left to order:
 * `react` states every consumer's demand at a tile, divides every species once, and only then lets
 * anybody take anything. These cases outlived the mechanism they were written for, which is the
 * right way round — they are about the vessel, not about the code.
 */
class OxygenWellTest {

    private val tiles = 4
    private val tile = TileIndex(1)
    private val kg = Budget.KILOGRAM

    private class World(val air: MassArray, val energy: EnergyArray, val layers: List<StuffLayer>)

    private fun world(
        oxygen: Long,
        methane: Long,
        roomKelvin: Int,
        vararg cargo: Pair<Long, Int>,
    ): World {
        val air = MassArray(tiles)
        air.add(tile, Fluid.Oxygen, oxygen)
        if (methane > 0L) air.add(tile, Fluid.Methane, methane)
        val energy = EnergyArray(tiles)
        energy[tile] = heatCapacityAt(air, tile) * roomKelvin

        val layers = cargo.map { (carbon, kelvin) ->
            StuffLayer.empty(tiles).also {
                if (carbon > 0L) {
                    it.add(tile, Species.Carbon, carbon)
                    it.setEnergy(tile, it.heatCapacityAt(tile) * kelvin)
                }
            }
        }
        return World(air, energy, layers)
    }

    private fun sweep(w: World) = react(w.air, w.energy, null, w.layers)

    // ── The bug ──────────────────────────────────────────────────────────────

    @Test
    fun `a belt does not take the oxygen a fire needed`() {
        // A room short of oxygen holding both a burning belt and a methane fire, both well above
        // their onsets. Both must get a share.
        val w = world(oxygen = 2 * kg, methane = 50 * kg, roomKelvin = 1200, 50 * kg to 1200)
        sweep(w)

        assertTrue(w.air[tile, Fluid.Methane] < 50 * kg, "the fire was starved out entirely")
        assertTrue(w.layers[0][tile, Species.Carbon] < 50 * kg, "the belt did not burn at all")
    }

    @Test
    fun `two cargo layers at one tile are peers`() {
        // Rails before hoppers was the other half of the same bug, and the less visible one — both
        // were `oxidise`, so it read as one pass rather than two consumers.
        //
        // Identical contents at identical temperatures ask for identical shares, so they must burn
        // identical amounts. Under pass order the rail burned more, every time.
        val w = world(oxygen = 2 * kg, methane = 0L, roomKelvin = 1200, 50 * kg to 1200, 50 * kg to 1200)
        sweep(w)

        assertEquals(w.layers[0][tile, Species.Carbon], w.layers[1][tile, Species.Carbon])
        assertTrue(w.layers[0][tile, Species.Carbon] < 50 * kg, "neither layer reacted at all")
    }

    @Test
    fun `nobody can draw more oxygen than the tile has`() {
        // ⛔ The property that makes a scale safe in place of an exact apportionment. Each consumer
        // takes `floor(demand x scale)` and the scale is at most `supply / wanted`, so the shares
        // sum to no more than the supply however the rounding falls. Checked against a tile
        // oversubscribed many times over, which is where a scale that rounded the wrong way shows.
        val w = world(oxygen = 3L, methane = 100 * kg, roomKelvin = 1500, 100 * kg to 1500, 100 * kg to 1500)
        sweep(w)
        assertTrue(w.air[tile, Fluid.Oxygen] >= 0L, "the tile went into oxygen debt")
    }

    @Test
    fun `plenty of oxygen starves nobody`() {
        // The common case: nothing is short, so nobody is scaled and everyone reacts at their own
        // unconstrained rate.
        val w = world(oxygen = 500 * kg, methane = 1 * kg, roomKelvin = 1200, 1 * kg to 1200)
        sweep(w)
        assertTrue(w.air[tile, Fluid.Methane] < 1 * kg)
        assertTrue(w.layers[0][tile, Species.Carbon] < 1 * kg)
    }

    @Test
    fun `a tile with no oxygen burns nothing`() {
        val w = world(oxygen = 0L, methane = 50 * kg, roomKelvin = 1200, 50 * kg to 1200)
        sweep(w)
        assertEquals(50 * kg, w.air[tile, Fluid.Methane])
        assertEquals(50 * kg, w.layers[0][tile, Species.Carbon])
    }

    // ── The other half of the snapshot ───────────────────────────────────────

    @Test
    fun `a warmer room does not follow from who reacted first`() {
        // ⚠️ **The well was necessary and not sufficient, and a test found that rather than the
        // reasoning.** With the oxygen shared, two pass orders still disagreed on the CO2 while
        // agreeing on the carbon: `combust` derived the room's temperature from `airEnergy`, and an
        // oxidation had already moved heat into the air by the time it looked. The fire's *rate* was
        // reading a room the belt had warmed.
        //
        // One pass makes that unrepresentable — every rate at a tile is read from one temperature,
        // taken before anything reacts. Stated here as the behaviour: handing the pass the
        // temperature it would have derived anyway changes nothing.
        val a = world(oxygen = 2 * kg, methane = 50 * kg, roomKelvin = 1200, 50 * kg to 1200)
        val b = world(oxygen = 2 * kg, methane = 50 * kg, roomKelvin = 1200, 50 * kg to 1200)

        react(a.air, a.energy, null, a.layers)
        val kelvin = IntArray(tiles) { org.emerge.demo.outofspace.world.gasKelvin(b.energy, b.air)[it] }
        react(b.air, b.energy, kelvin, b.layers)

        assertEquals(a.air[tile, Fluid.Methane], b.air[tile, Fluid.Methane])
        assertEquals(a.layers[0][tile, Species.Carbon], b.layers[0][tile, Species.Carbon])
    }
}
