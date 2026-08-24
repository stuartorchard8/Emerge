package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A locked warehouse: the first sink whose appetite is **endless and picky at once**.
 *
 * Every appetite before this one answered both questions the same way — a machine took anything for
 * ever, a construction site took one recipe until it was finished — so the network could read one
 * flag for both and did. A lock separates them, and these tests are what say so: the first two are
 * the feature, the third is the flag that quietly meant the wrong thing until it was split (see
 * [org.emerge.demo.outofspace.world.Acceptance.takesAnything]), and it is the one that would go
 * green again on its own if the two were ever conflated back together.
 */
class StorageFilterTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private val load = 6L * Capacity.PACKET_MASS

    /**
     * 98% titanium: ore, not a bar.
     *
     * Chosen to sit **between** two of the panel's steps, so the threshold tests have something to
     * bite on — it clears 90 comfortably and misses 99, and a filter that ignored its own number
     * could not tell those two apart.
     */
    private fun titanium(mass: Long) = Mixture.of(
        Species.Titanium to mass - mass / 50L,
        Species.Iron to mass / 50L,
        energy = 0,
    )

    /**
     * A full tank at (2,3) pouring right into a second tank at (10,3), which may be locked.
     *
     * Two warehouses and not a warehouse and a vent, because the question is what a *sink* refuses:
     * a vent takes anything and would answer nothing.
     */
    private fun tankToTank(filter: SpeciesFilter?): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(2, 3), Direction.Right)
        deck += Storage(grid.tile(10, 3), Direction.Right, filter = filter)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 9, 3)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(2, 3), titanium(load))
    }

    private fun VesselState.received(): Long = inStore(grid.tile(10, 3), BufferRole.Inside)?.total ?: 0L

    private fun VesselState.onTrack(): Long {
        var total = 0L
        for (i in 0 until grid.size) total += rail.massAt(TileIndex(i))
        return total
    }

    @Test
    fun `a warehouse locked to something else is never sent anything`() {
        val s = run(tankToTank(SpeciesFilter(Species.Iron, 90)), 40 * RAIL_PERIOD)

        assertEquals(0L, s.received(), "titanium got into a warehouse locked to iron")
        // ⛔ **And the run stays clear.** Refusing at the door alone would let the source pour
        // anyway and leave the line packed solid against a tank that will never take it — the dead
        // end failure, with the lock as the dead end. The whitelist is what makes the tank's answer
        // visible eight tiles upstream.
        assertEquals(0L, s.onTrack(), "the run filled up against a lock that will never open")
        assertEquals(load, s.inStore(s.grid.tile(2, 3), BufferRole.Inside)?.total, "and the source kept it all")
    }

    @Test
    fun `a warehouse locked to what is coming fills up as it always did`() {
        val s = run(tankToTank(SpeciesFilter(Species.Titanium, 90)), 40 * RAIL_PERIOD)
        assertTrue(s.received() > 0L, "a warehouse locked to titanium refused titanium")
    }

    /**
     * ⚠️ The threshold is the player's number and is read as written: 98% ore clears 90 and misses
     * 99. If a filter were ever routed
     * through `buildableFrom` this reads 99% *of* the build tolerance instead, which is 94%, and
     * the ore sails in while the panel says 99.
     */
    @Test
    fun `the threshold is the number the player set`() {
        val ore = titanium(load)
        assertTrue(SpeciesFilter(Species.Titanium, 90).admits(ore))
        assertTrue(!SpeciesFilter(Species.Titanium, 99).admits(ore))
        assertTrue(!SpeciesFilter(Species.Titanium, 90).admits(Mixture.EMPTY), "nothing is not a delivery")
    }

    /** A lock is the player's decision, so it survives the file. */
    @Test
    fun `a lock survives a save and a load`() {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(2, 3), Direction.Right, filter = SpeciesFilter(Species.Titanium, 70))
        val state = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        val back = Save.read(Save.write(state))
        assertEquals(
            SpeciesFilter(Species.Titanium, 70),
            (back.deck[grid.tile(2, 3)] as Storage).filter,
        )
    }
}
