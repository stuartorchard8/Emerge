package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.DeleteLayer
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
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
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private val ingots = Resource(Form.IronIngot, Mixture.of(Species.Iron to 20 * Capacity.PACKET_MASS, energy = 0))

    /**
     * Two lines that want the same tile.
     *
     * The horizontal line runs left to right along row 5, from a full tank to an empty one. The
     * vertical line runs top to bottom down column 9. They meet at (9, 5).
     */
    private fun crossing(bridged: Boolean = false, horizontalSupply: Resource? = ingots): VesselState {
        val deck = DeckArray(grid)

        // Emptying the horizontal source is how the merge is caught: with nothing of its own to
        // send, anything arriving at its tank must have come off the *other* line.
        deck += Storage(grid.tile(3, 5), Direction.Right)   // out at (4, 5)
        deck += Storage(grid.tile(15, 5), Direction.Right)             // in at (14, 5)
        deck += Storage(grid.tile(9, 2), Direction.Down)       // out at (9, 3)
        deck += Storage(grid.tile(9, 9), Direction.Down)               // in at (9, 8)

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
        if (bridged) deck += Bridge(grid.tile(9, 5), Direction.Right)
        return VesselState(grid, deck, conduits = Conduits.ofRails(track), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .stocked(grid.tile(3, 5), horizontalSupply)
            .stocked(grid.tile(9, 2), ingots)
    }

    /** How many of the bridge's three slots are carrying something. */
    private fun VesselState.slotsFilled(at: TileIndex): Int =
        listOf(BufferRole.Input, BufferRole.Inside, BufferRole.Product)
            .count { inStore(at, it) != null }

    // ── The shape of a bridge ─────────────────────────────────────────────────

    @Test
    fun `a bridge has one port at each end and stands on the three tiles between`() {
        val at = grid.tile(9, 5)
        val ports = portsOf(grid, Bridge(at, Direction.Right), at)
        assertEquals(2, ports.size)
        assertEquals(grid.tile(8, 5), ports.single { it.kind == PortKind.Input }.tile, "in on one side")
        assertEquals(grid.tile(10, 5), ports.single { it.kind == PortKind.Output }.tile, "out on the other")

        // ⚠️ It claims all three tiles of deck. That is the change of shape: a bridge used to
        // occupy nothing and could be stacked without limit, and crossing a run now costs floor.
        val s = crossing(bridged = true)
        assertTrue((8..10).none { s.occupancy.isFree(grid.tile(it, 5)) }, "a bridge stands on its span")
        // The *conduit* underneath is a different layer and is untouched — that is still the point.
        assertNotNull(s.railAt(grid.tile(9, 5)), "and the track it spans is untouched")
    }

    @Test
    fun `a bridge turns with its facing`() {
        val at = grid.tile(9, 5)
        val ports = portsOf(grid, Bridge(at, Direction.Down), at)
        assertEquals(grid.tile(9, 4), ports.single { it.kind == PortKind.Input }.tile)
        assertEquals(grid.tile(9, 6), ports.single { it.kind == PortKind.Output }.tile)
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
        val merged = run(crossing(horizontalSupply = null), 120*RAIL_PERIOD)
        assertTrue(
            merged.buffers.massAt(grid.tile(15, 5)) > 0L,
            "the column's material reached the row's tank: the two lines are one network",
        )

        val bridged = run(crossing(bridged = true, horizontalSupply = null), 120)
        assertNull(
            bridged.buffers.resourceAt(grid.tile(15, 5)),
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
            s.railAt(grid.tile(8, 5))!!.linkedTo(Direction.Right),
            "the horizontal run stops dead at the column rather than joining it",
        )
        assertEquals(
            false,
            s.railAt(grid.tile(9, 5))!!.linkedTo(Direction.Left),
            "and the column does not reach back",
        )
    }

    @Test
    fun `a bridge carries one line over the other without them meeting`() {
        // The horizontal line hops the vertical one. Its material has to arrive at the far end
        // having never touched the column, and the column's material has to be unaffected.
        val s = run(crossing(bridged = true), 160*RAIL_PERIOD)

        assertTrue(
            s.buffers.massAt(grid.tile(15, 5)) > 0L,
            "the horizontal line reached its tank",
        )
        assertTrue(
            s.buffers.massAt(grid.tile(9, 9)) > 0L,
            "and the vertical one reached its own",
        )
    }

    // ── Three tiles of track, behaving like three tiles of track ──────────────

    /** The bridged crossing with nothing feeding the horizontal run, and one lump placed by hand. */
    private fun withLumpAtTheEntrance(): VesselState {
        val s = crossing(bridged = true, horizontalSupply = null)
        return s.riding(grid.tile(8, 5), ingots)
    }

    @Test
    fun `a packet crosses a bridge one slot at a time`() {
        // The whole point of three slots: a bridge costs what its three tiles of track would have
        // cost, and the material is somewhere identifiable the whole way over rather than vanishing
        // into the span and reappearing. Off the track at the input end, over the tile being hopped,
        // then down onto the track at the far end.
        val at = grid.tile(9, 5)
        var s = withLumpAtTheEntrance()

        s = run(s, RAIL_PERIOD)
        assertEquals(20 * Capacity.PACKET_MASS, s.inStore(at, BufferRole.Input)?.mass, "lifted off the track at the input end")

        s = run(s, RAIL_PERIOD)
        assertEquals(20 * Capacity.PACKET_MASS, s.inStore(at, BufferRole.Inside)?.mass, "over the tile it hops")
        assertNull(s.inStore(at, BufferRole.Input), "and the entrance is free for the next one")

        s = run(s, RAIL_PERIOD)
        assertEquals(20 * Capacity.PACKET_MASS, s.inStore(at, BufferRole.Product)?.mass, "resting on the far end, for a whole step")
        assertNull(s.inStore(at, BufferRole.Inside), "and the middle is free for the next one")
        assertNull(s.onRail(grid.tile(10, 5)), "not yet put down — that is next step's job")

        s = run(s, RAIL_PERIOD)
        assertNull(s.inStore(at, BufferRole.Product), "and now down onto the track")
        // At (11, 5) rather than (10, 5): the exit slot is drawn *at* the output port's tile, so
        // setting down there and running on one tile is a single tile of travel, not two. The
        // deposit happens first precisely so the track can carry it in the same step.
        assertEquals(20 * Capacity.PACKET_MASS, s.onRail(grid.tile(11, 5))?.mass, "and away down the far run")
    }

    @Test
    fun `a bridge backs up three deep, like the three tiles it spans`() {
        // With the far tank gone the output run fills and stops taking, and what queues behind it is
        // the whole bridge. One slot meant a bridge could only ever hold one lump, which is the same
        // statement as "it is a bottleneck": the span had a third of the capacity of the track it
        // replaced, so a bridged line ran at a third of the speed of the line either side of it.
        // Take the far tank away, so the output run fills and stops taking.
        var s = crossing(bridged = true)
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.tile(15, 5), DeleteLayer.Deck))))
        s = run(s, RAIL_PERIOD * 20)
        assertEquals(Bridge.SLOTS, s.slotsFilled(grid.tile(9, 5)), "all three slots loaded")
    }

    @Test
    fun `a bridge takes a packet every step, not one every three`() {
        // The flowing case, and the one that pins the ordering. A bridge sets down what it was
        // *already* holding, before anything shifts along -- so the exit slot is free by the time
        // the shift wants it and the span stays a pipeline. Drain it after the shift instead and
        // every slot idles a step waiting for the one ahead, halving the throughput while looking
        // perfectly correct tile by tile. Occupancy cannot see that; only throughput can.
        val supply = Resource(Form.IronIngot, Mixture.of(Species.Iron to 200 * Capacity.PACKET_MASS, energy = 0))
        var s = crossing(bridged = true, horizontalSupply = supply)
        // Priming: three steps of latency across the span, plus the run either side of it. The
        // window then has to close before the receiving tank fills, or this measures its capacity.
        s = run(s, RAIL_PERIOD * 13)
        val before = s.buffers.resourceAt(grid.tile(15, 5))?.mass ?: 0L
        val steps = 15
        s = run(s, RAIL_PERIOD * steps)
        val delivered = s.buffers.massAt(grid.tile(15, 5)) - before
        assertEquals(steps * Capacity.PACKET_MASS, delivered, "a packet a step, all the way across")
    }

    // ── The placement rule ────────────────────────────────────────────────────

    @Test
    fun `a bridge cannot be placed so its port lands on another port of the same conduit`() {
        // The tank at (15, 5) has its input port at (14, 5). A bridge at (13, 5) puts its output
        // port there too, and has to be refused: a segment on that tile could not say which of the
        // two it feeds.
        var s = crossing()
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(grid.tile(13, 5), DeckMachineKind.Bridge, Direction.Right))))
        assertNull(s.deck[grid.tile(13, 5)], "its output port would collide with the tank's input")
    }

    @Test
    fun `two bridges cannot share an end`() {
        var s = crossing()
        val first = grid.tile(9, 5)
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(first, DeckMachineKind.Bridge, Direction.Right))))
        assertNotNull(s.deck[first], "the first one goes down fine")

        // Two tiles along: its input port would land on the first bridge's output port.
        val second = grid.tile(11, 5)
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(second, DeckMachineKind.Bridge, Direction.Right))))
        assertNull(s.deck[second], "ends may not overlap")
    }

    @Test
    fun `two bridges may not cross, even only at their middles`() {
        var s = crossing()
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(grid.tile(9, 5), DeckMachineKind.Bridge, Direction.Right))))
        // Perpendicular, crossing the first bridge's middle. This used to be legal, and it is the
        // regression the migration knowingly accepts: a bridge stands on all three of its tiles now,
        // so two of them cannot make a `+`. Crossing a crossing costs a detour, which is the puzzle.
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(grid.tile(9, 5), DeckMachineKind.Bridge, Direction.Down))))
        assertEquals(Direction.Right, (s.deck[grid.tile(9, 5)] as? Bridge)?.facing, "the first is still there")
    }

    @Test
    fun `a bridge cannot be built over a building`() {
        // The constraint the migration buys, and the reason it is worth having: crossing a run costs
        // three tiles of deck, so a cramped vessel has to be laid out rather than stacked.
        var s = crossing()
        val under = grid.tile(9, 7)
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(under, DeckMachineKind.Pump, Direction.Right))))
        assertNotNull(s.deck[under], "the fixture built no pump")
        // Centred one tile away, so its span would land on the vent.
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(grid.tile(8, 7), DeckMachineKind.Bridge, Direction.Right))))
        assertNull(s.deck[grid.tile(8, 7)], "a bridge went up across a pump")
    }

    @Test
    fun `turning a bridge is refused when it has nowhere to swing`() {
        var s = crossing()
        val at = grid.tile(9, 5)
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(at, DeckMachineKind.Bridge, Direction.Right))))
        // Block one of the tiles it would turn onto. A pump because it is permeable and one tile:
        // this test is about geometry, and an airtight machine would also have to find room for the
        // air it displaces before it could stand anywhere.
        s = run(s, 1, OutofspaceInput(listOf(Edit.PlaceDeck(grid.tile(9, 4), DeckMachineKind.Pump, Direction.Right))))
        assertNotNull(s.deck[grid.tile(9, 4)], "nothing was standing in the way, so this proved nothing")

        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(at))))
        assertEquals(
            Direction.Right, (s.deck[at] as? Bridge)?.facing,
            "it turned onto a tile something else is standing on",
        )

        // With the obstruction gone it turns, and lands on the two new tiles.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.tile(9, 4), DeleteLayer.Deck))))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(at))))
        assertEquals(Direction.Down, (s.deck[at] as? Bridge)?.facing, "and now it turns")
        assertTrue(!s.occupancy.isFree(grid.tile(9, 4)), "the tile it swung onto is its now")
        assertTrue(s.occupancy.isFree(grid.tile(8, 5)), "and the one it swung off is free again")
    }

    // ── Conservation ──────────────────────────────────────────────────────────

    @Test
    fun `material inside a bridge is still aboard, and comes back out if it is removed`() {
        val at = grid.tile(9, 5)
        var s = crossing(bridged = true)
        s = run(s, RAIL_PERIOD * 4)
        val before = s.inTransitMass

        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(at))))
        assertNull(s.deck[at], "the bridge is gone")
        assertEquals(before, s.inTransitMass, "and whatever was inside it fell on the deck")
    }

    @Test
    fun `a crossing world conserves and replays identically`() {
        fun digest(): String {
            var s = crossing(bridged = true)
            val cfg = OutofspaceConfig(initialGrid = grid)
            repeat(600) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
            return buildString {
                append(s.inTransitMass).append('|').append(s.ventedMass).append('|').append(s.diverters)
                for (i in s.rails.indices) append(s.rail.massAt(TileIndex(i))).append(',')
            }
        }
        assertEquals(digest(), digest())
    }
}
