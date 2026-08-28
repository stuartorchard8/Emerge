package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Furnace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

/**
 * The inspector, as the player reaches it: click a tile, click again, see the next layer.
 *
 * A tile is several things at once and the panel shows one of them, so *which* one is the whole of
 * the interaction — and it is the sort of thing that reads fine in code and is unusable in hand. The
 * cases below are the ones that would each have made it so: a layer that vanished when the room
 * emptied, a nine-tile machine whose second square looked like a repeat click, and a cycle that
 * dead-ends instead of wrapping.
 */
class InspectTest {

    private val grid = Grid(9, 9)
    private val cfg = OutofspaceConfig(initialGrid = grid)
    private val centre = grid.tile(4, 4)
    private val room = grid.tile(2, 2)

    private fun world(rails: List<TileIndex> = emptyList()): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }
        deck += Furnace(centre, Direction.Right)
        val layer = arrayOfNulls<Segment>(grid.size)
        for (tile in rails) layer[tile.index] = Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))
        return VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(layer.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            creative = true,
        )
    }

    private fun controller(rails: List<TileIndex> = emptyList()) = OutofspaceController(cfg, world(rails))

    @Test
    fun `the default tool reads the world rather than changing it`() {
        // The tool a player is holding before they have chosen one must not be able to spend
        // anything or take anything down.
        assertEquals(Tool.Inspect, OutofspaceController(cfg, world()).tool)
    }

    @Test
    fun `an empty room still offers its atmosphere`() {
        // ⛔ The case the "layers with content in them" rule would get wrong on its own. "VACUUM" is
        // the most useful thing this panel ever says, and a layer that only appeared once there was
        // gas to describe would disappear at exactly the moment a breach made it worth reading.
        val c = controller()
        assertEquals(listOf(InspectLayer.Atmosphere), inspectableLayers(c.state, room))
    }

    @Test
    fun `a building is what a click on it means`() {
        val c = controller()
        c.apply(centre)
        assertEquals(InspectLayer.Deck, c.inspectLayer, "clicking a furnace did not read the furnace")
    }

    @Test
    fun `clicking again steps to the next layer and wraps`() {
        // A furnace tile is the building and the air in it, so the cycle is two long — and a cycle
        // that runs off its end rather than wrapping strands the player on the last layer.
        val c = controller()
        val layers = inspectableLayers(c.state, centre)
        assertTrue(layers.size >= 2, "the fixture has nothing to cycle through")

        c.apply(centre)
        val first = c.inspectLayer
        c.apply(centre)
        assertTrue(c.inspectLayer != first, "a repeat click did not change layer")
        repeat(layers.size - 1) { c.apply(centre) }
        assertEquals(first, c.inspectLayer, "the cycle did not wrap round to where it started")
    }

    @Test
    fun `a second square of the same machine is a new tile, not a repeat click`() {
        // ⛔ **The bug this exists for.** Selection resolves through occupancy, so every tile of a
        // nine-tile furnace answers with the same centre. Cycling off *that* would read a click on
        // the machine's corner as a second click on its middle and step the layer, so the player
        // would never see the DECK layer twice in a row while working across one building.
        val c = controller()
        val corner = grid.tile(grid.xOf(centre) + 1, grid.yOf(centre) + 1)
        assertEquals(c.state.occupancy[centre], c.state.occupancy[corner], "the fixture is not one machine")

        c.apply(centre)
        assertEquals(InspectLayer.Deck, c.inspectLayer)
        c.apply(corner)
        assertEquals(InspectLayer.Deck, c.inspectLayer, "moving along the same machine stepped the layer")
    }

    @Test
    fun `track under a building is reachable without taking the building off`() {
        // The reason the cycle exists at all: a belt threaded under a machine used to be visible
        // only by deleting what was standing on it.
        val c = controller(rails = listOf(centre))
        val layers = inspectableLayers(c.state, centre)
        assertTrue(InspectLayer.Deck in layers && InspectLayer.Rail in layers, "got $layers")

        c.apply(centre)
        c.apply(centre)
        assertEquals(InspectLayer.Rail, c.inspectLayer, "the second click did not reach the track")
    }

    @Test
    fun `a layer that stops existing falls back to the top rather than to nothing`() {
        // The rail is pinned, then taken up. Nothing should be able to leave the inspector reading a
        // layer the tile no longer has.
        val c = controller(rails = listOf(room))
        c.inspect(room, InspectLayer.Rail)
        assertEquals(InspectLayer.Rail, c.inspectLayer)

        c.removeAt(room, DeleteLayer.Rail)
        repeat(2) { c.stepOnce() }
        assertTrue(InspectLayer.Rail !in inspectableLayers(c.state, room), "the fixture never removed the rail")

        c.inspect(room)
        assertEquals(InspectLayer.Atmosphere, c.inspectLayer, "a stale layer did not fall back")
    }

    @Test
    fun `pointing at nothing leaves nothing pinned`() {
        val c = controller()
        c.apply(centre)
        c.inspect(TileIndex.NONE)
        assertEquals(TileIndex.NONE, c.inspectTile)
    }
}
