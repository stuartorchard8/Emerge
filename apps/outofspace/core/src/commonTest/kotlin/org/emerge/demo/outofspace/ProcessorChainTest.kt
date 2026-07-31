package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The processor's **direction contract**, and the backpressure that gives it teeth.
 *
 * Concentrate leaves by the facing side, tailings by the side clockwise of it. Getting these the
 * wrong way round would silently invert the whole refining game, and it is not something you can see
 * by looking at a running world — hence a test that measures purity on each side by name.
 */
class ProcessorChainTest {

    private val cfg = OutofspaceConfig(grid = Grid(8, 3))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
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
    fun `the concentrate leaves forward and the tailings leave to the side`() {
        val grid = Grid(3, 2)
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(40_000L))
        val m = arrayOfNulls<Machine>(6)
        m[0] = Processor(Direction.Right, input = ore)
        m[1] = Storage(Direction.Up)      // forward of the processor, facing nothing so it holds
        m[3] = Storage(Direction.Down)    // below it: the side clockwise of Right
        var s = VesselState(grid, m.toList())
        s = run(s, 60 * 200)

        val forward = (s[1] as Storage).contents
        val side = (s[3] as Storage).contents

        assertEquals(Species.Iron, forward!!.mixture.dominant, "the concentrate keeps the ore's own metal")
        assertTrue(purity(forward) > 70, "forward should be concentrated, was ${purity(forward)}%")
        assertTrue(
            forward.mixture[Species.Iron] * 100 / forward.mass > side!!.mixture[Species.Iron] * 100 / side.mass,
            "forward must be richer in iron than the tailings",
        )
    }

    @Test
    fun `chained straight through, purity climbs at every stage`() {
        // Miner -> processor -> processor -> processor -> storage, each with a vent for its tailings.
        val grid = Grid(6, 2)
        val m = arrayOfNulls<Machine>(12)
        m[0] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        for (x in 1..3) {
            m[x] = Processor(Direction.Right)
            m[x + 6] = Vent()
        }
        m[4] = Storage(Direction.Up)
        var s = VesselState(grid, m.toList())
        s = run(s, 60 * 300)

        val stages = (1..3).map { purity((s[it] as Processor).product) }
        assertEquals(listOf(75, 100, 100), stages, "each stage should be cleaner than the last: $stages")
        assertEquals(100, purity((s[4] as Storage).contents), "and the far end should be pure metal")
    }

    /**
     * Before output buffers were capped, a processor with nowhere to put its tailings simply hoarded
     * them — one machine sat on 77kg — so connecting the waste side was effectively optional and the
     * direction contract meant nothing. Now it backs up, like every other blockage in the game.
     */
    @Test
    fun `a processor with nowhere to put its tailings backs up instead of hoarding them`() {
        val grid = Grid(6, 2)
        val m = arrayOfNulls<Machine>(12)
        m[0] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        for (x in 1..3) m[x] = Processor(Direction.Right)   // no vents anywhere
        m[4] = Storage(Direction.Up)
        var s = VesselState(grid, m.toList())
        s = run(s, 60 * 300)

        for (x in 1..3) {
            val held = (s[x] as Processor).tailings?.mass ?: 0L
            assertTrue(
                held <= MACHINE_OUTPUT_CAP + 1_000L,
                "stage $x is hoarding ${held}g of tailings; the cap is $MACHINE_OUTPUT_CAP",
            )
        }
        assertEquals(
            s.minedGrams,
            s.inTransitGrams + s.ventedGrams,
            "and a stalled chain still conserves mass",
        )
    }
}
