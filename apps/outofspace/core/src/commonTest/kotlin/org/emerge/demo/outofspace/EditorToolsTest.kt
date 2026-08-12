package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Direction
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
    private val OPEN_TILE get() = grid.index(4, 7)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** A room with a rail and a pipe threaded through it, and a tank standing on one of the tiles. */
    private fun layered(): OutofspaceController {
        val machines = arrayOfNulls<org.emerge.demo.outofspace.world.Machine>(grid.size)
        for (x in 2..10) { machines[grid.index(x, 2)] = Hull(); machines[grid.index(x, 8)] = Hull() }
        for (y in 2..8) { machines[grid.index(2, y)] = Hull(); machines[grid.index(10, y)] = Hull() }
        machines[grid.index(6, 5)] = Storage(Direction.Right)
        val c = OutofspaceController(cfg, VesselState(grid, machines.toList()))
        c.brush = MachineKind.Rail
        c.dragTo(grid.index(5, 5))
        c.apply(grid.index(4, 5))
        c.dragTo(grid.index(7, 5))
        c.brush = MachineKind.Pipe
        c.apply(grid.index(4, 5))
        c.dragTo(grid.index(7, 5))
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
        val at = grid.index(6, 5)
        assertNotNull(c.state.conduits[Conduit.Rail][at], "the fixture built no rail")
        assertNotNull(c.state.conduits[Conduit.Pipe][at], "the fixture built no pipe")
        assertTrue(c.state[at] is Storage, "the fixture built no tank")

        c.remove(at, DeleteLayer.Pipe)
        c.stepOnce()

        assertNull(c.state.conduits[Conduit.Pipe][at], "the pipe survived being named")
        assertNotNull(c.state.conduits[Conduit.Rail][at], "the rail came off with the pipe")
        assertTrue(c.state[at] is Storage, "the tank came off with the pipe")
    }

    /** The deck can be reached through what is threaded over it, which TOP could never do in one go. */
    @Test
    fun `DECK takes the building out from under its fittings`() {
        val c = layered()
        val at = grid.index(6, 5)

        c.remove(at, DeleteLayer.Deck)
        c.stepOnce()

        assertNull(c.state[at], "the tank is still there")
        assertNotNull(c.state.conduits[Conduit.Rail][at], "the rail went with it")
        assertNotNull(c.state.conduits[Conduit.Pipe][at], "the pipe went with it")
    }

    @Test
    fun `ALL clears the tile in one click`() {
        val c = layered()
        val at = grid.index(6, 5)

        c.remove(at, DeleteLayer.All)
        c.stepOnce()

        assertNull(c.state[at])
        assertNull(c.state.conduits[Conduit.Rail][at])
        assertNull(c.state.conduits[Conduit.Pipe][at])
    }

    /**
     * TOP is what every caller that predates the tool meant, and it still means it: one layer per
     * click, topmost first. A default that quietly became "all of it" would demolish a smelter the
     * first time somebody tried to lift the track off it.
     */
    @Test
    fun `TOP still peels one layer at a time`() {
        val c = layered()
        val at = grid.index(6, 5)

        c.remove(at, DeleteLayer.Top)
        c.stepOnce()
        assertNull(c.state.conduits[Conduit.Rail][at], "rail is the first conduit layer, so it goes first")
        assertNotNull(c.state.conduits[Conduit.Pipe][at], "two layers came off in one click")
        assertTrue(c.state[at] is Storage)

        c.remove(at, DeleteLayer.Top)
        c.stepOnce()
        assertNull(c.state.conduits[Conduit.Pipe][at])
        assertTrue(c.state[at] is Storage, "the tank came off before its fittings had")

        c.remove(at, DeleteLayer.Top)
        c.stepOnce()
        assertNull(c.state[at])
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
        val before = c.state.atmosphereGrams

        repeat(10) {
            c.injectTile = at
            c.stepOnce()
        }
        c.injectTile = -1
        c.stepOnce()

        val s = c.state
        assertEquals(10L * Edit.INJECT_GRAMS, s.injectedAirGrams, "ten ticks is ten kilograms")
        assertTrue(s.atmosphereGrams > before, "the room did not actually get any heavier")
        assertEquals(0L, s.airBalance, "the air ledger broke")
        EnergyLedgers.assertAirBalanced(s, "the air's energy ledger broke")
    }

    /**
     * The gas arrives at room temperature, not at absolute zero.
     *
     * [org.emerge.demo.outofspace.world.AirField.of]'s rule, and the one mistake this is most likely
     * to make: energy derived from the grams rather than defaulted, or the room chills every time
     * somebody uses the tool.
     */
    @Test
    fun `injected gas arrives at room temperature`() {
        val c = layered()
        val at = OPEN_TILE
        val before = c.state.airKelvinAt(at)

        repeat(5) { c.injectTile = at; c.stepOnce() }
        c.injectTile = -1
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
        val at = grid.index(6, 5)   // the tank, which is impermeable
        val before = c.state.atmosphereGrams

        repeat(5) { c.injectTile = at; c.stepOnce() }
        c.injectTile = -1
        c.stepOnce()

        val s = c.state
        assertEquals(0L, s.injectedAirGrams, "a refused breath was booked anyway")
        assertEquals(before, s.atmosphereGrams, "gas got into a solid tank")
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
        assertEquals(3L * Edit.INJECT_GRAMS, c.state.injectedAirGrams)
    }

    /** The admission has to survive a save, or a reloaded world reads its own history as a leak. */
    @Test
    fun `the admission survives a round trip`() {
        val c = layered()
        repeat(4) { c.injectTile = OPEN_TILE; c.stepOnce() }
        c.injectTile = -1
        c.stepOnce()

        val written = Save.write(c.state)
        val back = Save.read(written)

        assertEquals(c.state.injectedAirGrams, back.injectedAirGrams)
        assertEquals(c.state.injectedAirJoules, back.injectedAirJoules)
        assertEquals(0L, back.airBalance, "the reloaded world reads its own past as a leak")
    }
}
