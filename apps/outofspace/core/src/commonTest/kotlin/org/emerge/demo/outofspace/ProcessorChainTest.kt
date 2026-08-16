package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.process
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The processor's **port contract**, and the backpressure that gives it teeth.
 *
 * Concentrate leaves by the front port, tailings by the one in the floor. Getting these the wrong way
 * round would silently invert the whole refining game, and it is not something you can see by looking
 * at a running world — hence a test that measures purity on each side by name.
 *
 * These layouts are drawn to scale now that machines are rooms, with track threaded underneath them
 * to reach their ports. Each machine's output starts a **new run** — its input and its output are
 * different networks, and one continuous line under everything would put a processor's concentrate
 * back onto the pipe feeding its own input.
 */
class ProcessorChainTest {

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
    private fun purity(r: Resource?): Int {
        if (r == null || r.isEmpty) return 0
        val d = r.mixture.dominant!!
        return (r.mixture[d] * 100 / r.mass).toInt()
    }

    @Test
    fun `the concentrate leaves forward and the tailings leave downward`() {
        val grid = Grid(12, 10)
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(40 * Capacity.PACKET_MASS))
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        m[grid.tile(3, 3).index] = Processor(Direction.Right, input = ore)   // covers x 2..4
        // Forward of the processor's product port, and below its tailings port.
        m[grid.tile(7, 3).index] = Storage(Direction.Right)                 // input port at (6, 3)
        // Facing Down, so its input port is on top at (3, 7), under the end of the tailings run.
        // A tank has one input now, not two, so which way it faces is the whole of how you feed it.
        m[grid.tile(3, 8).index] = Storage(Direction.Down)
        joinRow(grid, rails, 4, 6, 3)   // product run
        joinCol(grid, rails, 3, 4, 7)   // tailings run
        var s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
        s = run(s, 800)

        val forward = (s[grid.tile(7, 3)] as Storage).contents
        val below = (s[grid.tile(3, 8)] as Storage).contents

        assertEquals(Species.Iron, forward!!.mixture.dominant, "the concentrate keeps the ore's own metal")
        // Against the *feed*, which is the claim: 41% ore in, appreciably richer out.
        //
        // Stated as a margin over the feed rather than a fixed figure, because the claim really is
        // comparative — this is the test for "concentrating does something", and the exact numbers
        // belong to the chain test below. It used to be loose for a worse reason: the figure moved
        // with the tick rate (65% at 1Hz, 79% at 120Hz) because `process` floors its impurity split
        // once per chunk and the chunk was a chunk-per-second-divided-by-the-rate. Rates are per
        // tick now, so the chunk is a constant and so is the result.
        val fed = purity(Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS)))
        assertTrue(
            purity(forward) > fed + 20,
            "forward should be well above the ${fed}% it was fed, was ${purity(forward)}%",
        )
        assertTrue(
            forward.mixture[Species.Iron] * 100 / forward.mass > below!!.mixture[Species.Iron] * 100 / below.mass,
            "forward must be richer in iron than the tailings",
        )
    }

    /**
     * What three passes of the concentrator do to a packet of the standard ore body — the oracle for
     * the end of a three-stage chain, computed from the same chemistry the machines use so that a
     * change to [process] or to the ore moves the expectation with it instead of breaking the test.
     */
    private fun threeStagePurity(): Int {
        var r = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS))
        repeat(3) { r = process(r, Processor(Direction.Right).efficiencyPermille).product }
        return purity(r)
    }

    /**
     * Before output buffers were capped, a processor with nowhere to put its tailings simply hoarded
     * them — one machine sat on 77kg — so connecting the waste side was effectively optional and the
     * direction contract meant nothing. Now it backs up, like every other blockage in the game.
     */
    @Test
    fun `a processor with nowhere to put its tailings backs up instead of hoarding them`() {
        val grid = Grid(28, 10)
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, m, 2, 3, bodies = 8)
        val stages = listOf(6, 11, 16)
        for (x in stages) m[grid.tile(x, 3).index] = Processor(Direction.Right)   // no waste runs anywhere
        m[grid.tile(21, 3).index] = Storage(Direction.Right)
        joinRow(grid, rails, 4, 5, 3)
        joinRow(grid, rails, 7, 10, 3)
        joinRow(grid, rails, 12, 15, 3)
        joinRow(grid, rails, 17, 20, 3)
        var s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()), bodies = feed)
        // 1800, not the 1200 this used to need: a rock cell is four tonnes now and the extractor
        // chews it at 250 kg a tick, so priming a three-stage chain takes about a third longer.
        s = run(s, 1800)

        for (x in stages) {
            val held = (s[grid.tile(x, 3)] as Processor).tailings?.mass ?: 0L
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
