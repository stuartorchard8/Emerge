package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.contentsBreakdown
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **gauge**: a length of track that reports what goes over it.
 *
 * It used to be a machine, and being a machine meant it needed ports, which meant it cut a run in
 * two for no reason anyone wanted. As a property of a segment it is what its own documentation
 * always claimed — track that reads — and material passes at full speed without stopping.
 *
 * It exists because ore is a mixture and nothing else in the world says so out loud. A player can
 * watch a refinery run for an hour and never learn its ore is 41% iron unless something tells them.
 */
class GaugeTest {

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(grid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * A tank at (3,2) with track running out from under its output port, a gauge two tiles along,
     * and a receiving tank at the far end. The gauge reads whatever passes.
     */
    private fun line(carrying: Resource, channel: Channel = Channel.Amber): VesselState {
        val grid = Grid(14, 6)
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        m[grid.index(3, 2)] = Storage(Direction.Right, carrying)
        m[grid.index(10, 2)] = Storage(Direction.Right)
        joinRow(grid, rails, 4, 9, 2, mapOf(6 to channel))
        return VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
    }

    private fun gaugeOf(s: VesselState): Segment = s.railAt(s.grid.index(6, 2))!!

    @Test
    fun `a gauge reports the dominant species of what passes through`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY)
        val s = run(line(ore), 20)
        val gauge = gaugeOf(s)
        assertEquals(Species.Iron, gauge.lastDominant, "iron is the largest single component")
        assertEquals(410, gauge.lastPurity, "41% of the ore, not a majority of it")
        assertEquals(Form.Ore, gauge.lastForm)
    }

    @Test
    fun `the reading persists after the packet has gone, so an idle line still reads`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY)
        val s = run(line(ore), 120)
        val gauge = gaugeOf(s)
        assertEquals(null, gauge.held, "the packet moved on")
        assertEquals(410, gauge.lastPurity, "but the reading stayed")
        assertEquals(1_000L, s.stockpile.totalGrams, "and it was passed through, not consumed")
    }

    @Test
    fun `a gauge measures without taking, so it costs the line nothing`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(4_000L))
        val s = run(line(ore), 120)
        assertEquals(4_000L, s.stockpile.totalGrams, "every gram arrived at the far end")
        assertEquals(s.extractedGrams + 4_000L, s.inTransitGrams + s.ventedGrams, "and none went missing")
    }

    @Test
    fun `a gauge broadcasts purity on its channel`() {
        val pure = Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L))
        val s = run(line(pure, Channel.Violet), 20)
        assertEquals(1000, s.signals[Channel.Violet], "pure metal reads 100%")
    }

    @Test
    fun `retuning a gauge is an ordinary edit on the track, not on what is under it`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY)
        var s = line(ore)
        val at = s.grid.index(6, 2)
        s = OutofspaceReducer.reduce(
            OutofspaceConfig(grid = s.grid),
            s,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.SetChannel(at, Channel.Green)))),
        )
        assertEquals(Channel.Green, s.railAt(at)?.channel)
    }

    @Test
    fun `the starter plant's two gauges show the concentration happening`() {
        // This is the starter world's demonstration, asserted: raw ore in, concentrate out.
        val s = run(workingVessel(Grid(40, 28)), 600)
        val raw = s.signals[Channel.Amber]
        val concentrated = s.signals[Channel.Cyan]
        assertTrue(raw in 380..440, "the raw ore should read about 41%, got $raw")
        assertTrue(concentrated > raw + 200, "the concentrate should read far higher, got $concentrated")
    }

    @Test
    fun `a gauge conserves what passes through it`() {
        var s = workingVessel(Grid(40, 28))
        val cfg = OutofspaceConfig(grid = s.grid)
        repeat(360) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 91 == 0) {
                assertEquals(s.extractedGrams, s.inTransitGrams + s.ventedGrams, "tick ${s.tick}")
            }
        }
    }

    // ── The inspector's data ──────────────────────────────────────────────────

    @Test
    fun `a processor names each of its buffers separately`() {
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
    fun `machines that hold nothing report nothing rather than a phantom row`() {
        assertEquals(emptyList(), contentsBreakdown(Storage(Direction.Right)))
        assertEquals(emptyList(), contentsBreakdown(Smelter(Direction.Right)))
    }

    @Test
    fun `a packet on the track is readable, which is what the gauge was invented for`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY)
        // Conduit steps, not seconds: the line is one packet long and six tiles end to end, so the
        // window where anything is on it at all is a handful of advances wide. `12` used to be two
        // advances and is now twelve, by which time the lone packet is in the far tank.
        val s = run(line(ore), Bridge.STEP_TICKS * 3)
        val carried = (0 until s.grid.size).mapNotNull { s.railAt(it)?.held }
        assertTrue(carried.isNotEmpty(), "something should be on the line by now")
        assertTrue(carried.all { it is SolidPacket }, "and it is a solid, with a form to name")
    }
}
