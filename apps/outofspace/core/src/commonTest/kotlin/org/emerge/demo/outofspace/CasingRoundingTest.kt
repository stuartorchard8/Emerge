package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machineBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DeconstructRoundingTest]'s twin, one layer up: **a machine shedding its casing has the same rule
 * as a rail shedding its metal**, and did not have it.
 *
 * A marked rail asks [org.emerge.demo.outofspace.world.Whitelist.room] and hands over exactly what
 * is wanted. A marked machine asked only [org.emerge.demo.outofspace.world.Whitelist.permits] — a
 * *boolean* — and then took a whole packet against the track's headroom. So a site short of a
 * fraction of a packet got a whole one, and the difference stood on the belt for ever, in front of
 * the material that would have finished the job.
 *
 * Stu's save, 2026-08-25: a `Storage` marked at (14,10) and a `Sensor` ghost at (13,12) with a full
 * titanium packet already on its way. Both are titanium, which is what makes the surplus travel at
 * all — a species the site refuses is caught by the kind question and never leaves.
 */
class CasingRoundingTest {

    private val grid = Grid(10, 4)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private val marked = grid.tile(2, 2)
    private val ghost = grid.tile(6, 2)

    /**
     * A marked `Storage` at one end of a run and a `Sensor` ghost at the other, already built to
     * within [shortBy] of its bill.
     *
     * ⚠️ **Short by less than a packet**, which is the whole shape: short by more and a whole packet
     * is exactly what should be sent, and the bug is invisible.
     */
    private fun world(shortBy: Long = Capacity.PACKET_MASS / 4): VesselState {
        val deck = DeckArray(grid)
        deck += Storage(marked, Direction.Right)
        deck.stand(Sensor(ghost, Direction.Right), withCasing = false)

        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, grid.xOf(marked), grid.xOf(ghost), 2)

        val s = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false, scrapping = setOf(marked))

        // The ghost, part-built. Put down as its own bill less the shortfall, so what it is still
        // owed is a fraction of a packet and nothing else.
        val bill = machineBillOfMaterials(DeckMachineKind.Sensor, 1)
        val standing = bill.scaledTo(bill.total - shortBy)
        for (sp in Species.ALL) deck.stuff[ghost, sp] = standing[sp]
        return s
    }

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * ⛔ **A machine coming apart hands back what is wanted, not a whole packet.**
     */
    @Test
    fun `a marked machine sheds no more casing than the network wants`() {
        val s = run(world(), RAIL_PERIOD * 40)

        assertTrue(s.deck[ghost] != null && !s.deck.isGhost(ghost), "the sensor never finished")
        for (t in grid.tiles) {
            assertEquals(0L, s.rail.massAt(t), "a surplus is standing at $t: ${s.rail.resourceAt(t)}")
        }
    }

    /** And the marked machine keeps what nobody asked for, rather than emptying itself onto the run. */
    @Test
    fun `the marked machine keeps the rest of its casing`() {
        val start = world()
        // Summed over the footprint: casing comes off a machine **evenly**, so the centre tile on
        // its own is a fraction of the answer.
        fun casing(v: VesselState): Long =
            v.deck[marked]!!.tiles(grid).sumOf { v.deck.stuff.massAt(it) }
        val before = casing(start)
        val s = run(start, RAIL_PERIOD * 40)
        val shed = before - casing(s)
        assertEquals(Capacity.PACKET_MASS / 4, shed, "the machine shed the wrong amount of casing")
    }
}
