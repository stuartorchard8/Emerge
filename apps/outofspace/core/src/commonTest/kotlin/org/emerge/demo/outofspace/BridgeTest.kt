package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bridges: how two runs of the same conduit cross.
 *
 * The thing being tested is mostly an *absence*. A bridge occupies nothing on any layer, so the run
 * passing beneath it is unconnected for the ordinary reason — the two share no port — and there is
 * no crossing logic anywhere in the network code to get wrong. What is left to check is that the
 * absence really is an absence, and that the one rule constraining placement holds.
 */
class BridgeTest {

    private val grid = Grid(20, 12)

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val cfg = OutofspaceConfig(grid = state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private val ingots = Resource(Form.IronIngot, Mixture.of(Species.Iron to 20_000L))

    /**
     * Two lines that want the same tile.
     *
     * The horizontal line runs left to right along row 5, from a full tank to an empty one. The
     * vertical line runs top to bottom down column 9. They meet at (9, 5).
     */
    private fun crossing(bridged: Boolean = false): VesselState {
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val bridges = arrayOfNulls<Bridge>(grid.size)

        m[grid.index(3, 5)] = Storage(Direction.Right, ingots)      // out at (4, 5)
        m[grid.index(15, 5)] = Storage(Direction.Right)             // in at (14, 5)
        m[grid.index(9, 2)] = Storage(Direction.Down, ingots)       // out at (9, 3)
        m[grid.index(9, 9)] = Storage(Direction.Down)               // in at (9, 8)
        for (y in 3..8) rails[grid.index(9, y)] = Segment(Conduit.Rail)

        if (bridged) {
            // The horizontal run stops two tiles short of the column on each side, and the bridge
            // spans the three-tile gap. Its ports are on (7, 5) and (11, 5) — the tiles where the
            // track actually is. Nothing of the horizontal line comes within two tiles of (9, 5),
            // which is what keeps the two runs from merging by adjacency.
            for (x in 4..7) rails[grid.index(x, 5)] = Segment(Conduit.Rail)
            for (x in 11..14) rails[grid.index(x, 5)] = Segment(Conduit.Rail)
            bridges[grid.index(9, 5)] = Bridge(Direction.Right)
        } else {
            for (x in 4..14) rails[grid.index(x, 5)] = Segment(Conduit.Rail)
        }
        return VesselState(grid, m.toList(), rails = rails.toList(), bridges = bridges.toList())
    }

    // ── The shape of a bridge ─────────────────────────────────────────────────

    @Test
    fun `a bridge has one port at each end and occupies nothing between them`() {
        val at = grid.index(9, 5)
        val ports = portsOf(grid, Bridge(Direction.Right), at)
        assertEquals(2, ports.size)
        assertEquals(grid.index(7, 5), ports.single { it.kind == PortKind.Input }.tile, "in on one side")
        assertEquals(grid.index(11, 5), ports.single { it.kind == PortKind.Output }.tile, "out on the other")

        // The span between them is clear, and the crossing line runs through it untouched.
        val s = crossing(bridged = true)
        assertTrue((8..10).all { s.occupancy.isFree(grid.index(it, 5)) }, "it takes up no floor")
        assertNotNull(s.railAt(grid.index(9, 5)), "and the track it spans is untouched")
        assertNull(s.railAt(grid.index(8, 5)), "with nothing of its own line inside the span")
    }

    @Test
    fun `a bridge turns with its facing`() {
        val at = grid.index(9, 5)
        val ports = portsOf(grid, Bridge(Direction.Down), at)
        assertEquals(grid.index(9, 3), ports.single { it.kind == PortKind.Input }.tile)
        assertEquals(grid.index(9, 7), ports.single { it.kind == PortKind.Output }.tile)
    }

    // ── Crossing ──────────────────────────────────────────────────────────────

    @Test
    fun `without a bridge the two lines are one network and material takes the wrong turn`() {
        // Worth asserting, because it is what the bridge exists to prevent: the crossing tile is
        // shared, so the two runs are joined and the horizontal line bleeds into the vertical one.
        val s = run(crossing(), 60 * 30)
        val downstream = (s[grid.index(9, 9)] as Storage).contents?.mass ?: 0L
        val across = (s[grid.index(15, 5)] as Storage).contents?.mass ?: 0L
        assertTrue(downstream > 0L && across > 0L, "both tanks fill: the lines are merged")
    }

    @Test
    fun `a bridge carries one line over the other without them meeting`() {
        // The horizontal line hops the vertical one. Its material has to arrive at the far end
        // having never touched the column, and the column's material has to be unaffected.
        val s = run(crossing(bridged = true), 60 * 40)

        assertTrue(
            ((s[grid.index(15, 5)] as Storage).contents?.mass ?: 0L) > 0L,
            "the horizontal line reached its tank",
        )
        assertTrue(
            ((s[grid.index(9, 9)] as Storage).contents?.mass ?: 0L) > 0L,
            "and the vertical one reached its own",
        )
    }

    @Test
    fun `a bridge holds one packet and passes it on`() {
        var s = crossing(bridged = true)
        // Long enough for the first lump to be picked up, short enough that it is still in there.
        s = run(s, Bridge.STEP_TICKS * 2)
        val carried = (0 until grid.size).sumOf { s.bridges[it]?.held?.mass ?: 0L }
        assertTrue(carried <= 1_000L, "one packet at a time, never more: ${carried}g")
    }

    // ── The placement rule ────────────────────────────────────────────────────

    @Test
    fun `a bridge cannot be placed so its port lands on another port of the same conduit`() {
        // The tank at (15, 5) has its input port at (14, 5). A bridge whose output would land there
        // has to be refused: a segment on that tile could not say which of the two it feeds.
        var s = crossing()
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(grid.index(12, 5), MachineKind.Bridge, Direction.Right))))
        assertNull(s.bridges[grid.index(12, 5)], "its output port would collide with the tank's input")
    }

    @Test
    fun `two bridges cannot share an end`() {
        var s = crossing()
        val first = grid.index(9, 5)
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(first, MachineKind.Bridge, Direction.Right))))
        assertNotNull(s.bridges[first], "the first one goes down fine")

        // Four tiles along: its input port would land on the first bridge's output port.
        val second = grid.index(13, 5)
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(second, MachineKind.Bridge, Direction.Right))))
        assertNull(s.bridges[second], "ends may not overlap")
    }

    @Test
    fun `a bridge may be placed where only its span overlaps another`() {
        var s = crossing()
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(grid.index(9, 5), MachineKind.Bridge, Direction.Right))))
        // Perpendicular, crossing the first bridge's middle. Nothing of either is at that tile, so
        // there is nothing to clash with — which is the whole point of occupying no space.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(grid.index(9, 5), MachineKind.Bridge, Direction.Down))))
        assertEquals(Direction.Right, s.bridges[grid.index(9, 5)]?.facing, "the first is still there")
    }

    // ── Conservation ──────────────────────────────────────────────────────────

    @Test
    fun `material inside a bridge is still aboard, and comes back out if it is removed`() {
        val at = grid.index(9, 5)
        var s = crossing(bridged = true)
        s = run(s, Bridge.STEP_TICKS * 4)
        val before = s.inTransitGrams

        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(at))))
        assertNull(s.bridges[at], "the bridge is gone")
        assertEquals(before, s.inTransitGrams, "and whatever was inside it fell on the deck")
    }

    @Test
    fun `a crossing world conserves and replays identically`() {
        fun digest(): String {
            var s = crossing(bridged = true)
            val cfg = OutofspaceConfig(grid = grid)
            repeat(600) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
            return buildString {
                append(s.inTransitGrams).append('|').append(s.ventedGrams).append('|').append(s.diverters)
                for (r in s.rails) append(r?.held?.mass ?: 0L).append(',')
            }
        }
        assertEquals(digest(), digest())
    }
}
