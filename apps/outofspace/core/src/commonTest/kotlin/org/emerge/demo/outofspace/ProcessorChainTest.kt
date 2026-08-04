package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Vent
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

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(grid = state.grid)
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
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(40_000L))
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        m[grid.index(3, 3)] = Processor(Direction.Right, input = ore)   // covers x 2..4
        // Forward of the processor's product port, and below its tailings port.
        m[grid.index(7, 3)] = Storage(Direction.Right)                 // input port at (6, 3)
        // Facing Down, so its input port is on top at (3, 7), under the end of the tailings run.
        // A tank has one input now, not two, so which way it faces is the whole of how you feed it.
        m[grid.index(3, 8)] = Storage(Direction.Down)
        joinRow(grid, rails, 4, 6, 3)   // product run
        joinCol(grid, rails, 3, 4, 7)   // tailings run
        var s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
        s = run(s, 800)

        val forward = (s[grid.index(7, 3)] as Storage).contents
        val below = (s[grid.index(3, 8)] as Storage).contents

        assertEquals(Species.Iron, forward!!.mixture.dominant, "the concentrate keeps the ore's own metal")
        // Against the *feed*, which is the claim: 41% ore in, appreciably richer out.
        //
        // Stated as a margin over the feed rather than a fixed figure, because the claim really is
        // comparative — this is the test for "concentrating does something", and the exact numbers
        // belong to the chain test below. It used to be loose for a worse reason: the figure moved
        // with the tick rate (65% at 1Hz, 79% at 120Hz) because `process` floors its impurity split
        // once per chunk and the chunk was a chunk-per-second-divided-by-the-rate. Rates are per
        // tick now, so the chunk is a constant and so is the result.
        val fed = purity(Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY))
        assertTrue(
            purity(forward) > fed + 20,
            "forward should be well above the ${fed}% it was fed, was ${purity(forward)}%",
        )
        assertTrue(
            forward.mixture[Species.Iron] * 100 / forward.mass > below!!.mixture[Species.Iron] * 100 / below.mass,
            "forward must be richer in iron than the tailings",
        )
    }

    @Test
    fun `chained straight through, purity climbs at every stage`() {
        // Miner -> processor -> processor -> processor -> tank, each processor venting its tailings
        // through the floor. Every building abuts the next; the ports line up without conveyors.
        val grid = Grid(28, 10)
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        m[grid.index(2, 3)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        val stages = listOf(6, 11, 16)
        for (x in stages) {
            m[grid.index(x, 3)] = Processor(Direction.Right)
            m[grid.index(x, 7)] = Vent()
            joinCol(grid, rails, x, 4, 7)   // its tailings run
        }
        m[grid.index(21, 3)] = Storage(Direction.Right)
        // One short run per stage, from an output port to the next input port.
        joinRow(grid, rails, 3, 5, 3)
        joinRow(grid, rails, 7, 10, 3)
        joinRow(grid, rails, 12, 15, 3)
        joinRow(grid, rails, 17, 20, 3)
        var s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
        s = run(s, 1200)

        // Exact figures again, now that a rate is stated per tick and a stage's concentration is
        // therefore a fact about the machine rather than about the clock. These were briefly
        // loosened to a property when the same chain measured 75/100/100 at 60Hz and 66/88/100 at
        // 4Hz; that spread is gone, so the test can go back to saying what it actually expects.
        val purities = stages.map { purity((s[grid.index(it, 3)] as Processor).product) }
        assertEquals(listOf(66, 88, 100), purities, "41% ore, cleaner at every stage")
        assertEquals(100, purity((s[grid.index(21, 3)] as Storage).contents), "and the far end is pure metal")
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
        m[grid.index(2, 3)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        val stages = listOf(6, 11, 16)
        for (x in stages) m[grid.index(x, 3)] = Processor(Direction.Right)   // no waste runs anywhere
        m[grid.index(21, 3)] = Storage(Direction.Right)
        joinRow(grid, rails, 3, 5, 3)
        joinRow(grid, rails, 7, 10, 3)
        joinRow(grid, rails, 12, 15, 3)
        joinRow(grid, rails, 17, 20, 3)
        var s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
        s = run(s, 1200)

        for (x in stages) {
            val held = (s[grid.index(x, 3)] as Processor).tailings?.mass ?: 0L
            assertTrue(
                held <= MACHINE_OUTPUT_CAP + 1_000L,
                "stage at $x is hoarding ${held}g of tailings; the cap is $MACHINE_OUTPUT_CAP",
            )
        }
        assertEquals(
            s.minedGrams,
            s.inTransitGrams + s.ventedGrams,
            "and a stalled chain still conserves mass",
        )
    }
}
