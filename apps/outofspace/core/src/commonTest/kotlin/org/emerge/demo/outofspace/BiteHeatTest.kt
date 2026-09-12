package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.temperatureKelvin
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A rock's heat rides into the store with the rock's matter**, and the casing gets only the work.
 *
 * Those are two different numbers and they used to be one place: a bite charged the cell's whole
 * thermal energy to the machine's own casing and handed the store a mixture stating zero energy.
 * Both ledgers closed — the energy was still in the world and [VesselState.acquiredEnergy] still
 * recorded the crossing — so the only way to see it was to ask *where it went*, which nothing did.
 *
 * ⚠️ **An assertion on temperature alone is not enough in either direction**, and that is why both
 * halves are here. The store and the casing share a tile, so they conduct into each other within a
 * few ticks; measure late and a wrong answer has had time to become a plausible one. Measure only
 * the store and a casing cooking itself to its melting point goes unnoticed.
 */
class BiteHeatTest {

    init { RockSpawner.enabled = false }

    /** Hot enough that a misrouted figure is unmistakable, and below any melting point involved. */
    private val hot = 1_500

    private val grid = Grid(12, 5)
    private val cfg = OutofspaceConfig(initialGrid = grid)
    private val at = grid.tile(2, 2)

    /** An extractor with a [hot] rock of its own size sitting on the plate, and nowhere to ship to. */
    private fun plateWithHotRock(): VesselState {
        val deck = DeckArray(grid)
        deck += Extractor(at, Direction.Right)
        val rock = RigidBody.rockBlob(
            radius = FEEDSTOCK_RADIUS,
            positionX = 2 * Flight.PER_TILE + Flight.PER_TILE / 2L,
            positionY = 2 * Flight.PER_TILE + Flight.PER_TILE / 2L,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
            kelvin = hot,
        )
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(List(grid.size) { null }),
            bodies = listOf(rock),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).gridAtWorldOrigin()
    }

    private fun step(s: VesselState): VesselState =
        OutofspaceReducer.reduce(cfg, s, mapOf(PlayerId(0) to OutofspaceInput.EMPTY))

    /** Where the ore lands — a store sits on a tile of the footprint, not on the machine's centre. */
    private fun storeTile(s: VesselState) = bufferTile(s.grid, s.deck[at]!!, at, BufferRole.Product)!!

    /**
     * ⚠️ **One kelvin low, and the kelvin is not this change's.** A body's capacity comes from
     * [org.emerge.demo.outofspace.world.capacityPerTileOf] — a *mean* specific heat against a
     * per-tile density — and a store's comes from
     * [org.emerge.demo.outofspace.world.StuffLayer.thermalMassAt], the exact `Σ mass × specificHeat`.
     * For the default orebody those differ by 1.26e-6 (200,770,621 against 200,770,873 per tile),
     * and that sliver plus a truncation reads one kelvin down at every temperature: 100 K seeds as
     * 99 K, 1500 K as 1499 K, 3000 K as 2999 K.
     *
     * The *energy* crosses exactly — `a bite moves energy without minting or losing any` is the
     * assertion that matters, and it is an equality. This is the two sides disagreeing about how to
     * read one figure back, it predates the bite carrying its heat at all, and
     * `heatCapacityOf`'s own KDoc says which of the two is honest: the store's. So the bound is
     * stated as one kelvin **downward** rather than as a tolerance — a gap that grew, or changed
     * sign, would be a real regression and this still catches it.
     */
    @Test
    fun `ore arrives in the hopper at the temperature of the rock it came off`() {
        val s = step(plateWithHotRock())
        val store = storeTile(s)

        assertTrue(s.extractedMass > 0L, "this proves nothing unless a bite was taken")
        val kelvin = s.buffers.stuff.kelvinAt(store)
        assertTrue(
            kelvin in hot - 1..hot,
            "ore off a ${hot}K rock should reach the hopper at ${hot}K, give or take the " +
                "capacity-route kelvin; it was ${kelvin}K",
        )
    }

    @Test
    fun `the casing takes the work and not the rock's heat`() {
        var s = plateWithHotRock()
        val before = s.deck[at]!!.temperatureKelvin(s.grid, s.deck.stuff)
        assertEquals(Temperature.AMBIENT_KELVIN, before, "the plate starts at room temperature")

        // Two bites, which is what the first version of this put at 706K and then 1119K.
        s = step(step(s))
        val after = s.deck[at]!!.temperatureKelvin(s.grid, s.deck.stuff)

        assertTrue(
            after > before,
            "the machine should still be warming itself by working: ${before}K -> ${after}K",
        )
        // The work heat is 2000 mJ/g against a rock cell's ~3.6 tonnes, which is tens of kelvin; the
        // rock's own heat would be hundreds. Stated as a wide bound on purpose — the figure this
        // guards against is four times the far side of it, and `heatPerGram` is a tuning dial.
        assertTrue(
            after < 500,
            "but the rock's heat must not be charged to the casing as well: ${after}K",
        )
    }

    @Test
    fun `a bite moves energy without minting or losing any`() {
        var s = plateWithHotRock()
        val rock = s.bodies.single()
        val perCell = rock.energy.total / rock.filled
        s = step(s)

        // The crossing is booked — the grid holds energy that was outside it a tick ago — and booked
        // at exactly what left the rock, no more. `acquiredEnergy` is what keeps
        // [VesselState.baselineEnergy]'s balance closed over a bite; it has to be the same figure
        // whichever store the heat landed in, which is the half of this that did not change.
        assertEquals(
            perCell, s.acquiredEnergy,
            "one bite should acquire exactly one cell's worth of heat",
        )
        assertEquals(
            perCell, s.buffers.stuff.energyAt(storeTile(s)),
            "and all of it should be in the store, since the ore is the only thing that moved",
        )
        assertEquals(
            hot, s.bodies.single().kelvin,
            "the rock keeps its temperature as it shrinks — it lost matter, not warmth",
        )
    }
}
