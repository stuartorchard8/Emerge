package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.OutofspaceReducer.HEAT_PERIOD
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.capacityPerTile
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.bodiesOf
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Four networks on one tile grid, which is what [Conduit] always claimed and the storage never was.
 *
 * The crossing case is a **wire under a rail** rather than a pipe under a rail, because track and
 * plumbing now compete for the floor and cannot share a tile — see `Conduits.checkExclusion`. Wires
 * are the layers that still ride under anything, so they are what these tests use to say "two
 * networks, one tile, one grid".
 *
 * The bug this pins is quiet and was documented as working: `layConduit` carried the comment "drawn
 * across a rail is a crossing, not a junction" while sharing one segment list with the track, so it
 * found the conduit mismatch and returned having laid nothing. Dragging over a rail produced nothing
 * and no error. A test that only checked the run's *ends* would still have passed, which is why the
 * one below checks the crossing tile itself.
 */
class ConduitLayersTest {

    private val grid = Grid(12, 6)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun lay(state: VesselState, conduit: Conduit, from: TileIndex, to: TileIndex): VesselState =
        OutofspaceReducer.reduce(
            cfg,
            state,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Lay(from, to, conduit)))),
        )

    /** Drag a straight run, one step at a time, the way the controller emits it. */
    private fun drag(state: VesselState, conduit: Conduit, y: Int, fromX: Int, toX: Int): VesselState {
        var s = state
        for (x in fromX until toX) s = lay(s, conduit, grid.tile(x, y), grid.tile(x + 1, y))
        return s
    }

    private fun dragDown(state: VesselState, conduit: Conduit, x: Int, fromY: Int, toY: Int): VesselState {
        var s = state
        for (y in fromY until toY) s = lay(s, conduit, grid.tile(x, y), grid.tile(x, y + 1))
        return s
    }

    @Test
    fun `a wire drawn across a rail leaves both on the crossing tile`() {
        var s = VesselState.empty(grid)
        s = drag(s, Conduit.Rail, y = 3, fromX = 2, toX = 8)
        s = dragDown(s, Conduit.Signal, x = 5, fromY = 1, toY = 5)

        val crossing = grid.tile(5, 3)
        val rail = s.conduits.at(Conduit.Rail, crossing)
        val wire = s.conduits.at(Conduit.Signal, crossing)

        assertNotNull(rail, "the rail was evicted by the wire crossing it")
        assertNotNull(wire, "no wire was laid on the crossing tile at all")
        assertEquals(Conduit.Rail, rail.conduit)
        assertEquals(Conduit.Signal, wire.conduit)
    }

    /**
     * Track and plumbing compete for the floor: the drag stops at the belt instead of hopping it.
     *
     * The run's *ends* are the trap here as much as they were for the old bug. A pipe dragged from
     * above the rail to below it must come out as two runs that do not meet, not as one run with a
     * gap that is still joined across — the join is what a network walk would follow.
     */
    @Test
    fun `a pipe drawn across a rail stops at it`() {
        var s = VesselState.empty(grid)
        s = drag(s, Conduit.Rail, y = 3, fromX = 2, toX = 8)
        s = dragDown(s, Conduit.Pipe, x = 5, fromY = 1, toY = 5)

        val crossing = grid.tile(5, 3)
        assertNotNull(s.conduits.at(Conduit.Rail, crossing), "the rail was evicted by the pipe")
        assertNull(s.conduits.at(Conduit.Pipe, crossing), "a pipe was laid on top of a rail")

        // Above and below survive: the obstacle costs the tile it is on, not the whole drag.
        val above = s.conduits.at(Conduit.Pipe, grid.tile(5, 2))
        val below = s.conduits.at(Conduit.Pipe, grid.tile(5, 4))
        assertNotNull(above, "the pipe above the rail was lost too")
        assertNotNull(below, "the drag gave up at the obstacle instead of resuming past it")
        assertTrue(!above.linkedTo(Direction.Down), "the pipe joined itself across the rail")
        assertTrue(!below.linkedTo(Direction.Up), "the pipe joined itself across the rail")
    }

    @Test
    fun `each layer is linked along itself and to nothing on the other`() {
        var s = VesselState.empty(grid)
        s = drag(s, Conduit.Rail, y = 3, fromX = 2, toX = 8)
        s = dragDown(s, Conduit.Signal, x = 5, fromY = 1, toY = 5)

        val crossing = grid.tile(5, 3)
        val rail = s.conduits.at(Conduit.Rail, crossing)!!
        val wire = s.conduits.at(Conduit.Signal, crossing)!!

        assertTrue(rail.linkedTo(Direction.Left) && rail.linkedTo(Direction.Right), "rail runs across")
        assertTrue(!rail.linkedTo(Direction.Up) && !rail.linkedTo(Direction.Down), "rail took the wire's links")
        assertTrue(wire.linkedTo(Direction.Up) && wire.linkedTo(Direction.Down), "wire runs down")
        assertTrue(!wire.linkedTo(Direction.Left) && !wire.linkedTo(Direction.Right), "wire took the rail's links")
    }

    @Test
    fun `two fittings on one tile are two bodies with their own temperatures`() {
        val rails = arrayOfNulls<Segment>(grid.size)
        val wires = arrayOfNulls<Segment>(grid.size)
        val tile = grid.tile(5, 3)
        rails[tile.index] = Segment(Conduit.Rail)
        wires[tile.index] = Segment(Conduit.Signal)
        val conduits = Conduits.of(
            grid.size,
            Conduit.Rail to rails.toList(),
            Conduit.Signal to wires.toList(),
        )

        val bodies = bodiesOf(grid, conduits, DeckArray(grid), BufferLayer.empty(grid.size), RailLayer.empty(grid.size))

        assertEquals(2, bodies.size, "one body per fitting, not one per tile")
        assertEquals(
            setOf(Conduit.Rail, Conduit.Signal),
            bodies.map { it.conduit }.toSet(),
            "a body has to say which layer it is on, or its heat cannot be put back",
        )
        // Iron and copper: different stuff, so different capacities for the same one tile. This is
        // exactly what the per-tile heat field could not represent.
        assertTrue(
            bodies[0].capacity != bodies[1].capacity,
            "two materials on one tile came out with the same heat capacity",
        )
    }

    /**
     * Heat crosses between layers where they *share a tile* and by no other route.
     *
     * Written as a pair rather than as a magnitude, because magnitudes here are misleading: copper
     * has almost no thermal mass, so the wire runs *hotter* than the iron rail feeding it on a
     * fraction of the energy. Comparing the two temperatures measures the materials, not the
     * topology. What is unambiguous is the control — a wire run one tile clear of the rail must gain
     * **nothing**, because there is no contact for heat to cross at all. Not *exactly* ambient: it is
     * hanging in vacuum, so it radiates and ends up a little colder. Colder is the proof.
     *
     * A wire and not a pipe, since the exclusion rule: the shared-tile case is only reachable at all
     * for the layers that are allowed to share a tile. The mechanism under test is the layer contact
     * in `bodiesOf`, which does not know one conduit from another.
     */
    @Test
    fun `heat reaches another layer only where the two share a tile`() {
        fun runWithWire(wireFromY: Int, wireToY: Int): Int {
            // In vacuum, with no hull: a room's air is an enormous reservoir beside a tile of copper
            // and drains the run to ambient long before the signal shows. Real conduction, and it
            // would drown what this test is asking about.
            var s = VesselState.empty(grid).copy(creative = true)
            s = drag(s, Conduit.Rail, y = 3, fromX = 2, toX = 8)
            s = dragDown(s, Conduit.Signal, x = 5, fromY = wireFromY, toY = wireToY)

            val hotEnd = grid.tile(2, 3)
            val hot = s.conduits.energyAt(Conduit.Rail, hotEnd)
            s = s.copy(conduits = s.conduits.heated(Conduit.Rail, hotEnd, hot * 3))
            repeat(20*HEAT_PERIOD) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

            val probe = grid.tile(5, wireFromY)
            return (s.conduits.energyAt(Conduit.Signal, probe) / Conduit.Signal.capacityPerTile).toInt()
        }

        // Crossing the rail at (5,3): the two share that tile, so heat gets across.
        val crossing = runWithWire(wireFromY = 2, wireToY = 4)
        // Clear of it, ending at (5,2) with the rail at y=3: adjacent, never sharing a tile.
        val clear = runWithWire(wireFromY = 1, wireToY = 2)

        assertTrue(crossing > Temperature.AMBIENT_KELVIN, "no heat crossed at the shared tile (${crossing}K)")
        assertTrue(
            clear <= Temperature.AMBIENT_KELVIN,
            "a wire that never shares a tile with the rail warmed anyway (${clear}K) — heat crossed " +
                "a face between two permeable fittings, which is not a contact",
        )
    }

    @Test
    fun `heat runs along a layer to the far end of its own run`() {
        var s = VesselState.empty(grid).copy(creative = true)
        s = drag(s, Conduit.Rail, y = 3, fromX = 2, toX = 8)

        val hotEnd = grid.tile(2, 3)
        val hot = s.conduits.energyAt(Conduit.Rail, hotEnd)
        s = s.copy(conduits = s.conduits.heated(Conduit.Rail, hotEnd, hot * 3))
        repeat(20*HEAT_PERIOD) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        val far = grid.tile(7, 3)
        val kelvin = (s.conduits.energyAt(Conduit.Rail, far) / Conduit.Rail.capacityPerTile).toInt()
        assertTrue(kelvin > Temperature.AMBIENT_KELVIN, "heat did not travel along the run (${kelvin}K)")
    }
}
