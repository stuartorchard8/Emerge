package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import org.emerge.demo.outofspace.world.bufferTile
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
        val deck = DeckArray(grid)
        val stored = Mixture.of(Species.Iron to fill, energy = 0)
        deck += Extractor(grid.tile(extractorAt.first, extractorAt.second), Direction.Right)
            .withWiring(stopWhenFull)
        deck += Storage(grid.tile(13, 5), Direction.Right)
        // Looking up at the tank, which sits below the run.
        deck += Sensor(grid.tile(sensorAt.first, sensorAt.second), Direction.Down)

        val wires = arrayOfNulls<Segment>(grid.size)
        if (wired) signalRow(wires, extractorAt.first, sensorAt.first, 3)

        return VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            bodies = rockOnPlate(extractorAt.first, extractorAt.second, 6),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(13, 5), stored)
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
        assertEquals(0, extractor(s)!!.wiring.activation(Action.Run, s.signals.at(grid.tile(extractorAt.first, extractorAt.second))))
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
        deck += Storage(grid.tile(13, 5), Direction.Right)
        deck += Sensor(grid.tile(sensorAt.first, sensorAt.second), Direction.Down)

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
     * Half a signal is half a machine, over a wire exactly as it was over a channel. The proportional
     * controller is the interesting half of this grammar and it would be easy to lose to a boolean.
     */
    @Test
    fun `a partial reading throttles proportionally`() {
        // Four ticks, not forty: over a longer window the machine's own buffer empties and it
        // stalls, and a stalled machine works the same amount whatever its throttle says. Measured
        // over the window where the throttle is the only thing governing the rate.
        val empty = burned(run(burnerRig(0L), 4))
        val quarter = burned(run(burnerRig(Storage.CAP / 4), 4))
        val half = burned(run(burnerRig(Storage.CAP / 2), 4))

        assertTrue(empty > quarter, "an empty tank should not throttle at all: $empty vs $quarter")
        assertTrue(quarter > half, "a quarter-full tank should throttle less than a half-full one: $quarter vs $half")
        assertTrue(half > 0L, "and half full is still running")
    }

    @Test
    fun `two machines on one run see the same value`() {
        val deck = DeckArray(grid)
        val stored = Mixture.of(Species.Iron to Storage.CAP, energy = 0)
        deck += Storage(grid.tile(13, 5), Direction.Right)
        deck += Sensor(grid.tile(12, 3), Direction.Down)
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
}
