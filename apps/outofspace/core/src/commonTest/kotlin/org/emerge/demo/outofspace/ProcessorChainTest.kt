package org.emerge.demo.outofspace

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
        m[grid.index(3, 8)] = Storage(Direction.Right)                 // input port at (3, 7)
        for (x in 4..6) rails[grid.index(x, 3)] = Segment(Conduit.Rail)   // product run
        for (y in 4..7) rails[grid.index(3, y)] = Segment(Conduit.Rail)   // tailings run
        var s = VesselState(grid, m.toList(), rails = rails.toList())
        s = run(s, 60 * 200)

        val forward = (s[grid.index(7, 3)] as Storage).contents
        val below = (s[grid.index(3, 8)] as Storage).contents

        assertEquals(Species.Iron, forward!!.mixture.dominant, "the concentrate keeps the ore's own metal")
        assertTrue(purity(forward) > 70, "forward should be concentrated, was ${purity(forward)}%")
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
            for (y in 4..7) rails[grid.index(x, y)] = Segment(Conduit.Rail)   // its tailings run
        }
        m[grid.index(21, 3)] = Storage(Direction.Right)
        // One short run per stage, from an output port to the next input port.
        for (x in 3..5) rails[grid.index(x, 3)] = Segment(Conduit.Rail)
        for (x in 7..10) rails[grid.index(x, 3)] = Segment(Conduit.Rail)
        for (x in 12..15) rails[grid.index(x, 3)] = Segment(Conduit.Rail)
        for (x in 17..20) rails[grid.index(x, 3)] = Segment(Conduit.Rail)
        var s = VesselState(grid, m.toList(), rails = rails.toList())
        s = run(s, 60 * 300)

        val purities = stages.map { purity((s[grid.index(it, 3)] as Processor).product) }
        assertEquals(listOf(75, 100, 100), purities, "each stage should be cleaner than the last: $purities")
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
        for (x in 3..5) rails[grid.index(x, 3)] = Segment(Conduit.Rail)
        for (x in 7..10) rails[grid.index(x, 3)] = Segment(Conduit.Rail)
        for (x in 12..15) rails[grid.index(x, 3)] = Segment(Conduit.Rail)
        for (x in 17..20) rails[grid.index(x, 3)] = Segment(Conduit.Rail)
        var s = VesselState(grid, m.toList(), rails = rails.toList())
        s = run(s, 60 * 300)

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
