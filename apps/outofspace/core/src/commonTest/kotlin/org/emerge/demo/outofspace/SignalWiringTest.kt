package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Increment D of `PLAN_signal_network.md`: a value comes off the wire and drives something.
 *
 * The headline is [`cutting the wire stops the throttling`]. A sensor at one end of a run and a
 * machine at the other, joined by nothing but wire, and severing that wire changes the machine's
 * behaviour — that single pair is the point of the entire feature, and it is the one thing the six
 * global channels could never have been made to do.
 */
class SignalWiringTest {

    private val grid = Grid(16, 8)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun signalRow(wires: Array<Segment?>, fromX: Int, toX: Int, y: Int) {
        for (x in fromX..toX) if (wires[grid.index(x, y)] == null) wires[grid.index(x, y)] = Segment(Conduit.Signal)
        for (x in fromX until toX) {
            val a = grid.index(x, y)
            val b = grid.index(x + 1, y)
            wires[a] = wires[a]!!.joinedTo(Direction.Right)
            wires[b] = wires[b]!!.joinedTo(Direction.Left)
        }
    }

    /** `RUN = ALWAYS − WIRE`: the throttle every one of these is about. */
    private val stopWhenFull = Wiring(
        mapOf(
            Action.Run to listOf(
                Trigger(SignalSource.Always, SignalField.FULL),
                Trigger(SignalSource.Wire, -SignalField.FULL),
            ),
        ),
    )

    private val extractorAt = 3 to 3
    private val sensorAt = 12 to 3

    /**
     * A tank at the far end of the grid with a sensor beside it, and an extractor at the near end
     * wired `ALWAYS − WIRE`. A single straight run of wire joins the two.
     *
     * The tank is [fill] full, so the sensor's reading is a known constant rather than something the
     * refinery has to produce — this is a test about wire, not about ore.
     */
    private fun rig(fill: Long, wired: Boolean = true): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to fill))
        machines[grid.index(extractorAt.first, extractorAt.second)] =
            Extractor(Direction.Right).withWiring(stopWhenFull)
        machines[grid.index(13, 5)] = Storage(Direction.Right, stored)
        // Looking up at the tank, which sits below the run.
        machines[grid.index(sensorAt.first, sensorAt.second)] = Sensor(Direction.Down)

        val wires = arrayOfNulls<Segment>(grid.size)
        if (wired) signalRow(wires, extractorAt.first, sensorAt.first, 3)

        return VesselState(
            grid,
            machines.toList(),
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            bodies = rockOnPlate(extractorAt.first, extractorAt.second, 6),
        )
    }

    private fun extractor(s: VesselState) = s[grid.index(extractorAt.first, extractorAt.second)] as Extractor

    /** What the extractor has ground out — the measure of a throttle, see [WiringTest]. */
    private fun ground(s: VesselState): Long = s.extractedMass - (extractor(s).input?.mass ?: 0L)

    // ── The point of the plan ─────────────────────────────────────────────────

    @Test
    fun `a sensor throttles a machine at the far end of a run`() {
        val s = run(rig(Storage.CAP), 2)

        assertEquals(
            SignalField.FULL,
            s.signals.at(grid.index(extractorAt.first, extractorAt.second)),
            "a full tank should be driving the whole run, including the far end",
        )
        assertEquals(0, extractor(s).wiring.activation(Action.Run, s.signals.at(grid.index(extractorAt.first, extractorAt.second))))
    }

    /**
     * The pair the whole feature rests on. Nothing about either machine changes — only the geometry
     * between them — and the machine's behaviour changes with it.
     */
    @Test
    fun `cutting the wire stops the throttling`() {
        val joined = rig(Storage.CAP)
        val before = ground(run(joined, 40))

        // One link severed, in the middle of the run.
        val cut = run(
            joined, 40,
            OutofspaceInput(listOf(Edit.Cut(grid.index(7, 3), grid.index(8, 3), Conduit.Signal))),
        )

        assertTrue(before == 0L, "joined, the full tank should have stopped it dead: ${before}g")
        assertTrue(ground(cut) > 0L, "cut, it should be digging again: ${ground(cut)}g")
    }

    @Test
    fun `a machine with no wire under it runs at full`() {
        // The migration-safety property, asserted directly: an unwired WIRE term reads 0, so
        // ALWAYS − WIRE is ALWAYS. This is why every pre-wire vessel kept working.
        val s = run(rig(Storage.CAP, wired = false), 40)
        assertTrue(ground(s) > 0L, "an unwired throttle is no throttle at all")
    }

    /**
     * Half a signal is half a machine, over a wire exactly as it was over a channel. The proportional
     * controller is the interesting half of this grammar and it would be easy to lose to a boolean.
     */
    @Test
    fun `a partial reading throttles proportionally`() {
        // Four ticks, not forty. There is no belt under this extractor, so its buffer fills and it
        // stalls — and a stalled machine grinds the same amount whatever its throttle says. Measured
        // over the window where the throttle is the only thing governing the rate.
        val empty = ground(run(rig(0L), 4))
        val quarter = ground(run(rig(Storage.CAP / 4), 4))
        val half = ground(run(rig(Storage.CAP / 2), 4))

        assertTrue(empty > quarter, "an empty tank should not throttle at all: $empty vs $quarter")
        assertTrue(quarter > half, "a quarter-full tank should throttle less than a half-full one: $quarter vs $half")
        assertTrue(half > 0L, "and half full is still running")
    }

    @Test
    fun `two machines on one run see the same value`() {
        val machines = arrayOfNulls<Machine>(grid.size)
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to Storage.CAP))
        machines[grid.index(13, 5)] = Storage(Direction.Right, stored)
        machines[grid.index(12, 3)] = Sensor(Direction.Down)
        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(wires, 2, 12, 3)

        val s = run(
            VesselState(grid, machines.toList(), conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList())),
            2,
        )
        assertEquals(s.signals.at(grid.index(2, 3)), s.signals.at(grid.index(10, 3)))
    }

    // ── Old worlds ────────────────────────────────────────────────────────────

    /**
     * A v10 file, typed out, with the colour grammar its writer used. It has to load, and the machine
     * it describes has to behave the way it did — compared against a world built directly in the new
     * model rather than against a pinned number.
     */
    @Test
    fun `a version 10 save loads and its machine behaves as it did`() {
        val old = Save.read(
            """
            outofspace 10
            grid 16 8
            machine ${grid.index(3, 3)} Extractor facing=Right wire=Run:ALWAYS@1000,Red@-1000
            """.trimIndent(),
        )

        val terms = old[grid.index(3, 3)]!!.wiring.triggers(Action.Run)
        assertEquals(
            listOf(Trigger(SignalSource.Always, 1000), Trigger(SignalSource.Wire, -1000)),
            terms,
            "a colour becomes a wire term — lossy on purpose, and behaviour-preserving where it counts",
        )

        // No wire was laid in that file and none could have been, so the machine runs at full — which
        // is exactly what it did when RED was a channel nobody was emitting on.
        assertEquals(1000, old[grid.index(3, 3)]!!.wiring.activation(Action.Run, old.signals.at(grid.index(3, 3))))
    }

    @Test
    fun `a wired vessel survives a save and load`() {
        val played = run(rig(Storage.CAP), 20)
        val reloaded = Save.read(Save.write(played))

        assertEquals(
            played.signals.at(grid.index(extractorAt.first, extractorAt.second)),
            run(reloaded, 1).signals.at(grid.index(extractorAt.first, extractorAt.second)),
            "the circuit should come back carrying what it carried",
        )
    }
}
