package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.process
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The concentrator's **port contract**, and the backpressure that gives it teeth.
 *
 * Concentrate leaves by the front port, tailings by the one in the floor. Getting these the wrong way
 * round would silently invert the whole refining game, and it is not something you can see by looking
 * at a running world — hence a test that measures purity on each side by name.
 *
 * These layouts are drawn to scale now that machines are rooms, with track threaded underneath them
 * to reach their ports. Each machine's output starts a **new run** — its input and its output are
 * different networks, and one continuous line under everything would put a concentrator's concentrate
 * back onto the pipe feeding its own input.
 */
class ConcentratorChainTest {

    /**
     * Ticks to watch a primed chain for, to be sure of catching every stage holding a packet.
     *
     * A stage's cycle is short — it fills its product buffer and empties it into the belt within a
     * few ticks — so this only has to be longer than one cycle, not tuned to it. Ten is comfortably
     * that and still costs nothing.
     */
    private val HANDOVER_WINDOW = 10

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /** Purity of the dominant species, as a percentage. */
    private fun purity(r: Mixture?): Int {
        if (r == null || r.isEmpty) return 0
        val d = r.dominant!!
        return (r[d] * 100 / r.total).toInt()
    }

    @Test
    fun `the concentrate leaves forward and the tailings leave downward`() {
        val grid = Grid(12, 10)
        val ore = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(40 * Capacity.PACKET_MASS)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Concentrator(grid.tile(3, 3), Direction.Right)                // covers x 2..4
        // Forward of the concentrator's product port, and below its tailings port.
        deck += fixtureStorage(grid.tile(7, 3), Direction.Right)          // input port at (6, 3)
        // Facing Down, so its input port is on top at (3, 7), under the end of the tailings run.
        // A tank has one input now, not two, so which way it faces is the whole of how you feed it.
        deck += fixtureStorage(grid.tile(3, 8), Direction.Down)
        joinRow(grid, rails, 4, 6, 3)   // product run
        joinCol(grid, rails, 3, 4, 7)   // tailings run
        var s = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .stocked(grid.tile(3, 3), ore)
        s = run(s, 800)

        val forward = s.buffers.resourceAt(grid.tile(7, 3))
        val below = s.buffers.resourceAt(grid.tile(3, 8))

        assertEquals(Species.Iron, forward!!.dominant, "the concentrate keeps the ore's own metal")
        // Against the *feed*, which is the claim: 41% ore in, appreciably richer out.
        //
        // Stated as a margin over the feed rather than a fixed figure, because the claim really is
        // comparative — this is the test for "concentrating does something", and the exact numbers
        // belong to the chain test below. It used to be loose for a worse reason: the figure moved
        // with the tick rate (65% at 1Hz, 79% at 120Hz) because `process` floors its impurity split
        // once per chunk and the chunk was a chunk-per-second-divided-by-the-rate. Rates are per
        // tick now, so the chunk is a constant and so is the result.
        val fed = purity(OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS))
        assertTrue(
            purity(forward) > fed + 20,
            "forward should be well above the ${fed}% it was fed, was ${purity(forward)}%",
        )
        assertTrue(
            forward[Species.Iron] * 100 / forward.total > below!![Species.Iron] * 100 / below.total,
            "forward must be richer in iron than the tailings",
        )
    }

    /**
     * What three passes of the concentrator do to a packet of the standard ore body — the oracle for
     * the end of a three-stage chain, computed from the same chemistry the machines use so that a
     * change to [process] or to the ore moves the expectation with it instead of breaking the test.
     */
    private fun threeStagePurity(): Int {
        var r = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS)
        repeat(3) { r = process(r, Concentrator(TileIndex(0), Direction.Right).efficiencyPermille).product }
        return purity(r)
    }

    /**
     * Before output buffers were capped, a concentrator with nowhere to put its tailings simply hoarded
     * them — one machine sat on 77kg — so connecting the waste side was effectively optional and the
     * direction contract meant nothing. Now it backs up, like every other blockage in the game.
     */
    @Test
    fun `a concentrator with nowhere to put its tailings backs up instead of hoarding them`() {
        val grid = Grid(28, 10)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 3, bodies = 8)
        val stages = listOf(6, 11, 16)
        for (x in stages) deck += Concentrator(grid.tile(x, 3), Direction.Right)   // no waste runs anywhere
        deck += fixtureStorage(grid.tile(21, 3), Direction.Right)
        joinRow(grid, rails, 4, 5, 3)
        joinRow(grid, rails, 7, 10, 3)
        joinRow(grid, rails, 12, 15, 3)
        joinRow(grid, rails, 17, 20, 3)
        var s = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), bodies = feed, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
        // 1800, not the 1200 this used to need: a rock cell is four tonnes now and the extractor
        // chews it at 250 kg a tick, so priming a three-stage chain takes about a third longer.
        s = run(s, 1800)

        for (x in stages) {
            val held = s.inStore(grid.tile(x, 3), BufferRole.Waste)?.total ?: 0L
            assertTrue(
                held <= MACHINE_OUTPUT_CAP + Capacity.PACKET_MASS,
                "stage at $x is hoarding ${held}g of tailings; the cap is $MACHINE_OUTPUT_CAP",
            )
        }
        assertEquals(
            s.extractedMass,
            s.inTransitMass + s.ventedMass,
            "and a stalled chain still conserves mass",
        )
    }
}
