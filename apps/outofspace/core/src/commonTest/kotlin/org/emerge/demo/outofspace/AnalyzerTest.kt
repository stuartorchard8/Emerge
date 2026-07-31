package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Analyzer
import org.emerge.demo.outofspace.world.Belt
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Node
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.contentsBreakdown
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The analyzer and the inspector — the two things that make a world of mixtures readable.
 *
 * Until these existed you could watch a refinery for an hour and never learn what was in the ore,
 * which is exactly how a correct sim comes to look like a broken one.
 */
class AnalyzerTest {

    private val cfg = OutofspaceConfig(grid = Grid(8, 3))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    @Test
    fun `an analyzer reports the dominant species of what passes through`() {
        val grid = Grid(3, 1)
        val ore = SolidPacket(Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY))
        var s = VesselState(
            grid,
            listOf(Belt(Direction.Right, listOf(ore, null, null, null)), Analyzer(Direction.Right), Node()),
        )
        s = run(s, Belt.STEP_TICKS * 2)

        val analyzer = s[1] as Analyzer
        assertEquals(Species.Iron, analyzer.lastDominant, "iron is the largest single component")
        assertEquals(410, analyzer.lastPurity, "41% of the ore, not a majority of it")
        assertEquals(Form.Ore, analyzer.lastForm)
    }

    @Test
    fun `the reading persists after the packet has gone, so an idle line still reads`() {
        val grid = Grid(3, 1)
        val ore = SolidPacket(Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY))
        var s = VesselState(
            grid,
            listOf(Belt(Direction.Right, listOf(ore, null, null, null)), Analyzer(Direction.Right), Node()),
        )
        s = run(s, Belt.STEP_TICKS * 20)

        val analyzer = s[1] as Analyzer
        assertNull(analyzer.holding, "the packet moved on")
        assertEquals(410, analyzer.lastPurity, "but the reading stayed")
        assertEquals(1_000L, s.stockpile.totalGrams, "and it was passed through, not consumed")
    }

    @Test
    fun `an analyzer broadcasts purity on its channel`() {
        val grid = Grid(3, 1)
        val pure = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        var s = VesselState(
            grid,
            listOf(
                Belt(Direction.Right, listOf(pure, null, null, null)),
                Analyzer(Direction.Right, Channel.Violet),
                Node(),
            ),
        )
        s = run(s, Belt.STEP_TICKS * 2)
        assertEquals(1000, s.signals[Channel.Violet], "pure metal reads 100%")
    }

    @Test
    fun `analyzers either side of a processor show the concentration happening`() {
        // This is the starter world's demonstration, asserted: raw ore in, concentrate out.
        val s = run(starterVessel(Grid(24, 14)), 60 * 120)
        val raw = s.signals[Channel.Amber]
        val concentrated = s.signals[Channel.Cyan]
        assertTrue(raw in 380..440, "the raw ore should read about 41%, got $raw")
        assertTrue(concentrated > raw + 200, "the concentrate should read far higher, got $concentrated")
    }

    @Test
    fun `an analyzer conserves what passes through it`() {
        var s = starterVessel(Grid(24, 14))
        repeat(60 * 90) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 91 == 0) {
                assertEquals(
                    s.minedGrams,
                    s.inTransitGrams + s.stockpile.totalGrams + s.ventedGrams,
                    "tick ${s.tick}",
                )
            }
        }
    }

    // ── The inspector's data ──────────────────────────────────────────────────

    @Test
    fun `a processor's buffers are broken out separately, not summed`() {
        val p = Processor(
            Direction.Right,
            input = Resource(Form.Ore, Mixture.of(Species.Iron to 1_000L)),
            product = Resource(Form.Ore, Mixture.of(Species.Iron to 500L)),
            tailings = Resource(Form.Ore, Mixture.of(Species.Silica to 300L)),
        )
        val rows = contentsBreakdown(p)
        assertEquals(listOf("INPUT", "CONCENTRATE", "TAILINGS"), rows.map { it.first })
        assertEquals(300L, rows[2].second.mass, "knowing which buffer is stuck is the whole point")
    }

    @Test
    fun `a belt reports each occupied slot, so a jam is countable in the readout too`() {
        val packet = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val belt = Belt(Direction.Right, listOf(packet, null, packet, null))
        assertEquals(listOf("SLOT 1", "SLOT 3"), contentsBreakdown(belt).map { it.first })
    }

    @Test
    fun `machines that hold nothing report nothing rather than a phantom row`() {
        assertEquals(emptyList(), contentsBreakdown(Node()))
        assertEquals(emptyList(), contentsBreakdown(Storage(Direction.Right)))
        assertEquals(emptyList(), contentsBreakdown(Smelter(Direction.Right)))
    }
}
