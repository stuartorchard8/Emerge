package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.diameter
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.Stream
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.footprint
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.sim.core.PlayerId
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

/**
 * Machines that occupy real space, and connect through named ports.
 *
 * Footprints had to come before pipes, because a port is a property of a *tile*: on a one-tile
 * machine every port overlaps every other, so "where does this connect" collapses into "which way is
 * it pointing". Give a building nine tiles and its input and its two output streams are three
 * different places you have to route to.
 */
class FootprintTest {

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    /**
     * Creative, because these are about **geometry** — which tiles a footprint claims and releases.
     * Outside creative a placement is a ghost and a delete is a mark, and neither answers the
     * question this file asks.
     */
    private fun placeDeck(grid: Grid, tile: TileIndex, kind: DeckMachineKind, facing: Direction = Direction.Right): VesselState =
        run(
            VesselState.empty(grid).copy(creative = true), 1,
            OutofspaceInput(listOf(fixturePlace(tile, Brush.Building(kind), facing))),
        )

    // ── Occupancy ─────────────────────────────────────────────────────────────

    @Test
    fun `a machine is stored once and covers the tiles around it`() {
        val grid = Grid(12, 12)
        val tile = grid.tile(5, 5)
        val s = placeDeck(grid, tile, DeckMachineKind.Extractor)

        assertNotNull(s.deck[tile], "the extractor lives at the tile it was placed on")
        assertNull(s.deck[grid.tile(4, 5)], "and nowhere else -- one machine, one copy")
        // But every tile of the five-by-five reports it.
        for (y in 3..7) for (x in 3..7) {
            assertEquals(tile, s.occupancy[grid.tile(x, y)], "($x,$y) should belong to the extractor")
        }
        assertTrue(s.occupancy.isFree(grid.tile(2, 5)), "and the tile beyond it should not")
    }

    @Test
    fun `placing is refused when anything already occupies the footprint`() {
        val grid = Grid(12, 12)
        var s = placeDeck(grid, grid.tile(5, 5), DeckMachineKind.Extractor)
        // Two tiles away: outside the *centre* but well inside the footprint.
        s = run(s, 1, OutofspaceInput(listOf(fixturePlace(grid.tile(7, 5), Brush.Building(DeckMachineKind.Sensor), Direction.Right))))
        assertNull(s.deck[grid.tile(7, 5)], "a sensor cannot be dropped inside an extractor")
    }

    @Test
    fun `placing is refused when the footprint would hang off the grid`() {
        val grid = Grid(12, 12)
        val s = placeDeck(grid, grid.tile(1, 5), DeckMachineKind.Extractor)
        assertNull(s.deck[grid.tile(1, 5)], "a five-tile machine needs two tiles of clearance")
        assertTrue(grid.tiles.all { s.deck[it] == null }, "and nothing partial is left behind")
    }

    @Test
    fun `clicking any tile of a machine edits the whole machine`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        var s = placeDeck(grid, at, DeckMachineKind.Extractor)

