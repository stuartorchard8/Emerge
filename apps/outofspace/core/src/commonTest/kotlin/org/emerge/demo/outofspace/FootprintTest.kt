package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Belt
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Stream
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.size
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val cfg = OutofspaceConfig(grid = state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun place(grid: Grid, at: Int, kind: MachineKind, facing: Direction = Direction.Right): VesselState =
        run(VesselState(grid, List(grid.size) { null }), 1, OutofspaceInput(listOf(Edit.Place(at, kind, facing))))

    // ── Occupancy ─────────────────────────────────────────────────────────────

    @Test
    fun `a machine is stored once and covers the tiles around it`() {
        val grid = Grid(12, 12)
        val at = grid.index(5, 5)
        val s = place(grid, at, MachineKind.Smelter)

        assertNotNull(s[at], "the smelter lives at the tile it was placed on")
        assertNull(s[grid.index(4, 5)], "and nowhere else -- one machine, one copy")
        // But every tile of the five-by-five reports it.
        for (y in 3..7) for (x in 3..7) {
            assertEquals(at, s.occupancy[grid.index(x, y)], "($x,$y) should belong to the smelter")
        }
        assertTrue(s.occupancy.isFree(grid.index(2, 5)), "and the tile beyond it should not")
    }

    @Test
    fun `placing is refused when anything already occupies the footprint`() {
        val grid = Grid(12, 12)
        var s = place(grid, grid.index(5, 5), MachineKind.Smelter)
        // Two tiles away: outside the *centre* but well inside the footprint.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(grid.index(7, 5), MachineKind.Belt, Direction.Right))))
        assertNull(s[grid.index(7, 5)], "a belt cannot be dropped inside a furnace")
    }

    @Test
    fun `placing is refused when the footprint would hang off the grid`() {
        val grid = Grid(12, 12)
        val s = place(grid, grid.index(1, 5), MachineKind.Smelter)
        assertNull(s[grid.index(1, 5)], "a five-tile machine needs two tiles of clearance")
        assertTrue(s.machines.all { it == null }, "and nothing partial is left behind")
    }

    @Test
    fun `clicking any tile of a machine edits the whole machine`() {
        val grid = Grid(12, 12)
        val at = grid.index(5, 5)
        var s = place(grid, at, MachineKind.Smelter)

        // A corner of the footprint, as far from the centre as it gets.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.index(7, 7)))))
        assertTrue(s.machines.all { it == null }, "the whole furnace goes, not a slice of it")
        assertTrue((0 until grid.size).all { s.occupancy.isFree(it) }, "and it releases every tile")
    }

    @Test
    fun `rotating leaves the footprint where it was and moves only the ports`() {
        val grid = Grid(12, 12)
        val at = grid.index(5, 5)
        var s = place(grid, at, MachineKind.Processor)
        val before = (0 until grid.size).filter { !s.occupancy.isFree(it) }.toSet()

        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(grid.index(6, 6)))))
        val after = (0 until grid.size).filter { !s.occupancy.isFree(it) }.toSet()
        assertEquals(before, after, "anchoring at the centre is what makes a rotate not also a move")
        assertEquals(Direction.Down, (s[at] as Processor).facing)
    }

    // ── Ports ─────────────────────────────────────────────────────────────────

    @Test
    fun `a processor's three ports are three different tiles`() {
        val grid = Grid(12, 12)
        val at = grid.index(5, 5)
        val ports = portsOf(grid, Processor(Direction.Right), at)

        val input = ports.single { it.kind == PortKind.Input }
        val product = ports.single { it.kind == PortKind.Output && it.stream == Stream.Product }
        val waste = ports.single { it.kind == PortKind.Output && it.stream == Stream.Waste }

        assertEquals(grid.index(4, 5), input.tile, "in at the back")
        assertEquals(grid.index(6, 5), product.tile, "concentrate out the front")
        assertEquals(grid.index(5, 6), waste.tile, "tailings out of the floor")
        assertEquals(3, setOf(input.tile, product.tile, waste.tile).size)
    }

    @Test
    fun `rotating a machine carries its ports round with it`() {
        val grid = Grid(12, 12)
        val at = grid.index(5, 5)
        val ports = portsOf(grid, Processor(Direction.Down), at)

        val product = ports.single { it.kind == PortKind.Output && it.stream == Stream.Product }
        val waste = ports.single { it.kind == PortKind.Output && it.stream == Stream.Waste }
        assertEquals(grid.index(5, 6), product.tile, "facing down, the product leaves downward")
        assertEquals(Direction.Down, product.side)
        // Waste is a quarter turn clockwise of the product in the machine's own frame, and stays so.
        assertEquals(grid.index(4, 5), waste.tile)
        assertEquals(Direction.Left, waste.side)
    }

    @Test
    fun `a smelter's ports sit on the edge of its footprint, not beside its centre`() {
        val grid = Grid(16, 16)
        val at = grid.index(8, 8)
        val ports = portsOf(grid, Smelter(Direction.Right), at)
        assertEquals(grid.index(6, 8), ports.single { it.kind == PortKind.Input }.tile)
        assertEquals(
            grid.index(10, 8),
            ports.single { it.kind == PortKind.Output && it.stream == Stream.Product }.tile,
        )
        assertEquals(5, MachineKind.Smelter.size, "and it really is five across")
    }

    @Test
    fun `material offered to the side of a building is refused`() {
        // The point of ports: a three-by-three is not a nine-tile sponge. A belt pushing at the tank's
        // flank has nothing to connect to, even though it is touching the building.
        val grid = Grid(12, 12)
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val m = arrayOfNulls<Machine>(grid.size)
        // Tank centred at (6,6): input ports at (5,6) facing Left and (6,5) facing Up.
        m[grid.index(6, 6)] = Storage(Direction.Right)
        // A belt below the tank's bottom-left corner, pushing up into (5, 7) -- part of the footprint,
        // but not a port.
        m[grid.index(5, 8)] = Belt(Direction.Up, listOf(ingot))
        var s = VesselState(grid, m.toList())
        s = run(s, Belt.STEP_TICKS * 4)

        assertNull((s[grid.index(6, 6)] as Storage).contents, "the tank has no port on that face")
        assertEquals(1_000L, s.inTransitGrams, "and the packet is still sitting on the belt")
    }

    @Test
    fun `material offered to a real port is taken`() {
        val grid = Grid(12, 12)
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val m = arrayOfNulls<Machine>(grid.size)
        m[grid.index(6, 6)] = Storage(Direction.Right)
        // Same building, same distance -- but aimed at the input port on its left edge.
        m[grid.index(4, 6)] = Belt(Direction.Right, listOf(ingot))
        var s = VesselState(grid, m.toList())
        s = run(s, Belt.STEP_TICKS * 4)

        assertEquals(1_000L, (s[grid.index(6, 6)] as Storage).contents?.mass, "it went in the front door")
    }

    @Test
    fun `a fabricator's two inputs are on two different faces`() {
        // Which is the recipe, spatially: two ingredients means two lines arriving from two
        // directions, rather than one belt that happens to carry both.
        val grid = Grid(12, 12)
        val ports = portsOf(grid, org.emerge.demo.outofspace.world.Fabricator(Direction.Right), grid.index(6, 6))
        val inputs = ports.filter { it.kind == PortKind.Input }
        assertEquals(2, inputs.size)
        assertEquals(setOf(Direction.Left, Direction.Up), inputs.map { it.side }.toSet())
        assertEquals(2, inputs.map { it.tile }.toSet().size, "and they are genuinely different tiles")
    }

    @Test
    fun `a sensor pointed at any tile of a building reads that building`() {
        val grid = Grid(12, 12)
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to Storage.CAP))
        val m = arrayOfNulls<Machine>(grid.size)
        m[grid.index(6, 6)] = Storage(Direction.Right, stored)
        // Looking up at the tank's bottom-right corner -- a covered tile, not its centre.
        m[grid.index(7, 8)] = org.emerge.demo.outofspace.world.Sensor(
            Direction.Up,
            org.emerge.demo.outofspace.world.Channel.Red,
        )
        val s = run(VesselState(grid, m.toList()), 2)
        assertEquals(1000, s.signals[org.emerge.demo.outofspace.world.Channel.Red], "a full tank reads full")
    }

    // ── The world still holds together ────────────────────────────────────────

    @Test
    fun `a machine's thermal mass scales with how much of it there is`() {
        // Inside a hull, because an unenclosed tile is space and space has no heat capacity at all.
        val grid = Grid(16, 16)
        fun room(kind: MachineKind): VesselState {
            val m = arrayOfNulls<Machine>(grid.size)
            for (i in 1..14) {
                m[grid.index(i, 1)] = org.emerge.demo.outofspace.world.Hull()
                m[grid.index(i, 14)] = org.emerge.demo.outofspace.world.Hull()
                m[grid.index(1, i)] = org.emerge.demo.outofspace.world.Hull()
                m[grid.index(14, i)] = org.emerge.demo.outofspace.world.Hull()
            }
            m[grid.index(8, 8)] = OutofspaceReducer.let { _ ->
                when (kind) {
                    MachineKind.Processor -> Processor(Direction.Right)
                    else -> Smelter(Direction.Right)
                }
            }
            return VesselState(grid, m.toList())
        }
        val small = room(MachineKind.Processor).storedJoules
        val large = room(MachineKind.Smelter).storedJoules
        assertTrue(
            large > small,
            "twenty-five tiles of furnace should hold more heat than nine of mill: $large vs $small",
        )
    }

    @Test
    fun `the starter plant still runs end to end with every machine at its real size`() {
        val s = run(org.emerge.demo.outofspace.world.starterVessel(Grid(40, 28)), 60 * 180)
        assertTrue(s.stockpile[Form.IronIngot].total > 0L, "iron reaches the tank: ${s.stockpile}")
        assertEquals(
            s.stockpile[Form.IronIngot].total,
            s.stockpile[Form.IronIngot][Species.Iron],
            "and it is pure",
        )
        assertEquals(s.minedGrams, s.inTransitGrams + s.ventedGrams, "conserving throughout")
    }
}
