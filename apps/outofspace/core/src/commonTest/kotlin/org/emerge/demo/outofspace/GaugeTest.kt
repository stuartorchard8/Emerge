package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.contentsBreakdown
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

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
    private fun line(carrying: Mixture): VesselState {
        val grid = Grid(14, 6)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Storage(grid.tile(3, 2), Direction.Right)
        deck += Storage(grid.tile(10, 2), Direction.Right)
        joinRow(grid, rails, 4, 9, 2)
        // The gauge is a building standing over the run now, not a flag on it — two acts.
        deck += Gauge(grid.tile(gaugeTileX, 2))
        // A stub of wire under the gauge, which is what its reading now goes onto. One tile is a
        // whole circuit — see [SignalNetworks] — so this is the least a gauge needs to be readable.
        val wires = arrayOfNulls<Segment>(grid.size)
        wires[gaugeTileX + 2 * grid.width] = Segment(Conduit.Signal, material = materialBefore(Conduit.Signal))
        return VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Rail to rails.toList(), Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(3, 2), carrying)
    }

    /** Where [line] puts its gauge, so a test can ask what that tile's circuit reads. */
    private val gaugeTileX = 6

    private fun gaugeOf(s: VesselState): Gauge = s.deck[s.grid.tile(6, 2)] as Gauge

    @Test
    fun `a gauge reports the dominant species of what passes through`() {
        val ore = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS)
        val s = run(line(ore), 3*RAIL_PERIOD)
        val gauge = gaugeOf(s)
        assertEquals(Species.Iron, gauge.lastDominant, "iron is the largest single component")
        assertEquals(410, gauge.lastPurity, "41% of the ore, not a majority of it")
    }

    @Test
    fun `the reading goes after the packet has gone, so an idle line is quiet`() {
        val ore = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS)
        val s = run(line(ore), 120*RAIL_PERIOD)
        val gauge = gaugeOf(s)
        assertEquals(null, s.onRail(s.grid.tile(6, 2)), "the packet moved on")
        assertEquals(0, gauge.lastPurity, "the reading disappeared")
        assertEquals(Capacity.PACKET_MASS, s.stockpile.totalMass, "and it was passed through, not consumed")
    }

    @Test
    fun `a gauge measures without taking, so it costs the line nothing`() {
        val ore = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(4 * Capacity.PACKET_MASS)
        val s = run(line(ore), 120*RAIL_PERIOD)
        assertEquals(4 * Capacity.PACKET_MASS, s.stockpile.totalMass, "every gram arrived at the far end")
        assertEquals(s.extractedMass + s.baselineCargoMass + 4 * Capacity.PACKET_MASS, s.inTransitMass + s.ventedMass + s.builtMass, "and none went missing")
    }

    @Test
    fun `a gauge puts its mass on the wire beneath it`() {
        val half = Mixture.of(Species.Iron to Capacity.PACKET_MASS/2, energy = 0)
        val s = run(line(half), 3*RAIL_PERIOD)
        assertEquals(500, s.signals.at(s.grid.tile(gaugeTileX, 2)), "half packet reads 50%")
    }

    /**
     * The half of the swap worth pinning: a gauge with nothing under it is not an error and not a
     * broadcast. It reports to whatever run passes beneath, and if none does, to nobody.
     */
    @Test
    fun `a gauge with no wire under it drives nothing`() {
        val pure = Mixture.of(Species.Iron to Capacity.PACKET_MASS, energy = 0)
        val grid = Grid(14, 6)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Storage(grid.tile(3, 2), Direction.Right)
        deck += Storage(grid.tile(10, 2), Direction.Right)
        joinRow(grid, rails, 4, 9, 2)
        // The gauge is a building standing over the run now, not a flag on it — two acts.
        deck += Gauge(grid.tile(gaugeTileX, 2))
        val bare = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size)).stocked(grid.tile(3, 2), pure)

        val s = run(bare, 20*RAIL_PERIOD)
        assertEquals(0, s.signals.networkCount, "no wire aboard means no circuits")
    }

    @Test
    fun `the starter plant's two gauges show the concentration happening`() {
        // This is the starter world's demonstration, asserted: raw ore in, concentrate out.
        //
        // ⚠️ **The highest reading each gauge ever showed, not the reading at the final tick.** A
        // gauge reports the lump *currently* under it and reads nothing between packets, so a single
        // sample is a coin toss on where the traffic happens to be — it passed for as long as it did
        // by luck. What the test means is "concentrate came past here at some point", and that is a
        // question about the whole run.
        //
        // ⚠️ **And long enough for the concentrate to arrive.** A concentrator takes two whole packets
        // before it starts (`Concentrator.CHARGE_MASS`), so the mill has to be fed twice over before it
        // ships anything at all and the first concentrate reaches the second gauge around t=1000.
        // At 600 ticks the plant is working perfectly and the gauge has simply not seen it yet.
        var s = workingVessel(Grid(40, 28))
        val cfg = OutofspaceConfig(initialGrid = s.grid)
        // Found by scanning rather than by coordinates: the vessel is fitted to its own contents on
        // construction, so a tile index written down here would be a hostage to its layout. The two
        // gauges on the main line are the first two in tile order, and that order is left-to-right.
        val gauges = s.grid.tiles.filter { s.deck[it] is Gauge }
        assertTrue(gauges.size >= 2, "the starter plant should ship two gauges, found ${gauges.size}")

        // ⚠️ **Stops as soon as it has its answer.** A tick of a 40x28 vessel is not cheap and the
        // bound is generous; running it out in full would put a ten-second test at twenty for no
        // extra evidence.
        val best = IntArray(gauges.size)
        var ticks = 0
        while (ticks < 1200 && best[1] <= best[0] + 200) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            ticks++
            for (i in gauges.indices) {
                val reading = (s.deck[gauges[i]] as? Gauge)?.lastPurity ?: 0
                if (reading > best[i]) best[i] = reading
            }
        }
        val raw = best[0]
        val concentrated = best[1]
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
                assertEquals(s.extractedMass + s.baselineCargoMass, s.inTransitMass + s.ventedMass + s.builtMass, "tick ${s.tick}")
            }
        }
    }

    // ── The inspector's data ──────────────────────────────────────────────────

    @Test
    fun `a concentrator names each of its buffers separately`() {
        val grid = Grid(5, 5)
        val centre = grid.tile(2, 2)
        val p = Concentrator(centre, Direction.Right)
        val buffers = BufferLayer.empty(grid.size)
        buffers.claimRoles(grid, p, centre)
        for ((role, resource) in listOf(
            BufferRole.Input to Mixture.of(Species.Iron to Capacity.PACKET_MASS, energy = 0),
            BufferRole.Inside to Mixture.of(Species.Iron to Capacity.PACKET_MASS * 10, energy = 0),
            BufferRole.Product to Mixture.of(Species.Iron to Capacity.PACKET_MASS / 2, energy = 0),
            BufferRole.Waste to Mixture.of(Species.Quartz to Capacity.PACKET_MASS * 3 / 10, energy = 0),
        )) buffers.put(bufferTile(grid, p, centre, role)!!, resource)
        val rows = contentsBreakdown(p, centre, grid, buffers)
        assertEquals(listOf("INPUT", "PROCESSING", "CONCENTRATE", "TAILINGS"), rows.map { it.first })
        assertEquals(Capacity.PACKET_MASS * 3 / 10, rows[3].second.total, "knowing which buffer is stuck is the whole point")
    }

    @Test
    fun `machines that hold nothing report nothing rather than a phantom row`() {
        val grid = Grid(9, 9)
        val centre = grid.tile(4, 4)
        assertEquals(emptyList(), contentsBreakdown(Storage(centre, Direction.Right), centre, grid, BufferLayer.empty(grid.size)))
        assertEquals(emptyList(), contentsBreakdown(Concentrator(centre, Direction.Right), centre, grid, BufferLayer.empty(grid.size)))
    }

    @Test
    fun `a packet on the track is readable, which is what the gauge was invented for`() {
        val ore = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS)
        // Conduit steps, not seconds: the line is one packet long and six tiles end to end, so the
        // window where anything is on it at all is a handful of advances wide. `12` used to be two
        // advances and is now twelve, by which time the lone packet is in the far tank.
        val s = run(line(ore), RAIL_PERIOD * 3)
        val carried = s.grid.tiles.mapNotNull { s.onRail(it) }
        assertTrue(carried.isNotEmpty(), "something should be on the line by now")
        assertTrue(carried.all { !it.isEmpty }, "and every lump on it is carrying something")
    }
}
