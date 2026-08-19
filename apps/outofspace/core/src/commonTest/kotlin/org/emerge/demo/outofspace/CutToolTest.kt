package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cut tool: severing joins without taking anything up.
 *
 * Conduit connects by being *drawn*, never by touching — so a run is only a run where the player
 * dragged one. This is the other half of that idea, and what these pin is that it is the exact
 * opposite of the build gesture rather than a flavour of delete: the track stays, only the
 * connection goes.
 */
class CutToolTest {

    private val grid = Grid(14, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** A belt along row 4 and a wire along the same row, threaded over each other. */
    private fun world(): VesselState {
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 2, 11, 4)
        var s = VesselState(
            grid,
            DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, DeckArray(grid)),
            rail = RailLayer.empty(grid.size),
        )
        // Laid the way a player lays it, because the wire is the whole point of the second test and
        // a fixture that stated it directly could state something the edit path cannot produce.
        s = OutofspaceReducer.reduce(
            cfg, s,
            mapOf(
                org.emerge.sim.core.PlayerId(0) to OutofspaceInput(
                    (2 until 11).map { Edit.Lay(grid.tile(it, 4), grid.tile(it + 1, 4), Conduit.Signal) },
                ),
            ),
        )
        return s
    }

    private fun controller(state: VesselState = world()): OutofspaceController =
        OutofspaceController(cfg, state)

    private fun joined(s: VesselState, conduit: Conduit, x: Int): Boolean =
        s.conduits.at(conduit, grid.tile(x, 4))?.linkedTo(Direction.Right) == true

    /**
     * ⛔ The gesture this exists for: a stroke **across** a belt, which is how a player says "not
     * that one" about a junction they did not mean to make.
     *
     * A cut that only severed the joins running *parallel* to the stroke would do nothing whatever
     * here, which is why the rule is every join of every tile passed rather than the literal inverse
     * of the laying step.
     */
    @Test
    fun `a stroke across a belt cuts it`() {
        val c = controller()
        c.tool = Tool.Cut
        c.apply(grid.tile(6, 3))
        c.dragTo(grid.tile(6, 5))
        val s = c.stepOnce()

        assertFalse(joined(s, Conduit.Rail, 5), "the belt is still joined where the stroke crossed it")
        assertTrue(joined(s, Conduit.Rail, 2), "the stroke cut track it never went near")
        assertTrue(joined(s, Conduit.Rail, 9), "the stroke cut track it never went near")
        assertTrue(
            s.conduits.at(Conduit.Rail, grid.tile(6, 4)) != null,
            "the tile came up: this is a cut, not a delete",
        )
    }

    /**
     * ⚠️ **Rail and wire share a tile — they are the one pair that may.** A cut that took both would
     * break a signal network as a side effect of tidying track, which is the failure the delete
     * tool's own layer selector exists to prevent.
     */
    @Test
    fun `cutting the rail leaves the wire under it alone`() {
        val c = controller()
        c.tool = Tool.Cut
        c.cutConduit = Conduit.Rail
        c.apply(grid.tile(6, 4))
        val s = c.stepOnce()

        assertFalse(joined(s, Conduit.Rail, 6), "the rail was not cut")
        assertTrue(joined(s, Conduit.Signal, 6), "the wire went with it")
    }

    /**
     * ⚠️ A fast drag skips tiles, so the path is stepped out — the same rule as laying, and it
     * matters more here: a run with a tile the stroke missed is still a run, and *looks* cut.
     */
    @Test
    fun `a drag that skips tiles still cuts every one of them`() {
        val c = controller()
        c.tool = Tool.Cut
        c.apply(grid.tile(4, 4))
        c.dragTo(grid.tile(9, 4))
        val s = c.stepOnce()

        for (x in 4..8) {
            assertFalse(joined(s, Conduit.Rail, x), "the join at x=$x survived a drag straight over it")
        }
    }

    /**
     * The point of the whole tool, stated in the only terms that matter: nothing gets through.
     *
     * A tank at one end and a length of ghost track at the other, so material genuinely wants to
     * cross — then one stroke, and it does not.
     */
    @Test
    fun `nothing crosses a cut`() {
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(1, 4), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 2, 11, 4)
        val start = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(
            grid.tile(1, 4),
            Mixture.of(Species.Iron to 20 * Capacity.PACKET_MASS, energy = 0),
        ).copy(creative = false)
        start.conduits.tracks[Conduit.Rail].release(grid.tile(11, 4))

        val c = controller(start)
        c.tool = Tool.Cut
        c.apply(grid.tile(7, 4))
        c.stepOnce()
        repeat(OutofspaceReducer.RAIL_PERIOD * 60) { c.stepOnce() }
        val s = c.state

        assertFalse(
            s.conduits.isComplete(Conduit.Rail, grid.tile(11, 4)),
            "the ghost built itself across a severed belt",
        )
        assertTrue(
            (8..11).none { !s.rail.isEmpty(grid.tile(it, 4)) },
            "iron got past the cut",
        )
    }
}
