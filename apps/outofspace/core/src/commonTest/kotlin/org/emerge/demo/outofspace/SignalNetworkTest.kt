package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.SignalNetworks
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Increment B of `PLAN_signal_network.md`: the components of the wire layer.
 *
 * Nothing reads these yet. What is being pinned is the answer to "who is connected to whom", which
 * is the question the whole feature rests on — after this lands, a transmitter and a receiver on one
 * run share a number, and that number is the entire replacement for a channel.
 */
class SignalNetworkTest {

    private val grid = Grid(12, 8)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun empty(): VesselState = VesselState(grid, List(grid.size) { null })

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    private fun drag(state: VesselState, y: Int, fromX: Int, toX: Int): VesselState {
        var s = state
        for (x in fromX until toX) {
            s = edit(s, Edit.Lay(grid.tile(x, y), grid.tile(x + 1, y), Conduit.Signal))
        }
        return s
    }

    private fun networks(s: VesselState) = SignalNetworks.derive(grid, s.conduits)

    // ── Components ────────────────────────────────────────────────────────────

    @Test
    fun `bare wire is one network per run`() {
        var s = drag(empty(), y = 2, fromX = 1, toX = 4)
        s = drag(s, y = 6, fromX = 1, toX = 4)
        val n = networks(s)

        assertEquals(2, n.count)
        assertNotEquals(n[grid.tile(1, 2)], n[grid.tile(1, 6)], "two separate runs shared a network")
        for (x in 1..4) {
            assertEquals(n[grid.tile(1, 2)], n[grid.tile(x, 2)], "the top run broke at x=$x")
        }
    }

    /**
     * The case most likely to be got wrong, and the reason components run over links rather than
     * adjacency: two circuits down one corridor must stay two circuits.
     */
    @Test
    fun `touching is not joining`() {
        var s = drag(empty(), y = 2, fromX = 1, toX = 4)
        s = drag(s, y = 3, fromX = 1, toX = 4)
        val n = networks(s)

        assertEquals(2, n.count, "runs on adjacent rows fused without being joined")
    }

    @Test
    fun `joining two runs makes one network`() {
        var s = drag(empty(), y = 2, fromX = 1, toX = 4)
        s = drag(s, y = 4, fromX = 1, toX = 4)
        assertEquals(2, networks(s).count)

        // The link that makes them one circuit, laid down the gap between them.
        s = edit(s, Edit.Lay(grid.tile(1, 2), grid.tile(1, 3), Conduit.Signal))
        s = edit(s, Edit.Lay(grid.tile(1, 3), grid.tile(1, 4), Conduit.Signal))

        val n = networks(s)
        assertEquals(1, n.count)
        assertEquals(n[grid.tile(4, 2)], n[grid.tile(4, 4)], "the far ends should now agree")
    }

    @Test
    fun `cutting a run makes two networks`() {
        val s = drag(empty(), y = 2, fromX = 1, toX = 6)
        assertEquals(1, networks(s).count)

        val cut = edit(s, Edit.Cut(grid.tile(3, 2), grid.tile(4, 2), Conduit.Signal))
        val n = networks(cut)

        assertEquals(2, n.count)
        assertNotEquals(n[grid.tile(3, 2)], n[grid.tile(4, 2)], "the two halves should have parted")
    }

    @Test
    fun `an isolated tile is a network of one`() {
        val s = edit(empty(), Edit.Place(grid.tile(5, 5), MachineKind.Wire, org.emerge.demo.outofspace.world.Direction.Right))
        val n = networks(s)

        assertEquals(1, n.count)
        assertTrue(n[grid.tile(5, 5)] >= 0, "a stub is still a circuit, just a very short one")
    }

    @Test
    fun `a tile with no wire is on no network`() {
        val s = drag(empty(), y = 2, fromX = 1, toX = 4)
        assertEquals(-1, networks(s)[grid.tile(7, 7)])
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Ids are assigned by lowest tile index, derived here from the fixture's own geometry rather than
     * pinned — the run that starts nearer the top-left is network 0 whatever order the sweep visits
     * them in.
     */
    @Test
    fun `networks are numbered by their lowest tile`() {
        // Laid bottom-first, so discovery order and index order disagree if anything gets this wrong.
        var s = drag(empty(), y = 6, fromX = 1, toX = 4)
        s = drag(s, y = 2, fromX = 1, toX = 4)
        val n = networks(s)

        val upper = grid.tile(1, 2)
        val lower = grid.tile(1, 6)
        assertTrue(upper.index < lower.index, "fixture assumption: the upper run holds the lower tile index")
        assertEquals(0, n[upper])
        assertEquals(1, n[lower])
    }

    @Test
    fun `ids survive a save and load`() {
        var s = drag(empty(), y = 6, fromX = 1, toX = 4)
        s = drag(s, y = 2, fromX = 1, toX = 4)

        val before = networks(s)
        val after = networks(Save.read(Save.write(s)))

        assertEquals(before.count, after.count)
        for (tile in grid.tiles) {
            assertEquals(before[tile], after[tile], "tile $tile changed network across a save")
        }
    }
}
