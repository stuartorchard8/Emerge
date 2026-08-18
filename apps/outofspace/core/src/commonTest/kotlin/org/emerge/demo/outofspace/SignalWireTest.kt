package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Increment A of `PLAN_signal_network.md`: wire you can lay, that connects to nothing.
 *
 * There is deliberately no signal semantics here at all. What is being pinned is that the signal
 * layer behaves like the two conduit layers that already work — it joins by explicit link rather
 * than by adjacency, it survives a save, and it is a *fitting*, so it shares its tile and displaces
 * neither air nor floor. Every one of those is a property the rail layer had to learn the hard way.
 */
class SignalWireTest {

    private val grid = Grid(12, 8)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    /** A straight run, one step at a time, the way the controller emits a drag. */
    private fun drag(state: VesselState, y: Int, fromX: Int, toX: Int): VesselState {
        var s = state
        for (x in fromX until toX) {
            s = edit(s, Edit.Lay(grid.tile(x, y), grid.tile(x + 1, y), Conduit.Signal))
        }
        return s
    }

    private fun wireAt(s: VesselState, x: Int, y: Int) = s.conduits.at(Conduit.Signal, grid.tile(x, y))

    // ── Laying ────────────────────────────────────────────────────────────────

    @Test
    fun `a dragged run joins tile to tile`() {
        val s = drag(VesselState.empty(grid), y = 3, fromX = 2, toX = 5)

        for (x in 2..5) assertNotNull(wireAt(s, x, 3), "no wire at x=$x")
        assertTrue(wireAt(s, 2, 3)!!.linkedTo(Direction.Right), "the first tile should join rightward")
        assertTrue(wireAt(s, 5, 3)!!.linkedTo(Direction.Left), "and the last leftward")
        assertFalse(wireAt(s, 5, 3)!!.linkedTo(Direction.Right), "but not off the end of the drag")
    }

    /**
     * The rail rule, and the one most likely to be got wrong: two runs laid alongside each other are
     * two runs. Links are explicit, so touching is not joining — otherwise a player could never lay
     * two independent circuits down one corridor.
     */
    @Test
    fun `two runs that merely touch are not joined`() {
        var s = drag(VesselState.empty(grid), y = 3, fromX = 2, toX = 4)
        s = drag(s, y = 4, fromX = 2, toX = 4)

        for (x in 2..4) {
            assertFalse(wireAt(s, x, 3)!!.linkedTo(Direction.Down), "x=$x joined downward without being dragged")
            assertFalse(wireAt(s, x, 4)!!.linkedTo(Direction.Up), "x=$x joined upward without being dragged")
        }
    }

    @Test
    fun `a single tile can be placed on its own`() {
        val s = edit(VesselState.empty(grid), Edit.Place(grid.tile(6, 2), Brush.Run(Conduit.Signal), Direction.Right))
        val stub = wireAt(s, 6, 2)

        assertNotNull(stub, "placing the wire brush on one tile should leave a stub")
        assertTrue(stub.isIsolated, "and a stub joins nothing")
        assertEquals(Conduit.Signal, stub.conduit)
    }

    @Test
    fun `wire comes off again`() {
        var s = drag(VesselState.empty(grid), y = 3, fromX = 2, toX = 4)
        s = edit(s, Edit.Remove(grid.tile(3, 3), DeleteLayer.Top))

        assertNull(wireAt(s, 3, 3), "the wire should be gone")
        assertFalse(
            wireAt(s, 2, 3)!!.linkedTo(Direction.Right),
            "and its neighbour should not still be joined to where it was",
        )
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Test
    fun `save and load round-trips a laid network, links included`() {
        val s = drag(VesselState.empty(grid), y = 3, fromX = 2, toX = 5)
        val reloaded = Save.read(Save.write(s))

        for (x in 2..5) {
            assertEquals(
                s.conduits.at(Conduit.Signal, grid.tile(x, 3)),
                reloaded.conduits.at(Conduit.Signal, grid.tile(x, 3)),
                "wire at x=$x did not survive the trip",
            )
        }
    }

    // ── A fitting, not a building ─────────────────────────────────────────────

    /**
     * Compared against the same world built without the wire rather than against remembered figures,
     * so this stays true when the fluid solver is next tuned. A wire that displaced air would show up
     * as a room with less in it; a wire the flood fill believed in would show up as a changed
     * structure map.
     */
    @Test
    fun `wire displaces nothing`() {
        fun room(): VesselState {
            val deck = DeckArray(grid)
            for (x in 1 until grid.width - 1) {
                deck += Hull(grid.tile(x, 1))
                deck += Hull(grid.tile(x, grid.height - 2))
            }
            for (y in 2 until grid.height - 2) {
                deck += Hull(grid.tile(1, y))
                deck += Hull(grid.tile(grid.width - 2, y))
            }
            return VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
        }

        var wired = room()
        wired = drag(wired, y = 3, fromX = 2, toX = 8)
        var bare = room()

        repeat(20) {
            wired = OutofspaceReducer.reduce(cfg, wired, emptyMap())
            bare = OutofspaceReducer.reduce(cfg, bare, emptyMap())
        }

        assertEquals(bare.structure, wired.structure, "wire changed what the hull encloses")
        assertEquals(bare.atmosphereMass, wired.atmosphereMass, "wire took up room in the air")
    }
}