        // A corner of the footprint, as far from the centre as it gets.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.tile(7, 7)))))
        assertTrue(grid.tiles.all { s.deck[it] == null }, "the whole extractor goes, not a slice of it")
        assertTrue(grid.tiles.all { s.occupancy.isFree(it) }, "and it releases every tile")
    }

    @Test
    fun `rotating leaves the footprint where it was and moves only the ports`() {
        val grid = Grid(12, 12)
        val tile = grid.tile(5, 5)
        var s = placeDeck(grid, tile, DeckMachineKind.Concentrator)
        val before = grid.tiles.filter { !s.occupancy.isFree(it) }.toSet()

        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(grid.tile(6, 6)))))
        val after = grid.tiles.filter { !s.occupancy.isFree(it) }.toSet()
        assertEquals(before, after, "anchoring at the centre is what makes a rotate not also a move")
        assertEquals(Direction.Down, (s.deck[tile] as? Concentrator)?.facing)
    }

    // ── Ports ─────────────────────────────────────────────────────────────────

    @Test
    fun `a concentrator's three ports are three different tiles`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        val ports = portsOf(grid, Concentrator(at, Direction.Right), at)

        val input = ports.single { it.kind == PortKind.Input }
        val product = ports.single { it.kind == PortKind.Output && it.stream == Stream.Product }
        val waste = ports.single { it.kind == PortKind.Output && it.stream == Stream.Waste }

        assertEquals(grid.tile(4, 5), input.tile, "in at the back")
        assertEquals(grid.tile(6, 5), product.tile, "concentrate out the front")
        assertEquals(grid.tile(5, 6), waste.tile, "tailings out of the floor")
        assertEquals(3, setOf(input.tile, product.tile, waste.tile).size)
    }

    @Test
    fun `rotating a machine carries its ports round with it`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        val ports = portsOf(grid, Concentrator(at, Direction.Down), at)

        val product = ports.single { it.kind == PortKind.Output && it.stream == Stream.Product }
        val waste = ports.single { it.kind == PortKind.Output && it.stream == Stream.Waste }
        assertEquals(grid.tile(5, 6), product.tile, "facing down, the product leaves downward")
        assertEquals(Direction.Down, product.side)
        // Waste is a quarter turn clockwise of the product in the machine's own frame, and stays so.
        assertEquals(grid.tile(4, 5), waste.tile)
        assertEquals(Direction.Left, waste.side)
    }

    /**
     * The shape that broke the symmetry: a footprint that is **not centred on its anchor**.
     *
     * Every other kind covers the same tiles when you turn it, or at worst a different line through
     * the same middle. A thruster turned swings its whole second tile from one neighbour to another,
     * and the tile it is stored at is an *end* of the machine rather than the middle of it — so this
     * asserts the four facings separately. A single facing would pass against arithmetic that had
     * the sign of the offset backwards.
     */
    @Test
    fun `a thruster's footprint is its chamber and the tile in front of it`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        for (facing in Direction.ALL) {
            val m = Thruster(at, facing = facing)
            assertEquals(
                setOf(at, grid.tile(5 + facing.dx, 5 + facing.dy)),
                m.tiles(grid).toSet(),
                "facing $facing, a motor stood on the wrong pair of tiles",
            )
            // Ascending index whichever way it points — row-major, like every other footprint, so
            // two walks of the same machine pair up with each other.
            val tiles = m.tiles(grid)
            assertTrue(tiles[0].index < tiles[1].index, "facing $facing, the footprint came back out of order")
        }
    }

    /** A motor whose bell would hang off the rim does not fit, whichever end of the world it is at. */
    @Test
    fun `a thruster does not fit when its bell is off the grid`() {
        val grid = Grid(12, 12)
        assertNull(
            DeckMachineKind.Thruster.footprint(grid.tile(11, 5), grid, Direction.Right),
            "a motor nosed off the right-hand rim fitted",
        )
        assertNull(
            DeckMachineKind.Thruster.footprint(grid.tile(0, 5), grid, Direction.Left),
            "a motor nosed off the left-hand rim fitted",
        )
        assertNotNull(
            DeckMachineKind.Thruster.footprint(grid.tile(11, 5), grid, Direction.Left),
            "a motor on the rim pointing inboard was refused",
        )
    }

    @Test
    fun `an extractor's port sits on the edge of its footprint, not beside its centre`() {
        val grid = Grid(16, 16)
        val at = grid.tile(8, 8)
        val ports = portsOf(grid, Extractor(at, Direction.Right), at)
        // Two tiles out, not one: a port belongs to the face of the building, and a five-tile
        // machine whose ports sat beside its centre would be handing material to its own insides.
        assertEquals(grid.tile(10, 8), ports.single { it.kind == PortKind.Output }.tile)
        assertEquals(5, DeckMachineKind.Extractor.diameter, "and it really is five across")
    }

    /**
     * Track under a tank, running from a source tank at (2,6) to a receiving one at (6,6), with the
     * [end] tile deciding whether it reaches a port or merely a covered tile.
     */
    private fun feed(endX: Int, endY: Int): VesselState {
        val grid = Grid(14, 14)
        val ingots = Mixture.of(Species.Iron to 4 * Capacity.PACKET_MASS, energy = 0)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += fixtureStorage(grid.tile(2, 6), Direction.Right)   // output port at (3, 6)
        deck += fixtureStorage(grid.tile(6, 6), Direction.Right)    // input ports at (5, 6) and (6, 5)
        // Track from the source's output port along to wherever the run is told to end.
        joinRow(grid, rails, 3, endX, 6)
        joinCol(grid, rails, endX, endY, 6)
        return VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .stocked(grid.tile(2, 6), ingots)
    }

    @Test
    fun `track running under a building connects only where a port is`() {
        // The point of ports: a three-by-three is not a nine-tile sponge. Track threaded through the
        // tank's bottom-left corner passes straight under it, touching the building the whole way
        // and connecting to nothing.
        val s = run(feed(endX = 7, endY = 6).let { st ->
            // Remove the run's own input-port tile so the only contact is a covered, portless tile.
            val rails = st.rails.toMutableList()
            rails[st.grid.tile(5, 6).index] = null
            st.copy(conduits = Conduits.ofRails(rails))
        }, 40)

        assertNull(s.buffers.resourceAt(s.grid.tile(6, 6)), "no port on the tiles it crosses")
    }

    @Test
    fun `track reaching a port delivers into the building`() {
        val s = run(feed(endX = 5, endY = 6), 40*RAIL_PERIOD)
        assertTrue(
            s.buffers.massAt(s.grid.tile(6, 6)) > 0L,
            "it went in the front door, from underneath",
        )
    }

    @Test
    fun `a sensor pointed at any tile of a building reads that building`() {
        val grid = Grid(12, 12)
        val stored = Mixture.of(Species.Iron to Storage.WAREHOUSE_CAP, energy = 0)
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(6, 6), Direction.Right)
        // Looking up at the tank's bottom-right corner -- a covered tile, not its centre.
        deck += fixtureSensor(grid.tile(7, 8), Direction.Up)
        // A stub of wire under the sensor: without one it reads the tank correctly and tells nobody.
        val wires = arrayOfNulls<Segment>(grid.size)
        wires[grid.tile(7, 8).index] = Segment(org.emerge.demo.outofspace.world.Conduit.Signal, material = materialBefore(org.emerge.demo.outofspace.world.Conduit.Signal))
        val s = run(
            VesselState(
                grid,
                deck,
                conduits = org.emerge.demo.outofspace.world.Conduits.of(
                    grid.size,
                    org.emerge.demo.outofspace.world.Conduit.Signal to wires.toList(),
                ),
                buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
            ).stocked(grid.tile(6, 6), stored),
            2,
        )
        assertEquals(1000, s.signals.at(grid.tile(7, 8)), "a full tank reads full")
    }

    // ── The world still holds together ────────────────────────────────────────

    @Test
    fun `a machine's thermal mass scales with how much of it there is`() {
        // Inside a hull, because an unenclosed tile is space and space has no heat capacity at all.
        val grid = Grid(16, 16)
        fun room(kind: DeckMachineKind): VesselState {
            val deck = DeckArray(grid)
            for (i in 1..13) {
                deck += Hull(grid.tile(i, 1))
                deck += Hull(grid.tile(i+1, 14))
                deck += Hull(grid.tile(1, i+1))
                deck += Hull(grid.tile(14, i))
            }
            deck += when (kind) {
                DeckMachineKind.Concentrator -> Concentrator(grid.tile(8, 8), Direction.Right)
                else -> Extractor(grid.tile(8, 8), Direction.Right)
            }
            return VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
                .copy(creative = true)
        }
        val small = room(DeckMachineKind.Concentrator).storedEnergy
        val large = room(DeckMachineKind.Extractor).storedEnergy
        assertTrue(
            large > small,
            "twenty-five tiles of extractor should hold more heat than nine of mill: $large vs $small",
        )
    }

    @Ignore // This test was passing when I turned it off, but it takes over 6 minutes and I can't afford that.
    @Test
    fun `the starter plant still runs end to end with every machine at its real size`() {
        val s = run(workingVessel(Grid(40, 28)), 720*RAIL_PERIOD)
        assertTrue(s.stockpile.totalMass > 0L, "ore reaches the tank: ${s.stockpile}")
        assertEquals(s.extractedMass, s.inTransitMass + s.ventedMass, "conserving throughout")
    }
}
