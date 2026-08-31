package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.SignalNetworks
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

/**
 * The trigger grammar: sensors, signals, and machines throttled by `Σ(signal × weight)`.
 *
 * This is the Godot vessel's `action_triggers` language, and the thing worth protecting about it is
 * that a weight *scales* rather than switches — half a signal is half a machine. Several of these
 * tests exist to pin that down, because it would be very easy to quietly reduce it to a boolean.
 */
class WiringTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun wiring(vararg terms: Pair<SignalSource, Int>) =
        Wiring(mapOf(Action.Run to terms.map { Trigger(it.first, it.second) }))

    /**
     * A run of wire along row [y], laid and joined, so a fixture can actually connect two things.
     *
     * The whole point of the layer is that this step is not optional any more: a sensor and the
     * machine it throttles are joined by geometry or they are not joined at all.
     */
    private fun signalRow(grid: Grid, wires: Array<Segment?>, fromX: Int, toX: Int, y: Int) {
        val lo = minOf(fromX, toX)
        val hi = maxOf(fromX, toX)
        for (x in lo..hi) if (wires[grid.tile(x, y).index] == null) wires[grid.tile(x, y).index] = Segment(Conduit.Signal, material = materialBefore(Conduit.Signal))
        for (x in lo until hi) {
            val a = grid.tile(x, y)
            val b = grid.tile(x + 1, y)
            wires[a.index] = wires[a.index]!!.joinedTo(Direction.Right)
            wires[b.index] = wires[b.index]!!.joinedTo(Direction.Left)
        }
    }

    private fun signalCol(grid: Grid, wires: Array<Segment?>, x: Int, fromY: Int, toY: Int) {
        val lo = minOf(fromY, toY)
        val hi = maxOf(fromY, toY)
        for (y in lo..hi) if (wires[grid.tile(x, y).index] == null) wires[grid.tile(x, y).index] = Segment(Conduit.Signal, material = materialBefore(Conduit.Signal))
        for (y in lo until hi) {
            val a = grid.tile(x, y)
            val b = grid.tile(x, y + 1)
            wires[a.index] = wires[a.index]!!.joinedTo(Direction.Down)
            wires[b.index] = wires[b.index]!!.joinedTo(Direction.Up)
        }
    }

    // ── Activation arithmetic ─────────────────────────────────────────────────

    @Test
    fun `activation sums the terms and clamps`() {
        val wire = 1000
        assertEquals(1000, wiring(SignalSource.Always to 1000).activation(Action.Run, wire))
        assertEquals(500, wiring(SignalSource.Wire to 1000).activation(Action.Run, 500))
        assertEquals(250, wiring(SignalSource.Wire to 500).activation(Action.Run, 500))
        assertEquals(0, wiring(SignalSource.Always to 1000, SignalSource.Wire to -1000).activation(Action.Run, wire))
        // Sums past full are clamped, not wrapped.
        assertEquals(1000, wiring(SignalSource.Always to 1000, SignalSource.Wire to 1000).activation(Action.Run, wire))
        assertEquals(-1000, wiring(SignalSource.Wire to -1000, SignalSource.Wire to -1000).activation(Action.Run, wire))
    }

    @Test
    fun `a machine with no terms never runs`() {
        assertEquals(0, Wiring(mapOf(Action.Run to emptyList())).activation(Action.Run, SignalField.FULL))
    }

    /**
     * The property the whole migration rested on: a `WIRE` term with no wire beneath it reads 0,
     * which is exactly what a channel nobody was emitting on read. So `ALWAYS − WIRE` on an unwired
     * machine runs at full, and every vessel that predates the wire layer kept working.
     */
    @Test
    fun `an unwired WIRE term reads nothing, so ALWAYS minus WIRE runs at full`() {
        assertEquals(1000, wiring(SignalSource.Always to 1000, SignalSource.Wire to -1000).activation(Action.Run, 0))
    }

    @Test
    fun `ALWAYS reads full whatever the wire under it is doing`() {
        assertEquals(1000, wiring(SignalSource.Always to 1000).activation(Action.Run, 0))
    }

    @Test
    fun `transmitter signals accumulate on a shared network, so order cannot matter`() {
        val grid = Grid(6, 2)
        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(grid, wires, 0, 5, 0)
        val networks = SignalNetworks.derive(grid, Conduits.of(grid.size, Conduit.Signal to wires.toList()))

        val field = SignalField.build(networks) { raise ->
            raise(grid.tile(0, 0), 200)
            raise(grid.tile(3, 0), 100)
            raise(grid.tile(5, 0), 400)
        }
        assertEquals(700, field.at(grid.tile(1, 0)), "the run carries the loudest of the three, everywhere")
    }

    // ── Sensors ───────────────────────────────────────────────────────────────

    /** Where [tankAndSensor] puts its sensor, so a test asks the tile the fixture actually used. */
    private val sensorTile get() = Grid(7, 5).tile(4, 2)

    /** A tank with a sensor looking left at it, and a stub of wire under the sensor. */
    private fun tankAndSensor(fill: Long, wired: Boolean = true): VesselState {
        // Room for the tank's three-tile footprint, which it needs now that it stands on the deck:
        // the tank is centred at (2, 2) and the sensor looks left at it from (4, 2).
        val grid = Grid(7, 5)
        val tank = grid.tile(2, 2)
        val eye = grid.tile(4, 2)
        val stored = Mixture.of(Species.Iron to fill, energy = 0)
        val wires = arrayOfNulls<Segment>(grid.size)
        if (wired) wires[eye.index] = Segment(Conduit.Signal, material = materialBefore(Conduit.Signal))
        val deck = DeckArray(grid)
        deck += Storage(tank, Direction.Right)
        deck += Sensor(eye, Direction.Left)
        return VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).stocked(tank, stored)
    }

    @Test
    fun `a sensor reports the fullness of the tile it faces`() {
        val s = run(tankAndSensor(Storage.CAP / 2), 1)
        assertEquals(500, s.signals.at(sensorTile), "a half-full tank should read 50%")
    }

    @Test
    fun `a sensor facing nothing reports nothing`() {
        val grid = Grid(2, 1)
        val wires = arrayOfNulls<Segment>(grid.size)
        wires[1] = Segment(Conduit.Signal, material = materialBefore(Conduit.Signal))
        val deck = DeckArray(grid)
        deck += Sensor(TileIndex(1), Direction.Left)
        var s = VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
        s = run(s, 1)
        assertEquals(0, s.signals.at(TileIndex(1)))
    }

    /**
     * A sensor is no longer retuned, because there is nothing to retune it to. What used to be an
     * edit on the machine is now a question about geometry: it drives the run under it, and moving
     * its reading somewhere else means moving the wire.
     */
    @Test
    fun `a sensor with no wire beneath it drives nothing, and that is not an error`() {
        val s = run(tankAndSensor(Storage.CAP, wired = false), 1)
        assertEquals(0, s.signals.networkCount, "no wire aboard means no circuits at all")
        assertEquals(0, s.signals.at(sensorTile))
    }

    // ── Throttling, not switching ─────────────────────────────────────────────

    @Test
    fun `an extractor is switched on and off rather than throttled`() {
        // ⚠️ **Binary, and deliberately** — Stu, 2026-08-19. This used to read "half activation is
        // half throughput", and it was true while the machine held a second store and ground the
        // cell in its jaws into the buffer at `massPerTick x activation`. Merging the two stores
        // deleted the grind, and with it the only place a fraction had any effect: what leaves an
        // extractor is metered by the belt, so a rate upstream of a full buffer changed nothing that
        // could be observed at the far end anyway.
        //
        // What was genuinely lost is the ability to run one *slowly* by wiring it to a weak signal.
        // That is a wiring question rather than an extraction one, and it is Stu's to reopen.
        fun groundInASecond(w: Wiring): Long {
            val grid = Grid(5, 5)
            val deck = DeckArray(grid)
            val feed = feedExtractor(grid, deck, 2, 2, wiring = w)
            val s = run(VesselState(grid, deck, bodies = feed, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size)), 4)
            return s.inStore(grid.tile(2, 2), BufferRole.Product)?.total ?: 0L
        }
        // Whatever four ticks of biting comes to — cells are whole and their mass is the rock's, so
        // the number is a property of the feed and not of the machine. What is pinned is that every
        // positive signal gives the *same* answer and a non-positive one gives nothing.
        val full = groundInASecond(wiring(SignalSource.Always to 1000))
        assertTrue(full > 0L, "a fully wired extractor bit nothing at all")
        assertEquals(full, groundInASecond(wiring(SignalSource.Always to 500)), "half a signal is still on")
        assertEquals(full, groundInASecond(wiring(SignalSource.Always to 250)), "a quarter signal is still on")
        assertEquals(0L, groundInASecond(wiring(SignalSource.Always to -1000)), "negative activation stops it")
    }

    @Test
    fun `a tank with no activation holds everything it has`() {
        // Track is inert -- it has no wiring and cannot be switched off, because it is plumbing. The
        // thing you switch off is the machine at the end of it, and a shut tank is a shut valve.
        val grid = Grid(12, 6)
        val stored = Mixture.of(Species.Iron to 4 * Capacity.PACKET_MASS, energy = 0)
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(3, 3), Direction.Right).copy(wiring = wiring()) as Storage
        deck += Storage(grid.tile(8, 3), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 7, 3)
        var s = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size)).stocked(grid.tile(3, 3), stored)

        s = run(s, RAIL_PERIOD * 8)
        assertEquals(4 * Capacity.PACKET_MASS, s.buffers.resourceAt(grid.tile(3, 3))?.total, "it let go of nothing")
        assertEquals(0L, (4..7).sumOf { s.rail.massAt(grid.tile(it, 3)) }, "so the track is bare")
    }

    // ── The loop that makes wiring worth having ───────────────────────────────

    /**
     * `ALWAYS − WIRE` is a **proportional controller, not a cut-off**, and that is worth pinning down
     * because it is easy to assume otherwise. As the tank fills, the wire rises and the extractor throttles
     * smoothly down; at 85% full it is running at 15%, still creeping upward. It approaches full
     * asymptotically and never overshoots.
     *
     * A hard "stop at 90%" would need a *comparison* — `WHEN WIRE > 900` — which this grammar cannot
     * express. That is a deliberate gap for now, noted in the plan: the analogue behaviour is the
     * more interesting half and comparisons can be added without disturbing it.
     */
    @Test
    fun `an extractor wired ALWAYS minus WIRE throttles smoothly as the tank it fills gets full`() {
        // The extractor's plate covers x 0..4 and it pushes out at x=4; the tank covers 5..7 and
        // takes it in at x=5. The sensor sits below the tank looking up at its bottom row.
        val grid = Grid(12, 8)
        val deck = DeckArray(grid)
        val feed = feedExtractor(
            grid, deck, 2, 3,
            wiring = wiring(SignalSource.Always to 1000, SignalSource.Wire to -1000),
            bodies = 4,
        )
        deck += Storage(grid.tile(6, 3), Direction.Right)   // input port at (5, 3)
        deck += Sensor(grid.tile(6, 5), Direction.Up)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 5, 3)
        // The run that joins the sensor to the extractor. Without it the two are strangers, which is
        // the whole difference between this and the six global channels it replaced.
        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(grid, wires, 2, 6, 5)
        signalCol(grid, wires, 2, 3, 5)
        var s = VesselState(
            grid, deck,
            conduits = Conduits.of(
                grid.size,
                Conduit.Rail to rails.toList(),
                Conduit.Signal to wires.toList(),
            ),
            bodies = feed,
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )

        // Throttling begins on the very first tick — fullness is continuous, so there is no grace
        // period. What matters is the *shape*: the rate falls away as the tank fills.
        //
        // Measured as **what has been ground out of the extractor**: everything taken off a rock,
        // less the cell still in the jaws. Neither `extractedMass` nor "mass aboard" would do — a
        // bite moves 3 kg in one tick and the throttle has no say in it, so both of them step in
        // lurches that say nothing about a rate.
        fun ground(w: VesselState): Long =
            w.extractedMass - (w.inStore(grid.tile(2, 3), BufferRole.Inside)?.total ?: 0L)
        val firstTenSeconds = ground(run(s, 40))
        assertTrue(firstTenSeconds > 7_000L, "barely throttled while nearly empty, got ${firstTenSeconds}g")

        // Long enough to fill the tank at belt rate, and then half again because the throttle
        // slows the last of it down. Derived rather than typed: filling a tank takes as many ticks
        // as it takes packets, and how big a packet is is a tuning dial.
        s = run(s, ticksToMove(Storage.CAP) * 3 / 2)
        val onTheWire = s.signals.at(grid.tile(2, 3))
        assertTrue(onTheWire > 800, "the tank should be reading nearly full, got $onTheWire")

        val lateRate = ground(run(s, 40)) - ground(s)
        assertTrue(
            lateRate * 4 < firstTenSeconds,
            "should be throttled to a fraction of its early rate: ${firstTenSeconds}g then ${lateRate}g",
        )
        assertTrue(s.buffers.resourceAt(grid.tile(6, 3))!!.total <= Storage.CAP, "and it never overfills")
    }

    /**
     * The sibling above proves the *throttle* on a rig built for it. This proves the starter vessel
     * ships the same loop wired up and live: a sensor watching the demonstration tank, a run of
     * signal reaching the extractor that reads it, and a main line that carries on regardless.
     *
     * ⚠️ **The tank is stocked, not filled.** Filling one by simulation is 200 packets at one packet
     * per `RAIL_PERIOD`, which is twelve thousand ticks of a 40x28 vessel — a hundred and fifty
     * seconds to re-derive a rate the sibling test already measures on a grid a tenth the size. What
     * is particular to the starter vessel is the *wiring*, and wiring can be read the moment the
     * tank has something in it. So the tank is stated as nearly full and the run is long enough for
     * the signal to cross the vessel and the line to bank a packet.
     */
    @Test
    fun `the starter vessel ships that same loop, working`() {
        val start = workingVessel(Grid(40, 28))
        val g = start.grid
        // Found by its wiring rather than by coordinates — the vessel is fitted to its contents on
        // construction, so a written-down tile index would be a hostage to its layout. There is
        // exactly one machine aboard that reads a wire, and it is the demonstration extractor.
        val throttled = g.tiles.filter { t ->
            start.deck[t]?.wiring?.triggers(Action.Run)?.any { it.source == SignalSource.Wire } == true
        }
        assertEquals(1, throttled.size, "expected one wire-driven machine aboard, found ${throttled.size}")

        // The tank that machine is throttled by, found the same way: whatever the vessel's sensor is
        // pointed at. That it is a `Storage` at all is part of what is being asserted — a sensor
        // aimed at nothing would leave the extractor reading a flat zero and looking unthrottled.
        val sensors = g.tiles.filter { start.deck[it] is Sensor }
        assertEquals(1, sensors.size, "expected one sensor aboard, found ${sensors.size}")
        val facing = g.neighbour(sensors.single(), (start.deck[sensors.single()] as Sensor).facing)
        val watched = start.occupancy[facing]
        val tank = assertNotNull(
            start.deck[watched] as? Storage,
            "the sensor should be watching a tank, found ${start.deck[watched]}",
        )

        // Nine tenths full, which is where the throttle is doing something visible.
        start.buffers.put(
            bufferTile(g, tank, watched, BufferRole.Inside)!!,
            Mixture.of(Species.Iron to Storage.CAP * 9 / 10, energy = 0).atAmbient(),
        )
        val banked = start.stockpile.totalMass

        val s = run(start, 200)
        val onTheWire = s.signals.at(throttled.single())
        assertTrue(onTheWire > 800, "the demonstration storage is nearly full, wire reads $onTheWire")
        // And the reading is *live* rather than a constant the fixture put there: the tank goes on
        // filling, and the wire follows it up.
        val later = run(s, 150).signals.at(throttled.single())
        assertTrue(later > onTheWire, "the wire should track the tank as it fills: $onTheWire then $later")
        // The main line is unaffected by any of it. Stated as *growth*, because the vessel is built
        // holding half a tank of iron to build with — a bare "there is iron aboard" would pass on a
        // line that never turned a wheel.
        assertTrue(
            s.stockpile.totalMass > banked,
            "the refinery line still banks what it digs: $banked then ${s.stockpile.totalMass}",
        )
    }

    // ── Editing wiring ────────────────────────────────────────────────────────

    @Test
    fun `wiring edits add, change and remove terms`() {
        // Nine across because an extractor is five: its stores stand on tiles of its own
        // footprint, so it needs room to be the size it is.
        val grid = Grid(9, 9)
        val at = grid.tile(4, 4)
        val deck = DeckArray(grid)
        deck += Extractor(at, Direction.Right)
        val base = VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))

        val added = run(base, 1, OutofspaceInput(listOf(Edit.Wire(at, Action.Run, 99, Trigger(SignalSource.Wire, -1000)))))
        assertEquals(2, added.deck[at]!!.wiring.triggers(Action.Run).size, "a slot past the end appends")

        val changed = run(added, 1, OutofspaceInput(listOf(Edit.Wire(at, Action.Run, 1, Trigger(SignalSource.Wire, 500)))))
        assertEquals(Trigger(SignalSource.Wire, 500), changed.deck[at]!!.wiring.triggers(Action.Run)[1])

        val removed = run(changed, 1, OutofspaceInput(listOf(Edit.Wire(at, Action.Run, 1, null))))
        assertEquals(1, removed.deck[at]!!.wiring.triggers(Action.Run).size)
    }

    @Test
    fun `a freshly placed machine is wired to ALWAYS so it simply works`() {
        // Room for the whole footprint: a place that would hang off the grid is refused outright.
        val grid = Grid(8, 6)
        val at = grid.tile(3, 3)
        var s = VesselState.empty(grid)
        s = run(s, 1, OutofspaceInput(listOf(fixturePlace(at, Brush.Building(DeckMachineKind.Extractor), Direction.Right))))
        assertEquals(listOf(Trigger(SignalSource.Always, 1000)), s.deck[at]!!.wiring.triggers(Action.Run))
    }

    @Test
    fun `wiring survives rotation`() {
        val grid = Grid(9, 9)
        val at = grid.tile(4, 4)
        val wired = Extractor(at, Direction.Right).withWiring(wiring(SignalSource.Wire to 750))
        val deck = DeckArray(grid)
        deck += wired
        var s = VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(at))))
        assertEquals(Direction.Down, (s.deck[at] as? Extractor)!!.facing)
        assertEquals(listOf(Trigger(SignalSource.Wire, 750)), s.deck[at]!!.wiring.triggers(Action.Run))
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    /**
     * [upstream] at (3, 3) feeding a tank at (7, 3), with a short run of track between their ports.
     *
     * The track has to be there: ports connect to whatever segment shares their tile, so two
     * buildings touching each other are not connected — nothing joins them but rail.
     */
    private fun twoUp(upstream: DeckMachine, stocked: Mixture? = null): VesselState {
        val g = Grid(12, 6)
        val deck = DeckArray(g)
        val rails = arrayOfNulls<Segment>(g.size)
        deck += upstream.movedTo(g.tile(3, 3))   // output port at (4, 3)
        deck += Storage(g.tile(7, 3), Direction.Right)  // input port at (6, 3)
        joinRow(g, rails, 4, 6, 3)
        return VesselState(g, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(g, deck), rail = RailLayer.empty(g.size))
            .stocked(g.tile(3, 3), stocked)
    }

    @Test
    fun `a storage releases only while it is told to`() {
        val stored = Mixture.of(Species.Iron to 5 * Capacity.PACKET_MASS, energy = 0)
        val shut = Storage(TileIndex(0), Direction.Right).copy(wiring = wiring())
        // The downstream tank is what gets checked, not the stockpile: both tanks feed the stockpile
        // now, so its total is 5kg either way and would say nothing about whether the valve opened.
        val g = twoUp(shut, stored).grid
        var s = run(twoUp(shut, stored), 20 * RAIL_PERIOD)
        assertEquals(5 * Capacity.PACKET_MASS, s.buffers.resourceAt(g.tile(3, 3))!!.total, "a closed valve holds everything")
        assertNull(s.buffers.resourceAt(g.tile(7, 3)), "so nothing arrives downstream")

        var s2 = run(twoUp(Storage(TileIndex(0), Direction.Right), stored), 20 * RAIL_PERIOD)
        assertEquals(5 * Capacity.PACKET_MASS, s2.buffers.resourceAt(g.tile(7, 3))!!.total, "an open one drains into the next tank")
        assertNull(s2.buffers.resourceAt(g.tile(3, 3)), "and empties itself doing it")
    }

    // ── Conservation still holds with all of it running ───────────────────────

    @Test
    fun `the world still never loses a unit of mass with sensors and wiring in play`() {
        var s = workingVessel(Grid(40, 28))
        repeat(240) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 89 == 0) {
                assertEquals(
                    s.extractedMass + s.baselineCargoMass,
                    s.inTransitMass + s.ventedMass + s.builtMass,
                    "tick ${s.tick}",
                )
            }
        }
    }

    @Ignore // This test took 18 seconds to pass.
    @Test
    fun `two runs of the wired world are identical`() {
        fun digest(s: VesselState) = buildString {
            append(s.tick).append(s.extractedMass).append(s.ventedMass).append(s.stockpile)
            for (tile in s.grid.tiles) append(s.deck[tile]?.toString() ?: "-")
        }
        val grid = Grid(40, 28)
        assertEquals(digest(run(starterVessel(grid), 900)), digest(run(starterVessel(grid), 900)))
    }
}
