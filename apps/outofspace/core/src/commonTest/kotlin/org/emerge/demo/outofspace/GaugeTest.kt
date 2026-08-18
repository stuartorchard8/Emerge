package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.contentsBreakdown
import org.emerge.demo.outofspace.world.machine.DeckArray
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
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * A tank at (3,2) with track running out from under its output port, a gauge two tiles along,
     * and a receiving tank at the far end. The gauge reads whatever passes.
     */
    private fun line(carrying: Resource): VesselState {
        val grid = Grid(14, 6)
        val m = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Storage(grid.tile(3, 2), Direction.Right)
        deck += Storage(grid.tile(10, 2), Direction.Right)
        joinRow(grid, rails, 4, 9, 2, setOf(6))
        // A stub of wire under the gauge, which is what its reading now goes onto. One tile is a
        // whole circuit — see [SignalNetworks] — so this is the least a gauge needs to be readable.
        val wires = arrayOfNulls<Segment>(grid.size)
        wires[gaugeTileX + 2 * grid.width] = Segment(Conduit.Signal)
        return VesselState(
            grid,
            m.toList(),
            deck,
            conduits = Conduits.of(grid.size, Conduit.Rail to rails.toList(), Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forMachines(grid, m.toList()), rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(3, 2), carrying)
    }

    /** Where [line] puts its gauge, so a test can ask what that tile's circuit reads. */
    private val gaugeTileX = 6

    private fun gaugeOf(s: VesselState): Segment = s.railAt(s.grid.tile(6, 2))!!

    @Test
    fun `a gauge reports the dominant species of what passes through`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS))
        val s = run(line(ore), 3*RAIL_PERIOD)
        val gauge = gaugeOf(s)
        assertEquals(Species.Iron, gauge.lastDominant, "iron is the largest single component")
        assertEquals(410, gauge.lastPurity, "41% of the ore, not a majority of it")
        assertEquals(Form.Ore, gauge.lastForm)
    }

    @Test
    fun `the reading goes after the packet has gone, so an idle line is quiet`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS))
        val s = run(line(ore), 120*RAIL_PERIOD)
        val gauge = gaugeOf(s)
        assertEquals(null, s.onRail(s.grid.tile(6, 2)), "the packet moved on")
        assertEquals(0, gauge.lastPurity, "the reading disappeared")
        assertEquals(Capacity.PACKET_MASS, s.stockpile.totalMass, "and it was passed through, not consumed")
    }

    @Test
    fun `a gauge measures without taking, so it costs the line nothing`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(4 * Capacity.PACKET_MASS))
        val s = run(line(ore), 120*RAIL_PERIOD)
        assertEquals(4 * Capacity.PACKET_MASS, s.stockpile.totalMass, "every gram arrived at the far end")
        assertEquals(s.extractedMass + 4 * Capacity.PACKET_MASS, s.inTransitMass + s.ventedMass, "and none went missing")
    }

    @Test
    fun `a gauge puts its mass on the wire beneath it`() {
        val half = Resource(Form.IronIngot, Mixture.of(Species.Iron to Capacity.PACKET_MASS/2, energy = 0))
        val s = run(line(half), 3*RAIL_PERIOD)
        assertEquals(500, s.signals.at(s.grid.tile(gaugeTileX, 2)), "half packet reads 50%")
    }

    /**
     * The half of the swap worth pinning: a gauge with nothing under it is not an error and not a
     * broadcast. It reports to whatever run passes beneath, and if none does, to nobody.
     */
    @Test
    fun `a gauge with no wire under it drives nothing`() {
        val pure = Resource(Form.IronIngot, Mixture.of(Species.Iron to Capacity.PACKET_MASS, energy = 0))
        val grid = Grid(14, 6)
        val m = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Storage(grid.tile(3, 2), Direction.Right)
        deck += Storage(grid.tile(10, 2), Direction.Right)
        joinRow(grid, rails, 4, 9, 2, setOf(6))
        val bare = VesselState(grid, m.toList(), deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forMachines(grid, m.toList()), rail = RailLayer.empty(grid.size)).stocked(grid.tile(3, 2), pure)

        val s = run(bare, 20*RAIL_PERIOD)
        assertEquals(0, s.signals.networkCount, "no wire aboard means no circuits")
    }

    @Test
    fun `the starter plant's two gauges show the concentration happening`() {
        // This is the starter world's demonstration, asserted: raw ore in, concentrate out.
        val s = run(workingVessel(Grid(40, 28)), 600)
        // The starter plant's two gauges, read through the tiles they sit on rather than through
        // names — which is the whole difference the wire layer makes. Neither has a run under it in
        // the shipped vessel, so the readings come off the segments themselves.
        // Found by scanning rather than by coordinates: the vessel is fitted to its own contents on
        // construction, so a tile index written down here would be a hostage to its layout. The two
        // gauges on the main line are the first two in tile order, and that order is left-to-right.
        val readings = s.grid.tiles
            .mapNotNull { t -> s.railAt(t)?.takeIf { it.isGauge }?.let { t to it.lastPurity } }
        assertTrue(readings.size >= 2, "the starter plant should ship two gauges, found ${readings.size}")
        val raw = readings[0].second
        val concentrated = readings[1].second
        assertTrue(raw in 380..440, "the raw ore should read about 41%, got $raw")
        assertTrue(concentrated > raw + 200, "the concentrate should read far higher, got $concentrated")
    }

    @Test
    fun `a gauge conserves what passes through it`() {
        var s = workingVessel(Grid(40, 28))
        val cfg = OutofspaceConfig(initialGrid = s.grid)
        repeat(360) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 91 == 0) {
                assertEquals(s.extractedMass, s.inTransitMass + s.ventedMass, "tick ${s.tick}")
            }
        }
    }

    // ── The inspector's data ──────────────────────────────────────────────────

    @Test
    fun `a processor names each of its buffers separately`() {
        val grid = Grid(5, 5)
        val centre = grid.tile(2, 2)
        val p = Processor(Direction.Right)
        val buffers = BufferLayer.empty(grid.size)
        buffers.claimRoles(grid, p, centre)
        for ((role, resource) in listOf(
            BufferRole.Input to Resource(Form.Ore, Mixture.of(Species.Iron to Capacity.PACKET_MASS, energy = 0)),
            BufferRole.Inside to Resource(Form.Ore, Mixture.of(Species.Iron to Capacity.PACKET_MASS * 10, energy = 0)),
            BufferRole.Product to Resource(Form.Ore, Mixture.of(Species.Iron to Capacity.PACKET_MASS / 2, energy = 0)),
            BufferRole.Waste to Resource(Form.Ore, Mixture.of(Species.Quartz to Capacity.PACKET_MASS * 3 / 10, energy = 0)),
        )) buffers.put(bufferTile(grid, p, centre, role)!!, resource)
        val rows = contentsBreakdown(p, centre, grid, buffers)
        assertEquals(listOf("INPUT", "PROCESSING", "CONCENTRATE", "TAILINGS"), rows.map { it.first })
        assertEquals(Capacity.PACKET_MASS * 3 / 10, rows[3].second.mass, "knowing which buffer is stuck is the whole point")
    }

    @Test
    fun `machines that hold nothing report nothing rather than a phantom row`() {
        val grid = Grid(9, 9)
        val centre = grid.tile(4, 4)
        assertEquals(emptyList(), contentsBreakdown(Storage(centre, Direction.Right), centre, grid, BufferLayer.empty(grid.size)))
        assertEquals(emptyList(), contentsBreakdown(Smelter(Direction.Right), centre, grid, BufferLayer.empty(grid.size)))
    }

    @Test
    fun `a packet on the track is readable, which is what the gauge was invented for`() {
        val ore = Resource(Form.Ore, OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS))
        // Conduit steps, not seconds: the line is one packet long and six tiles end to end, so the
        // window where anything is on it at all is a handful of advances wide. `12` used to be two
        // advances and is now twelve, by which time the lone packet is in the far tank.
        val s = run(line(ore), RAIL_PERIOD * 3)
        val carried = s.grid.tiles.mapNotNull { s.onRail(it) }
        assertTrue(carried.isNotEmpty(), "something should be on the line by now")
        assertTrue(carried.all { it.form == Form.Ore }, "and it is a solid, with a form to name")
    }
}
