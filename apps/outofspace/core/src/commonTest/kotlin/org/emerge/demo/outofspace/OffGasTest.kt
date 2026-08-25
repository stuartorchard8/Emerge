package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.offGas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Volatiles leaving the matter carrying them — the release half of `offGas`, on its own.
 *
 * `SealedTileGasTest` pins where this may *not* happen and runs through the whole tick to do it.
 * This is the rule itself, against the function, so a change to the saturation condition shows up
 * here as an arithmetic statement rather than three layers away as a room that filled up oddly.
 */
class OffGasTest {

    private val tiles = 16
    private val tile = TileIndex(3)
    private val kg = Budget.KILOGRAM

    /** Nowhere is a wall — the ordinary case, a lump standing in a room. */
    private val inTheOpen: (TileIndex) -> Boolean = { false }

    private fun layerWith(vararg stuff: Pair<Species, Long>, kelvin: Int): StuffLayer {
        val layer = StuffLayer.empty(tiles)
        for ((species, mass) in stuff) layer[tile, species] = mass
        layer.setEnergy(tile, layer.heatCapacityAt(tile) * kelvin)
        return layer
    }

    private fun air() = MassArray(tiles)

    // ── It happens ───────────────────────────────────────────────────────────

    @Test
    fun `a volatile leaves the lump for the room it is standing in`() {
        val layer = layerWith(Species.Iron to 100L * kg, Species.Water to 10L * kg, kelvin = 293)
        val air = air()

        val step = offGas(layer, air, null, inTheOpen)

        assertTrue(air[tile, Fluid.Water] > 0L, "no water reached the room")
        assertTrue(layer[tile, Species.Water] < 10L * kg, "the lump did not give any water up")
        assertEquals(100L * kg, layer[tile, Species.Iron], "the iron went somewhere")
        assertEquals(
            air[tile, Fluid.Water], step.toGasMass,
            "the step does not report what actually crossed",
        )
    }

    @Test
    fun `what the lump loses is exactly what the room gains`() {
        val layer = layerWith(Species.Water to 10L * kg, Species.Ammonia to 4L * kg, kelvin = 293)
        val air = air()

        offGas(layer, air, null, inTheOpen)

        assertEquals(
            10L * kg - layer[tile, Species.Water], air[tile, Fluid.Water],
            "water was invented or lost on the way across",
        )
        assertEquals(
            4L * kg - layer[tile, Species.Ammonia], air[tile, Fluid.Ammonia],
            "ammonia was invented or lost on the way across",
        )
    }

    @Test
    fun `the heat rides across with the mass`() {
        val layer = layerWith(Species.Water to 10L * kg, kelvin = 400)
        val air = air()
        val airEnergy = EnergyArray(tiles)
        val before = layer.energyAt(tile)

        val step = offGas(layer, air, airEnergy, inTheOpen)

        assertTrue(step.toGasMass > 0L, "nothing left, so there is nothing to have carried")
        assertTrue(step.toGasEnergy > 0L, "the vapour left its heat behind in the lump")
        assertEquals(step.toGasEnergy, airEnergy[tile], "the room did not receive what left")
        assertEquals(before - step.toGasEnergy, layer.energyAt(tile), "the lump kept what it gave away")
    }

    // ── And it stops ─────────────────────────────────────────────────────────

    @Test
    fun `it stops at saturation instead of emptying the lump`() {
        // The whole reason there is no rate constant in this function: a species leaves until the
        // room will hold no more of it at that temperature, and then it stops on its own.
        val layer = layerWith(Species.Water to 10L * kg, kelvin = 293)
        val air = air()

        offGas(layer, air, null, inTheOpen)
        val afterFirst = air[tile, Fluid.Water]
        val heldAfterFirst = layer[tile, Species.Water]

        assertTrue(heldAfterFirst > 0L, "the whole 10 kg boiled off — the saturation ceiling did nothing")

        repeat(20) { offGas(layer, air, null, inTheOpen) }

        assertEquals(afterFirst, air[tile, Fluid.Water], "the room kept taking water past saturation")
        assertEquals(heldAfterFirst, layer[tile, Species.Water], "the lump kept shedding past saturation")
    }

    @Test
    fun `a hotter lump sheds more of the same volatile`() {
        // Saturation is a function of temperature and of nothing else, so this is the one dial the
        // player has: warm the ore and more of it comes off.
        fun shed(kelvin: Int): Long {
            val layer = layerWith(Species.Water to 10L * kg, kelvin = kelvin)
            val air = air()
            offGas(layer, air, null, inTheOpen)
            return air[tile, Fluid.Water]
        }

        assertTrue(shed(350) > shed(293), "warming the lump did not free any more water")
    }

    @Test
    fun `a species with no critical point has no liquid phase and leaves in full`() {
        // ⚠️ The largest thing this function does, and it is the equation of state's statement
        // rather than a choice: `CRITICAL` holds five entries, so methane is a gas here at every
        // temperature there is. Ore carrying it is ore carrying gas in a sack.
        val layer = layerWith(Species.Methane to 3L * kg, kelvin = 293)
        val air = air()

        offGas(layer, air, null, inTheOpen)

        assertEquals(0L, layer[tile, Species.Methane], "methane stayed in the lump as though it were rock")
        assertEquals(3L * kg, air[tile, Fluid.Methane], "the methane did not all arrive")
    }

    @Test
    fun `a room already full of the vapour takes none of it`() {
        val layer = layerWith(Species.Water to 10L * kg, kelvin = 293)
        val air = air()
        // Saturate the room first, from a lump that is not this one.
        val primer = layerWith(Species.Water to 10L * kg, kelvin = 293)
        offGas(primer, air, null, inTheOpen)
        val alreadyThere = air[tile, Fluid.Water]

        offGas(layer, air, null, inTheOpen)

        assertEquals(alreadyThere, air[tile, Fluid.Water], "a saturated room took more water anyway")
        assertEquals(10L * kg, layer[tile, Species.Water], "the second lump gave up water into a full room")
    }

    // ── Never into a wall ────────────────────────────────────────────────────

    @Test
    fun `a lump inside something that holds air out keeps everything`() {
        val layer = layerWith(Species.Water to 10L * kg, Species.Methane to 3L * kg, kelvin = 400)
        val air = air()

        val step = offGas(layer, air, null, holdsAirOut = { true })

        assertTrue(step.isNothing, "something crossed out of a tile that has no room in it")
        assertEquals(10L * kg, layer[tile, Species.Water], "water left a sealed tile")
        assertEquals(3L * kg, layer[tile, Species.Methane], "methane left a sealed tile")
        for (f in Fluid.ALL) assertEquals(0L, air[tile, f], "$f appeared inside a wall")
    }
}
