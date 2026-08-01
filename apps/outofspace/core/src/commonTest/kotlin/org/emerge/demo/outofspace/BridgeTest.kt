package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
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
    private fun crossing(bridged: Boolean = false, horizontalSupply: Resource? = ingots): VesselState {
        val m = arrayOfNulls<Machine>(grid.size)
        val bridges = arrayOfNulls<Bridge>(grid.size)

        // Emptying the horizontal source is how the merge is caught: with nothing of its own to
        // send, anything arriving at its tank must have come off the *other* line.
        m[grid.index(3, 5)] = Storage(Direction.Right, horizontalSupply)   // out at (4, 5)
        m[grid.index(15, 5)] = Storage(Direction.Right)             // in at (14, 5)
        m[grid.index(9, 2)] = Storage(Direction.Down, ingots)       // out at (9, 3)
        m[grid.index(9, 9)] = Storage(Direction.Down)               // in at (9, 8)

        val track = rails(grid) {
            col(9, 3, 8)
            if (bridged) {
                // The horizontal run stops one tile short of the column on each side, and the bridge
                // spans the three tiles between. Its ports are (8, 5) and (10, 5) — where the track
                // is. Those tiles are *adjacent* to the column's (9, 5) and that is now fine: they
                // are not joined to it, because nobody drew a join.
                row(4, 8, 5)
                row(10, 14, 5)
            } else {
                // Drawn straight through the crossing tile, which really does join the two runs.
                row(4, 14, 5)
            }
        }
        if (bridged) bridges[grid.index(9, 5)] = Bridge(Direction.Right)
        return VesselState(grid, m.toList(), rails = track, bridges = bridges.toList())
    }

    // ── The shape of a bridge ─────────────────────────────────────────────────

    @Test
    fun `a bridge has one port at each end and occupies nothing between them`() {
        val at = grid.index(9, 5)
        val ports = portsOf(grid, Bridge(Direction.Right), at)
        assertEquals(2, ports.size)
        assertEquals(grid.index(8, 5), ports.single { it.kind == PortKind.Input }.tile, "in on one side")
        assertEquals(grid.index(10, 5), ports.single { it.kind == PortKind.Output }.tile, "out on the other")

        // The tile it hops is clear, and the crossing line runs through it untouched.
        val s = crossing(bridged = true)
        assertTrue((8..10).all { s.occupancy.isFree(grid.index(it, 5)) }, "it takes up no floor")
        assertNotNull(s.railAt(grid.index(9, 5)), "and the track it spans is untouched")
    }

    @Test
    fun `a bridge turns with its facing`() {
        val at = grid.index(9, 5)
        val ports = portsOf(grid, Bridge(Direction.Down), at)
        assertEquals(grid.index(9, 4), ports.single { it.kind == PortKind.Input }.tile)
        assertEquals(grid.index(9, 6), ports.single { it.kind == PortKind.Output }.tile)
    }

    // ── Crossing ──────────────────────────────────────────────────────────────

    @Test
    fun `drawn straight through, the two lines really are one network`() {
        // What the bridge exists to prevent, and it has to be a real failure or the bridge is
        // solving nothing. Drawing the horizontal line *through* (9, 5) makes a four-way junction,
        // and a junction is a fork: material coming down the column splits, and half of it leaves
        // along a line it was never meant to be on.
        //
        // The test starves the horizontal line of its own supply, so anything reaching its tank can
        // only have come off the vertical one. That is a sharper statement than any total: the two
        // worlds differ in *whose material ends up where*, which is exactly what a crossing is for.
        //
        // Its earlier forms asserted the near tank stole everything and the far one got nothing.
        // Both were artefacts rather than the mechanic — first of consumers being terminal, then of
        // "nearest sink wins" standing in for a fork.
        val merged = run(crossing(horizontalSupply = null), seconds(30))
        assertTrue(
            (merged[grid.index(15, 5)] as Storage).contents?.mass ?: 0L > 0L,
            "the column's material reached the row's tank: the two lines are one network",
        )

        val bridged = run(crossing(bridged = true, horizontalSupply = null), seconds(30))
        assertNull(
            (bridged[grid.index(15, 5)] as Storage).contents,
            "and with a bridge it cannot: the column passes over without joining",
        )
    }

    @Test
    fun `simply running alongside is not a crossing problem in the first place`() {
        // The reason the bridge's ports could move back to its own ends. Under adjacency-joining,
        // track at (8, 5) touching the column at (9, 5) merged the two runs regardless of ports, and
        // a bridge had to hold its connections two tiles out to stay clear. Now touching is nothing.
        val s = crossing(bridged = true)
        assertEquals(
            false,
            s.railAt(grid.index(8, 5))!!.linkedTo(Direction.Right),
            "the horizontal run stops dead at the column rather than joining it",
        )
        assertEquals(
            false,
            s.railAt(grid.index(9, 5))!!.linkedTo(Direction.Left),
            "and the column does not reach back",
        )
    }

    @Test
    fun `a bridge carries one line over the other without them meeting`() {
        // The horizontal line hops the vertical one. Its material has to arrive at the far end
        // having never touched the column, and the column's material has to be unaffected.
        val s = run(crossing(bridged = true), seconds(40))

        assertTrue(
            ((s[grid.index(15, 5)] as Storage).contents?.mass ?: 0L) > 0L,
            "the horizontal line reached its tank",
        )
        assertTrue(
            ((s[grid.index(9, 9)] as Storage).contents?.mass ?: 0L) > 0L,
            "and the vertical one reached its own",
        )
    }

    // ── Three tiles of track, behaving like three tiles of track ──────────────

    /** The bridged crossing with nothing feeding the horizontal run, and one lump placed by hand. */
    private fun withLumpAtTheEntrance(): VesselState {
        val s = crossing(bridged = true, horizontalSupply = null)
        val rails = s.rails.toMutableList()
        val at = grid.index(8, 5)
        rails[at] = rails[at]!!.copy(held = SolidPacket(ingots))
        return s.copy(rails = rails)
    }

    @Test
    fun `a packet crosses a bridge one slot at a time`() {
        // The whole point of three slots: a bridge costs what its three tiles of track would have
        // cost, and the material is somewhere identifiable the whole way over rather than vanishing
        // into the span and reappearing. Off the track at the input end, over the tile being hopped,
        // then down onto the track at the far end.
        val at = grid.index(9, 5)
        var s = withLumpAtTheEntrance()

        s = run(s, Bridge.STEP_TICKS)
        assertEquals(20_000L, s.bridges[at]?.entry?.mass, "lifted off the track at the input end")

        s = run(s, Bridge.STEP_TICKS)
        assertEquals(20_000L, s.bridges[at]?.middle?.mass, "over the tile it hops")
        assertNull(s.bridges[at]?.entry, "and the entrance is free for the next one")

        s = run(s, Bridge.STEP_TICKS)
        assertNull(s.bridges[at]?.exit, "off the far end in the same step it reaches it")
        assertEquals(20_000L, s.railAt(grid.index(10, 5))?.held?.mass, "and onto the track there")
    }

    @Test
    fun `a bridge backs up three deep, like the three tiles it spans`() {
        // With the far tank gone the output run fills and stops taking, and what queues behind it is
        // the whole bridge. One slot meant a bridge could only ever hold one lump, which is the same
        // statement as "it is a bottleneck": the span had a third of the capacity of the track it
        // replaced, so a bridged line ran at a third of the speed of the line either side of it.
        var s = crossing(bridged = true).withMachine(grid.index(15, 5), null)
        s = run(s, Bridge.STEP_TICKS * 20)
        assertEquals(Bridge.SLOTS, s.bridges[grid.index(9, 5)]?.carried?.size, "all three slots loaded")
    }

    @Test
    fun `a bridge takes a packet every step, not one every three`() {
        // The flowing case. The exit slot is empty at the end of most steps because it is put down
        // on the track in the same step it reaches the end -- exactly what the last tile of any run
        // does -- so throughput is what has to be measured, not occupancy.
        val supply = Resource(Form.IronIngot, Mixture.of(Species.Iron to 200_000L))
        var s = crossing(bridged = true, horizontalSupply = supply)
        // Priming: three steps of latency across the span, plus the run either side of it. The
        // window then has to close before the receiving tank fills, or this measures its capacity.
        s = run(s, Bridge.STEP_TICKS * 13)
        val before = (s[grid.index(15, 5)] as Storage).contents?.mass ?: 0L
        val steps = 15
        s = run(s, Bridge.STEP_TICKS * steps)
        val delivered = ((s[grid.index(15, 5)] as Storage).contents?.mass ?: 0L) - before
        assertEquals(steps * 1_000L, delivered, "a packet a step, all the way across")
    }

    // ── The placement rule ────────────────────────────────────────────────────

    @Test
    fun `a bridge cannot be placed so its port lands on another port of the same conduit`() {
        // The tank at (15, 5) has its input port at (14, 5). A bridge at (13, 5) puts its output
        // port there too, and has to be refused: a segment on that tile could not say which of the
        // two it feeds.
        var s = crossing()
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(grid.index(13, 5), MachineKind.Bridge, Direction.Right))))
        assertNull(s.bridges[grid.index(13, 5)], "its output port would collide with the tank's input")
    }

    @Test
    fun `two bridges cannot share an end`() {
        var s = crossing()
        val first = grid.index(9, 5)
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(first, MachineKind.Bridge, Direction.Right))))
        assertNotNull(s.bridges[first], "the first one goes down fine")

        // Two tiles along: its input port would land on the first bridge's output port.
        val second = grid.index(11, 5)
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
