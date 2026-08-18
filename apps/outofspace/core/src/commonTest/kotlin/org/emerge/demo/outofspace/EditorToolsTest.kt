package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Machine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The aimed delete and the debug bellows — the editor tools.
 *
 * Both are player conveniences and neither is allowed to be a hole in a ledger. Deleting has never
 * been one, because what a machine held falls on the floor rather than vanishing; injecting is one
 * by construction, so what is checked here is that it *admits* to being one.
 */
class EditorToolsTest {

    private val grid = Grid(24, 16)

    /**
     * A tile inside the hull with nothing standing on it.
     *
     * Named, and chosen away from the tank, because the tank is **three tiles across**: its anchor is
     * at (6, 5) and it covers (5..7, 4..6), so the obvious "one below the tank" is inside it. That
     * mistake reads as the bellows being broken and is the fixture being wrong.
     */
    private val OPEN_TILE get() = grid.tile(4, 7)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /**
     * A room with a rail and a wire threaded through it, and a tank standing on one of the tiles.
     *
     * A **wire** as the second fitting rather than a pipe, because track and plumbing compete for
     * the floor and can no longer share a tile — see `Conduits.checkExclusion`. Wires still ride
     * under anything, so rail + wire + tank is what "a tile is not one thing" looks like now, and it
     * is still three layers deep, which is what these tests need.
     */
    private fun layered(): OutofspaceController {
        val machines = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid)
        for (x in 2..10) { deck += Hull(grid.tile(x, 2)); deck += Hull(grid.tile(x, 8)) }
        for (y in 3..7) { deck += Hull(grid.tile(2, y)); deck += Hull(grid.tile(10, y)) }
        deck += Storage(grid.tile(6, 5), Direction.Right)
        val c = OutofspaceController(cfg, VesselState(grid, machines.toList(), deck, buffers = BufferLayer.forMachines(grid, machines.toList()), rail = RailLayer.empty(grid.size)))
        c.brush = MachineKind.Rail
        c.dragTo(grid.tile(5, 5))
        c.apply(grid.tile(4, 5))
        c.dragTo(grid.tile(7, 5))
        c.brush = MachineKind.Wire
        c.apply(grid.tile(4, 5))
        c.dragTo(grid.tile(7, 5))
        c.stepOnce()
        return c
    }

    /**
     * The reason the tool exists: a tile is not one thing, and until now the only way to reach the
     * track under a tank was to click repeatedly and hope.
     */
    @Test
    fun `a named layer comes off and leaves the others standing`() {
        val c = layered()
        val tile = grid.tile(6, 5)
        assertNotNull(c.state.conduits[Conduit.Rail][tile.index], "the fixture built no rail")
        assertNotNull(c.state.conduits[Conduit.Signal][tile.index], "the fixture built no wire")
        assertTrue(c.state.deck[tile] as? Storage != null, "the fixture built no tank")

        c.removeAt(tile, DeleteLayer.Rail)
        c.stepOnce()

        assertNull(c.state.conduits[Conduit.Rail][tile.index], "the rail survived being named")
        assertNotNull(c.state.conduits[Conduit.Signal][tile.index], "the wire came off with the rail")
        assertTrue(c.state.deck[tile] as? Storage != null, "the tank came off with the rail")
    }

    /**
     * The wire is the layer that most needs naming, and until it had a [DeleteLayer] the only way to
     * reach one under a belt was to peel the belt off first and put it back.
     *
     * The rail is the assertion that matters: TOP would have taken it, because rail is the first
     * conduit entry and a wire is the last.
     */
    @Test
    fun `WIRE reaches the signal layer under the belt on top of it`() {
        val c = layered()
        val tile = grid.tile(6, 5)
        assertNotNull(c.state.conduits[Conduit.Signal][tile.index], "the fixture built no wire")

        c.removeAt(tile, DeleteLayer.Wire)
        c.stepOnce()

        assertNull(c.state.conduits[Conduit.Signal][tile.index], "the wire survived being named")
        assertNotNull(c.state.conduits[Conduit.Rail][tile.index], "the rail came off with the wire")
        assertTrue(c.state.deck[tile] as? Storage != null, "the tank came off with the wire")
    }

    /** The deck can be reached through what is threaded over it, which TOP could never do in one go. */
    @Test
    fun `DECK takes the building out from under its fittings`() {
        val c = layered()
        val at = grid.tile(6, 5)

        c.removeAt(at, DeleteLayer.Deck)
        c.stepOnce()

        assertNull(c.state.deck[at], "the tank is still there")
        assertNotNull(c.state.conduits[Conduit.Rail][at.index], "the rail went with it")
        assertNotNull(c.state.conduits[Conduit.Signal][at.index], "the wire went with it")
    }

    @Test
    fun `ALL clears the tile in one click`() {
        val c = layered()
        val at = grid.tile(6, 5)

        c.removeAt(at, DeleteLayer.All)
        c.stepOnce()

        assertNull(c.state.deck[at])
        assertNull(c.state.conduits[Conduit.Rail][at.index])
        assertNull(c.state.conduits[Conduit.Signal][at.index])
    }

    /**
     * TOP is what every caller that predates the tool meant, and it still means it: one layer per
     * click, topmost first. A default that quietly became "all of it" would demolish a smelter the
     * first time somebody tried to lift the track off it.
     */
    @Test
    fun `TOP still peels one layer at a time`() {
        val c = layered()
        val tile = grid.tile(6, 5)

        c.removeAt(tile, DeleteLayer.Top)
        c.stepOnce()
        assertNull(c.state.conduits[Conduit.Rail][tile.index], "rail is the first conduit layer, so it goes first")
        assertNotNull(c.state.conduits[Conduit.Signal][tile.index], "two layers came off in one click")
        assertTrue(c.state.deck[tile] is Storage)

        c.removeAt(tile, DeleteLayer.Top)
        c.stepOnce()
        assertNull(c.state.conduits[Conduit.Signal][tile.index])
        assertTrue(c.state.deck[tile] is Storage, "the tank came off before its fittings had")

        c.removeAt(tile, DeleteLayer.Top)
        c.stepOnce()
        assertNull(c.state.deck[tile])
    }

    // ── the bellows ────────────────────────────────────────────────────────────────

    /**
     * The whole point of the ledger term: gas from nowhere makes `atmosphere + vented == baseline`
     * false forever, and an instrument that reads LEAK whatever happens is one nobody looks at again.
     * So the identity gains a term and stays exactly as strict.
     */
    @Test
    fun `injected gas is admitted, so the air ledger stays balanced`() {
        val c = layered()
        val at = OPEN_TILE
        val before = c.state.atmosphereMass

        repeat(10) {
            c.injectTile = at
            c.stepOnce()
        }
        c.injectTile = TileIndex.NONE
        c.stepOnce()

        val s = c.state
        assertEquals(10L * Edit.INJECT_MASS, s.injectedAirMass, "ten ticks is ten kilograms")
        assertTrue(s.atmosphereMass > before, "the room did not actually get any heavier")
        assertEquals(0L, s.airBalance, "the air ledger broke")
        EnergyLedgers.assertAirBalanced(s, "the air's energy ledger broke")
    }

    /**
     * The gas arrives at room temperature, not at absolute zero.
     *
     * [org.emerge.demo.outofspace.world.Stuff.gas]'s rule, and the one mistake this is most likely
     * to make: energy derived from the mass rather than defaulted, or the room chills every time
     * somebody uses the tool.
     */
    @Test
    fun `injected gas arrives at room temperature`() {
        val c = layered()
        val at = OPEN_TILE
        val before = c.state.airKelvinAt(at)

        repeat(5) { c.injectTile = at; c.stepOnce() }
        c.injectTile = TileIndex.NONE
        c.stepOnce()

        val after = c.state.airKelvinAt(at)
        assertTrue(
            after in (before - 15)..(before + 15),
            "the room went from ${before}K to ${after}K — the parcel arrived at the wrong temperature",
        )
    }

    /** A wall has no gas volume, so filling one is refused outright rather than booked and lost. */
    @Test
    fun `injecting into a solid machine does nothing at all`() {
        val c = layered()
        val at = grid.tile(6, 5)   // the tank, which is impermeable
        val before = c.state.atmosphereMass

        repeat(5) { c.injectTile = at; c.stepOnce() }
        c.injectTile = TileIndex.NONE
        c.stepOnce()

        val s = c.state
        assertEquals(0L, s.injectedAirMass, "a refused breath was booked anyway")
        assertEquals(before, s.atmosphereMass, "gas got into a solid tank")
        assertEquals(0L, s.airBalance)
    }

    /**
     * A rate, not a frame count.
     *
     * The controller turns held state into one edit a tick for exactly [Edit.Thrust]'s reason: an
     * edit per *event* gives one tick's worth per press, and an edit per *frame* makes a fast machine
     * a faster bellows.
     */
    @Test
    fun `a held bellows delivers once a tick, however many frames pass`() {
        val c = layered()
        c.injectTile = OPEN_TILE
        repeat(3) { c.stepOnce() }
        assertEquals(3L * Edit.INJECT_MASS, c.state.injectedAirMass)
    }

    /** The admission has to survive a save, or a reloaded world reads its own history as a leak. */
    @Test
    fun `the admission survives a round trip`() {
        val c = layered()
        repeat(4) { c.injectTile = OPEN_TILE; c.stepOnce() }
        c.injectTile = TileIndex.NONE
        c.stepOnce()

        val written = Save.write(c.state)
        val back = Save.read(written)

        assertEquals(c.state.injectedAirMass, back.injectedAirMass)
        assertEquals(c.state.injectedAirEnergy, back.injectedAirEnergy)
        assertEquals(0L, back.airBalance, "the reloaded world reads its own past as a leak")
    }
}
