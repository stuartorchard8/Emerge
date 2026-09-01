package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

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
        for (x in fromX..toX) if (wires[grid.tile(x, y).index] == null) wires[grid.tile(x, y).index] = Segment(Conduit.Signal, material = materialBefore(Conduit.Signal))
        for (x in fromX until toX) {
            val a = grid.tile(x, y)
            val b = grid.tile(x + 1, y)
            wires[a.index] = wires[a.index]!!.joinedTo(Direction.Right)
            wires[b.index] = wires[b.index]!!.joinedTo(Direction.Left)
        }
    }

    /** `RUN = ALWAYS − WIRE`: the switch every one of these is about. */
    private val stopWhenFull = Wiring(
        mapOf(
            Action.Run to listOf(
                Trigger(SignalSource.Always),
                Trigger(SignalSource.Wire, negated = true),
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
        val deck = DeckArray(grid)
        val stored = Mixture.of(Species.Iron to fill, energy = 0)
        deck += Extractor(grid.tile(extractorAt.first, extractorAt.second), Direction.Right)
            .withWiring(stopWhenFull)
        deck += fixtureStorage(grid.tile(13, 5), Direction.Right)
        // Looking up at the tank, which sits below the run.
        deck += fixtureSensor(grid.tile(sensorAt.first, sensorAt.second), Direction.Down)

        val wires = arrayOfNulls<Segment>(grid.size)
        if (wired) signalRow(wires, extractorAt.first, sensorAt.first, 3)

        return run(VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(13, 5), stored).gridAtWorldOrigin(), 1).copy(
            // Place rock after signals have propagated for a tick
            bodies = rockOnPlate(extractorAt.first, extractorAt.second, 6),
        )
    }

    private fun extractor(s: VesselState) = s.deck[grid.tile(extractorAt.first, extractorAt.second)] as? Extractor

    /** What the extractor has ground out — the measure of a throttle, see [WiringTest]. */
    private fun ground(s: VesselState): Long =
        s.extractedMass - (s.inStore(grid.tile(extractorAt.first, extractorAt.second), BufferRole.Inside)?.total ?: 0L)

    // ── The point of the plan ─────────────────────────────────────────────────

    @Test
    fun `a sensor throttles a machine at the far end of a run`() {
        val s = run(rig(Storage.CAP), 2)

        assertEquals(
            SignalField.FULL,
            s.signals.at(grid.tile(extractorAt.first, extractorAt.second)),
            "a full tank should be driving the whole run, including the far end",
        )
        assertFalse(extractor(s)!!.wiring.isOn(Action.Run, s.signals.at(grid.tile(extractorAt.first, extractorAt.second))))
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
            OutofspaceInput(listOf(Edit.Cut(grid.tile(7, 3), grid.tile(8, 3), Conduit.Signal))),
        )

        assertEquals(0L, before, "joined, the full tank should have stopped it dead: ${before}g")
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
     * The same rig with a **thruster** at the near end instead of an extractor, and propellant in it.
     *
     * ⚠️ The subject changed, not the question. This file is about *wire*, and an extractor stopped
     * being able to demonstrate a proportional throttle when its two buffers became one — what it
     * used to throttle was the grinding of the cell in its jaws, and there is no grinding now, so
     * its activation is binary (see `WiringTest`). Every other machine still reads its activation as
     * a rate, so the grammar is pinned on one of those rather than let go.
     *
     * Measured as **propellant consumed** rather than product made: a motor's output is exhaust, and
     * how much matter it worked through is the rate the throttle governs either way.
     *
     * ⚠️ It was a mineral vaporizer until that machine was deleted (see `PLAN_ambient_chemistry.md`).
     * A thruster is the nearest thing left: the same `throttled(massPerTick, …)`, so the same
     * question is being asked of the same code.
     */
    private fun burnerRig(fill: Long): VesselState {
        val deck = DeckArray(grid)
        val stored = Mixture.of(Species.Iron to fill, energy = 0)
        val at = grid.tile(extractorAt.first, extractorAt.second)
        // A thruster used as a generic wired burner, so it has to be *on the wire*: by default a
        // motor answers the pilot and ignores its wiring entirely — see [ThrusterControl].
        deck += Thruster(at, Direction.Right, control = ThrusterControl.Wire).withWiring(stopWhenFull)
        deck += fixtureStorage(grid.tile(13, 5), Direction.Right)
        deck += fixtureSensor(grid.tile(sensorAt.first, sensorAt.second), Direction.Down)

        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(wires, extractorAt.first, sensorAt.first, 3)

        return VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
            .stocked(grid.tile(13, 5), stored)
            // Plenty, so the machine is never short of something to work on: the throttle is the
            // only thing that may govern the rate over the window measured.
            .stocked(at, Mixture.of(Species.Water to Storage.CAP, energy = 0), BufferRole.Input)
    }

    /** How much the machine worked through: what is gone from its input store. */
    private fun burned(s: VesselState): Long =
        Storage.CAP - (s.inStore(grid.tile(extractorAt.first, extractorAt.second), BufferRole.Input)?.total ?: 0L)

    /**
     * ⛔ **`a partial reading throttles proportionally` stood here, and it was lost to a boolean on
     * purpose.** Its comment warned that the proportional controller was the interesting half of the
     * grammar and would be easy to lose by accident. It was not lost by accident: a sensor reports a
     * verdict and a term carries a sign, so there is no partial reading left to throttle on — see
     * [Wiring] for what was traded and why. Analogue survives inside machines, where a thruster on
     * [ThrusterControl.Flight] is still throttled per motor by the flight solver.
     *
     * What is pinned in its place is the contract that replaced it, asked of the same rig over the
     * same window: a machine wired `ALWAYS − WIRE` works flat out or not at all.
     */
    @Test
    fun `a reading stops the machine outright rather than throttling it`() {
        // Four ticks, not forty: over a longer window the machine's own buffer empties and it
        // stalls, and a stalled machine works the same amount whatever it was told. Measured over
        // the window where the wire is the only thing governing the rate.
        val empty = burned(run(burnerRig(0L), 4))
        val quarter = burned(run(burnerRig(Storage.CAP / 4), 4))
        val half = burned(run(burnerRig(Storage.CAP / 2), 4))

        assertTrue(empty > 0L, "a quiet wire leaves the machine running: $empty")
        assertTrue(empty > quarter, "and anything in the tank stops it: $empty vs $quarter")
        assertEquals(quarter, half, "a fuller tank stops it no harder: $quarter vs $half")
        // ⚠️ **Stopped dead, one tick late.** A signal is built from the state the tick before it,
        // so the machine works the tick the packet lands and none after — a quarter of the window
        // measured here. That lag is the network's, not the throttle's: what it is *not* is a rate
        // that scales with the reading, which is what would show up as a half or a third instead.
        assertEquals(empty, quarter * 4, "exactly one tick of work before the wire caught up")
    }

    @Test
    fun `two machines on one run see the same value`() {
        val deck = DeckArray(grid)
        val stored = Mixture.of(Species.Iron to Storage.CAP, energy = 0)
        deck += fixtureStorage(grid.tile(13, 5), Direction.Right)
        deck += fixtureSensor(grid.tile(12, 3), Direction.Down)
        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(wires, 2, 12, 3)

        val s = run(
            VesselState(grid, deck, conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
                .stocked(grid.tile(13, 5), stored),
            2,
        )
        assertEquals(s.signals.at(grid.tile(2, 3)), s.signals.at(grid.tile(10, 3)))
    }

    // ── Old worlds ────────────────────────────────────────────────────────────
    /**
     * ⚠️ **The reloaded world is on a different lattice, and the reading has to be looked up there.**
     *
     * `Save.read` sets [org.emerge.demo.outofspace.world.GRID_PAD], which this hand-built fixture
     * never had — the extractor sits three tiles from the left edge, so the first tick after a load
     * grows that edge and moves the origin. A tile index written down against `grid` therefore means
     * a different place afterwards, which is exactly what [VesselState.frameShiftX] exists to
     * correct and what this now applies.
     *
     * ⛔ It used to read the raw index and pass — **because of a bug**. `remapped` carried the old
     * `SignalField` through the resize with its old-grid indexing intact, so the stale lookup agreed
     * with the stale map. Re-deriving the map on the new grid is what exposed the test.
     */
    @Test
    fun `a wired vessel survives a save and load`() {
        val played = run(rig(Storage.CAP), 20)
        val reloaded = Save.read(Save.write(played))

        val after = run(reloaded, 1)
        val moved = after.grid.tile(
            extractorAt.first + after.frameShiftX,
            extractorAt.second + after.frameShiftY,
        )
        assertEquals(
            played.signals.at(grid.tile(extractorAt.first, extractorAt.second)),
            after.signals.at(moved),
            "the circuit should come back carrying what it carried",
        )
    }

    // ── A stopped clock ───────────────────────────────────────────────────────

    /** The same run of wire, with a slow sensor on the end of it and nothing to throttle. */
    private fun delayRig(delay: Int, release: Int = 0): VesselState {
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(13, 5), Direction.Right)
        deck += Sensor(
            grid.tile(sensorAt.first, sensorAt.second), Direction.Down,
            threshold = 0, delay = delay, release = release,
        )

        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(wires, extractorAt.first, sensorAt.first, 3)

        return VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(13, 5), Mixture.of(Species.Iron to Storage.CAP, energy = 0))
    }

    private fun sensor(s: VesselState): Sensor =
        s.deck[grid.tile(sensorAt.first, sensorAt.second)] as Sensor

    private fun paused(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.freeze(cfg, s, emptyMap()) }
        return s
    }

    /**
     * ⛔ **A delay is a wait the player has to sit through**, so a stopped game must not serve any
     * of it. The signal pass runs on frozen ticks on purpose — what the wires carry is a derivation,
     * not time passing, and skipping it would slam every airlock shut for as long as the game was
     * stopped — but the sensor's two counters *are* time passing and were being advanced inside it.
     * A sensor with a minute on its dial used to come off pause already fired.
     */
    @Test
    fun `a paused game serves none of a sensor's delay`() {
        val started = run(delayRig(delay = 8), 3)
        assertEquals(3, sensor(started).delayedFor, "three live ticks should be three ticks served")
        assertEquals(
            0, started.signals.at(grid.tile(sensorAt.first, sensorAt.second)),
            "still counting down: the sensor should not be driving the run yet",
        )

        val stopped = paused(started, 30)
        assertEquals(3, sensor(stopped).delayedFor, "thirty stopped ticks are no wait at all")
        assertEquals(
            0, stopped.signals.at(grid.tile(sensorAt.first, sensorAt.second)),
            "a paused sensor should not fire itself",
        )

        // And the wait resumes where it left off rather than restarting: five more live ticks fill
        // the dial, and the sixth — the first tick that finds it no longer counting — fires.
        val resumed = run(stopped, 6)
        assertEquals(8, sensor(resumed).delayedFor)
        assertEquals(
            SignalField.FULL, resumed.signals.at(grid.tile(sensorAt.first, sensorAt.second)),
            "the wait was served in full and only once, so it should be on",
        )
    }

    /** The other dial, which holds a signal up after the reading has gone away. */
    @Test
    fun `a paused game serves none of a sensor's release`() {
        // Fires immediately, then holds for ten ticks once the tank is emptied.
        val on = run(delayRig(delay = 0, release = 10), 2)
        assertEquals(
            SignalField.FULL, on.signals.at(grid.tile(sensorAt.first, sensorAt.second)),
            "a full tank with no delay should be driving the run",
        )

        val emptied = run(on.stocked(grid.tile(13, 5), null), 3)
        assertEquals(3, sensor(emptied).releasedFor, "three live ticks of the hold")

        val stopped = paused(emptied, 30)
        assertEquals(3, sensor(stopped).releasedFor, "thirty stopped ticks are no hold at all")
        assertEquals(
            SignalField.FULL, stopped.signals.at(grid.tile(sensorAt.first, sensorAt.second)),
            "a paused sensor should not release itself either",
        )
    }
}
