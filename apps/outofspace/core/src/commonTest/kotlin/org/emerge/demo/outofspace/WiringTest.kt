package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.SignalNetworks
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        for (x in lo..hi) if (wires[grid.index(x, y)] == null) wires[grid.index(x, y)] = Segment(Conduit.Signal)
        for (x in lo until hi) {
            val a = grid.index(x, y)
            val b = grid.index(x + 1, y)
            wires[a] = wires[a]!!.joinedTo(Direction.Right)
            wires[b] = wires[b]!!.joinedTo(Direction.Left)
        }
    }

    private fun signalCol(grid: Grid, wires: Array<Segment?>, x: Int, fromY: Int, toY: Int) {
        val lo = minOf(fromY, toY)
        val hi = maxOf(fromY, toY)
        for (y in lo..hi) if (wires[grid.index(x, y)] == null) wires[grid.index(x, y)] = Segment(Conduit.Signal)
        for (y in lo until hi) {
            val a = grid.index(x, y)
            val b = grid.index(x, y + 1)
            wires[a] = wires[a]!!.joinedTo(Direction.Down)
            wires[b] = wires[b]!!.joinedTo(Direction.Up)
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
    fun `the loudest transmitter wins a shared network, so order cannot matter`() {
        val grid = Grid(6, 2)
        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(grid, wires, 0, 5, 0)
        val networks = SignalNetworks.derive(grid, Conduits.of(grid.size, Conduit.Signal to wires.toList()))

        val field = SignalField.build(networks) { raise ->
            raise(grid.index(0, 0), 200)
            raise(grid.index(3, 0), 900)
            raise(grid.index(5, 0), 400)
        }
        assertEquals(900, field.at(grid.index(1, 0)), "the run carries the loudest of the three, everywhere")
    }

    // ── Sensors ───────────────────────────────────────────────────────────────

    /** A tank at 0 and a sensor at 1 looking left at it, with a stub of wire under the sensor. */
    private fun tankAndSensor(fill: Long, wired: Boolean = true): VesselState {
        val grid = Grid(2, 1)
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to fill))
        val wires = arrayOfNulls<Segment>(grid.size)
        if (wired) wires[1] = Segment(Conduit.Signal)
        return VesselState(
            grid,
            listOf(Storage(Direction.Right, stored), Sensor(Direction.Left)),
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
        )
    }

    @Test
    fun `a sensor reports the fullness of the tile it faces`() {
        val s = run(tankAndSensor(Storage.CAP / 2), 1)
        assertEquals(500, s.signals.at(1), "a half-full tank should read 50%")
    }

    @Test
    fun `a sensor facing nothing reports nothing`() {
        val grid = Grid(2, 1)
        val wires = arrayOfNulls<Segment>(grid.size)
        wires[1] = Segment(Conduit.Signal)
        var s = VesselState(
            grid,
            listOf(null, Sensor(Direction.Left)),
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
        )
        s = run(s, 1)
        assertEquals(0, s.signals.at(1))
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
        assertEquals(0, s.signals.at(1))
    }

    // ── Throttling, not switching ─────────────────────────────────────────────

    @Test
    fun `half activation is half throughput`() {
        // Measured at the **belt side** of the extractor, not at the rock. Mass comes off a rock in
        // whole 3 kg cells, so `extractedMass` moves in lurches that say nothing about a rate; what
        // the throttle governs is how fast the cell in the jaws is ground into the buffer.
        fun groundInASecond(w: Wiring): Long {
            val grid = Grid(5, 5)
            val machines = arrayOfNulls<Machine>(grid.size)
            val feed = feedExtractor(grid, machines, 2, 2, wiring = w)
            val s = run(VesselState(grid, machines.toList(), bodies = feed), 4)
            return (s[grid.index(2, 2)] as Extractor).buffer.mass
        }
        // Four ticks of the extractor's own rate. It used to read `Capacity.PACKET_MASS`, which was
        // the same number only by the coincidence that a packet was four ticks' output — a
        // coincidence that died when the belt-load dropped to 100 kg. What the throttle governs is
        // the *rate*, so the rate is what the expectation is built from.
        val full = 4L * Extractor(Direction.Right).massPerTick
        assertEquals(full, groundInASecond(wiring(SignalSource.Always to 1000)))
        assertEquals(full / 2, groundInASecond(wiring(SignalSource.Always to 500)), "half the weight, half the ore")
        assertEquals(full / 4, groundInASecond(wiring(SignalSource.Always to 250)))
        assertEquals(0L, groundInASecond(wiring(SignalSource.Always to -1000)), "negative activation stops it")
    }

    @Test
    fun `a tank with no activation holds everything it has`() {
        // Track is inert -- it has no wiring and cannot be switched off, because it is plumbing. The
        // thing you switch off is the machine at the end of it, and a shut tank is a shut valve.
        val grid = Grid(12, 6)
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to 4 * Capacity.PACKET_MASS))
        val m = arrayOfNulls<Machine>(grid.size)
        m[grid.index(3, 3)] = Storage(Direction.Right, stored).copy(wiring = wiring()) as Storage
        m[grid.index(8, 3)] = Storage(Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 7, 3)
        var s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))

        s = run(s, RAIL_PERIOD * 8)
        assertEquals(4 * Capacity.PACKET_MASS, (s[grid.index(3, 3)] as Storage).contents?.mass, "it let go of nothing")
        assertEquals(0L, (4..7).sumOf { s.railAt(grid.index(it, 3))?.held?.mass ?: 0L }, "so the track is bare")
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
        val machines = arrayOfNulls<Machine>(grid.size)
        val feed = feedExtractor(
            grid, machines, 2, 3,
            wiring = wiring(SignalSource.Always to 1000, SignalSource.Wire to -1000),
            bodies = 4,
        )
        machines[grid.index(6, 3)] = Storage(Direction.Right)   // input port at (5, 3)
        machines[grid.index(6, 5)] = Sensor(Direction.Up)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 5, 3)
        // The run that joins the sensor to the extractor. Without it the two are strangers, which is
        // the whole difference between this and the six global channels it replaced.
        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(grid, wires, 2, 6, 5)
        signalCol(grid, wires, 2, 3, 5)
        var s = VesselState(
            grid, machines.toList(),
            conduits = Conduits.of(
                grid.size,
                Conduit.Rail to rails.toList(),
                Conduit.Signal to wires.toList(),
            ),
            bodies = feed,
        )

        // Throttling begins on the very first tick — fullness is continuous, so there is no grace
        // period. What matters is the *shape*: the rate falls away as the tank fills.
        //
        // Measured as **what has been ground out of the extractor**: everything taken off a rock,
        // less the cell still in the jaws. Neither `extractedMass` nor "mass aboard" would do — a
        // bite moves 3 kg in one tick and the throttle has no say in it, so both of them step in
        // lurches that say nothing about a rate.
        fun ground(w: VesselState): Long =
            w.extractedMass - ((w[grid.index(2, 3)] as Extractor).input?.mass ?: 0L)
        val firstTenSeconds = ground(run(s, 40))
        assertTrue(firstTenSeconds > 7_000L, "barely throttled while nearly empty, got ${firstTenSeconds}g")

        // Long enough to fill the tank at belt rate, and then half again because the throttle
        // slows the last of it down. Derived rather than typed: filling a tank takes as many ticks
        // as it takes packets, and how big a packet is is a tuning dial.
        s = run(s, ticksToMove(Storage.CAP) * 3 / 2)
        val onTheWire = s.signals.at(grid.index(2, 3))
        assertTrue(onTheWire > 800, "the tank should be reading nearly full, got $onTheWire")

        val lateRate = ground(run(s, 40)) - ground(s)
        assertTrue(
            lateRate * 4 < firstTenSeconds,
            "should be throttled to a fraction of its early rate: ${firstTenSeconds}g then ${lateRate}g",
        )
        assertTrue((s[grid.index(6, 3)] as Storage).contents!!.mass <= Storage.CAP, "and it never overfills")
    }

    @Test
    fun `the starter vessel ships that same loop, working`() {
        val s = run(workingVessel(Grid(40, 28)), ticksToMove(Storage.CAP) * 3 / 2)
        // Found by its wiring rather than by coordinates — the vessel is fitted to its contents on
        // construction, so a written-down tile index would be a hostage to its layout. There is
        // exactly one machine aboard that reads a wire, and it is the demonstration extractor.
        val throttled = (0 until s.grid.size).filter { t ->
            s[t]?.wiring?.triggers(Action.Run)?.any { it.source == SignalSource.Wire } == true
        }
        assertEquals(1, throttled.size, "expected one wire-driven machine aboard, found ${throttled.size}")
        val onTheWire = s.signals.at(throttled.single())
        assertTrue(onTheWire > 800, "the demonstration storage should have nearly filled, wire reads $onTheWire")
        // And the main line is unaffected by it.
        assertTrue(s.stockpile[Form.IronIngot].total > 0L, "the refinery line still stores iron")
    }

    // ── Editing wiring ────────────────────────────────────────────────────────

    @Test
    fun `wiring edits add, change and remove terms`() {
        val grid = Grid(2, 1)
        val base = VesselState(grid, listOf(Extractor(Direction.Right), null))

        val added = run(base, 1, OutofspaceInput(listOf(Edit.Wire(0, Action.Run, 99, Trigger(SignalSource.Wire, -1000)))))
        assertEquals(2, added[0]!!.wiring.triggers(Action.Run).size, "a slot past the end appends")

        val changed = run(added, 1, OutofspaceInput(listOf(Edit.Wire(0, Action.Run, 1, Trigger(SignalSource.Wire, 500)))))
        assertEquals(Trigger(SignalSource.Wire, 500), changed[0]!!.wiring.triggers(Action.Run)[1])

        val removed = run(changed, 1, OutofspaceInput(listOf(Edit.Wire(0, Action.Run, 1, null))))
        assertEquals(1, removed[0]!!.wiring.triggers(Action.Run).size)
    }

    @Test
    fun `a freshly placed machine is wired to ALWAYS so it simply works`() {
        // Room for the whole footprint: a place that would hang off the grid is refused outright.
        val grid = Grid(8, 6)
        val at = grid.index(3, 3)
        var s = VesselState(grid, List(grid.size) { null })
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(at, MachineKind.Extractor, Direction.Right))))
        assertEquals(listOf(Trigger(SignalSource.Always, 1000)), s[at]!!.wiring.triggers(Action.Run))
    }

    @Test
    fun `wiring survives rotation`() {
        val grid = Grid(2, 1)
        val wired = Extractor(Direction.Right).withWiring(wiring(SignalSource.Wire to 750))
        var s = VesselState(grid, listOf(wired, null))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(0))))
        assertEquals(Direction.Down, (s[0] as Extractor).facing)
        assertEquals(listOf(Trigger(SignalSource.Wire, 750)), s[0]!!.wiring.triggers(Action.Run))
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    /**
     * [upstream] at (3, 3) feeding a tank at (7, 3), with a short run of track between their ports.
     *
     * The track has to be there: ports connect to whatever segment shares their tile, so two
     * buildings touching each other are not connected — nothing joins them but rail.
     */
    private fun twoUp(upstream: Machine): VesselState {
        val g = Grid(12, 6)
        val m = arrayOfNulls<Machine>(g.size)
        val rails = arrayOfNulls<Segment>(g.size)
        m[g.index(3, 3)] = upstream                  // output port at (4, 3)
        m[g.index(7, 3)] = Storage(Direction.Right)  // input port at (6, 3)
        joinRow(g, rails, 4, 6, 3)
        return VesselState(g, m.toList(), conduits = Conduits.ofRails(rails.toList()))
    }

    @Test
    fun `a storage releases only while it is told to`() {
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to 5 * Capacity.PACKET_MASS))
        val shut = Storage(Direction.Right, stored).copy(wiring = wiring())
        // The downstream tank is what gets checked, not the stockpile: both tanks feed the stockpile
        // now, so its total is 5kg either way and would say nothing about whether the valve opened.
        val g = twoUp(shut).grid
        var s = run(twoUp(shut), 20)
        assertEquals(5 * Capacity.PACKET_MASS, (s[g.index(3, 3)] as Storage).contents!!.mass, "a closed valve holds everything")
        assertNull((s[g.index(7, 3)] as Storage).contents, "so nothing arrives downstream")

        var s2 = run(twoUp(Storage(Direction.Right, stored)), 20)
        assertEquals(5 * Capacity.PACKET_MASS, (s2[g.index(7, 3)] as Storage).contents!!.mass, "an open one drains into the next tank")
        assertNull((s2[g.index(3, 3)] as Storage).contents, "and empties itself doing it")
    }

    // ── Conservation still holds with all of it running ───────────────────────

    @Test
    fun `the world still never loses a unit of mass with sensors and wiring in play`() {
        var s = workingVessel(Grid(40, 28))
        repeat(240) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 89 == 0) {
                assertEquals(
                    s.extractedMass,
                    s.inTransitMass + s.ventedMass,
                    "tick ${s.tick}",
                )
            }
        }
    }

    @Test
    fun `two runs of the wired world are identical`() {
        fun digest(s: VesselState) = buildString {
            append(s.tick).append(s.extractedMass).append(s.ventedMass).append(s.stockpile)
            for (m in s.machines) append(m?.toString() ?: "-")
        }
        val grid = Grid(40, 28)
        assertEquals(digest(run(starterVessel(grid), 900)), digest(run(starterVessel(grid), 900)))
    }
}
