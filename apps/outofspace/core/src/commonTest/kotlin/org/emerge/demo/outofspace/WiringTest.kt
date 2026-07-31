package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Signals
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private val cfg = OutofspaceConfig(grid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun wiring(vararg terms: Pair<Channel, Int>) =
        Wiring(mapOf(Action.Run to terms.map { Trigger(it.first, it.second) }))

    // ── Activation arithmetic ─────────────────────────────────────────────────

    @Test
    fun `activation sums the terms and clamps`() {
        val signals = Signals.build { raise -> raise(Channel.Red, 1000); raise(Channel.Blue, 500) }
        assertEquals(1000, wiring(Channel.Always to 1000).activation(Action.Run, signals))
        assertEquals(500, wiring(Channel.Blue to 1000).activation(Action.Run, signals))
        assertEquals(250, wiring(Channel.Blue to 500).activation(Action.Run, signals))
        assertEquals(0, wiring(Channel.Always to 1000, Channel.Red to -1000).activation(Action.Run, signals))
        // Sums past full are clamped, not wrapped.
        assertEquals(1000, wiring(Channel.Always to 1000, Channel.Red to 1000).activation(Action.Run, signals))
        assertEquals(-1000, wiring(Channel.Red to -1000, Channel.Blue to -1000).activation(Action.Run, signals))
    }

    @Test
    fun `a machine with no terms never runs`() {
        assertEquals(0, Wiring(mapOf(Action.Run to emptyList())).activation(Action.Run, Signals.build { }))
    }

    @Test
    fun `ALWAYS reads full and cannot be raised or lowered by a sensor`() {
        val signals = Signals.build { raise -> raise(Channel.Always, 0) }
        assertEquals(Signals.FULL, signals[Channel.Always])
    }

    @Test
    fun `the loudest sensor wins a shared channel, so order cannot matter`() {
        val signals = Signals.build { raise ->
            raise(Channel.Red, 200)
            raise(Channel.Red, 900)
            raise(Channel.Red, 400)
        }
        assertEquals(900, signals[Channel.Red])
    }

    // ── Sensors ───────────────────────────────────────────────────────────────

    @Test
    fun `a sensor reports the fullness of the tile it faces`() {
        val grid = Grid(2, 1)
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to Storage.CAP / 2))
        var s = VesselState(grid, listOf(Storage(Direction.Right, stored), Sensor(Direction.Left, Channel.Red)))
        s = run(s, 1)
        assertEquals(500, s.signals[Channel.Red], "a half-full tank should read 50%")
    }

    @Test
    fun `a sensor facing nothing reports nothing`() {
        val grid = Grid(2, 1)
        var s = VesselState(grid, listOf(null, Sensor(Direction.Left, Channel.Red)))
        s = run(s, 1)
        assertEquals(0, s.signals[Channel.Red])
    }

    @Test
    fun `retuning a sensor moves its reading to the new channel`() {
        val grid = Grid(2, 1)
        val full = Resource(Form.IronIngot, Mixture.of(Species.Iron to Storage.CAP))
        var s = VesselState(grid, listOf(Storage(Direction.Right, full), Sensor(Direction.Left, Channel.Red)))
        s = run(s, 2, OutofspaceInput(listOf(Edit.SetChannel(1, Channel.Blue))))
        assertEquals(0, s.signals[Channel.Red])
        assertEquals(1000, s.signals[Channel.Blue])
    }

    // ── Throttling, not switching ─────────────────────────────────────────────

    @Test
    fun `half activation is half throughput`() {
        fun minedAfterASecond(w: Wiring): Long {
            val grid = Grid(2, 1)
            val miner = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY).withWiring(w) as Miner
            return run(VesselState(grid, listOf(miner, null)), 60).minedGrams
        }
        assertEquals(1_000L, minedAfterASecond(wiring(Channel.Always to 1000)))
        assertEquals(500L, minedAfterASecond(wiring(Channel.Always to 500)), "half the weight, half the ore")
        assertEquals(250L, minedAfterASecond(wiring(Channel.Always to 250)))
        assertEquals(0L, minedAfterASecond(wiring(Channel.Always to -1000)), "negative activation stops it")
    }

    @Test
    fun `a tank with no activation holds everything it has`() {
        // Track is inert -- it has no wiring and cannot be switched off, because it is plumbing. The
        // thing you switch off is the machine at the end of it, and a shut tank is a shut valve.
        val grid = Grid(12, 6)
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to 4_000L))
        val m = arrayOfNulls<Machine>(grid.size)
        m[grid.index(3, 3)] = Storage(Direction.Right, stored).copy(wiring = wiring()) as Storage
        m[grid.index(8, 3)] = Storage(Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 7, 3)
        var s = VesselState(grid, m.toList(), rails = rails.toList())

        s = run(s, Bridge.STEP_TICKS * 8)
        assertEquals(4_000L, (s[grid.index(3, 3)] as Storage).contents?.mass, "it let go of nothing")
        assertEquals(0L, (4..7).sumOf { s.railAt(grid.index(it, 3))?.held?.mass ?: 0L }, "so the track is bare")
    }

    // ── The loop that makes wiring worth having ───────────────────────────────

    /**
     * `ALWAYS − RED` is a **proportional controller, not a cut-off**, and that is worth pinning down
     * because it is easy to assume otherwise. As the tank fills, RED rises and the miner throttles
     * smoothly down; at 85% full it is running at 15%, still creeping upward. It approaches full
     * asymptotically and never overshoots.
     *
     * A hard "stop at 90%" would need a *comparison* — `WHEN RED > 900` — which this grammar cannot
     * express. That is a deliberate gap for now, noted in the plan: the analogue behaviour is the
     * more interesting half and comparisons can be added without disturbing it.
     */
    @Test
    fun `a miner wired ALWAYS minus RED throttles smoothly as the tank it fills gets full`() {
        // Miner covers x 1..3 and pushes out at x=3; the tank covers 4..6 and takes it in at x=4.
        // The sensor sits below the tank looking up at its bottom row.
        val grid = Grid(12, 8)
        val machines = arrayOfNulls<Machine>(grid.size)
        machines[grid.index(2, 3)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
            .withWiring(wiring(Channel.Always to 1000, Channel.Red to -1000)) as Miner
        machines[grid.index(6, 3)] = Storage(Direction.Right)   // input port at (5, 3)
        machines[grid.index(6, 5)] = Sensor(Direction.Up, Channel.Red)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 5, 3)
        var s = VesselState(grid, machines.toList(), rails = rails.toList())

        // Throttling begins on the very first tick — fullness is continuous, so there is no grace
        // period. What matters is the *shape*: the rate falls away as the tank fills.
        val firstTenSeconds = run(s, 60 * 10).minedGrams
        assertTrue(firstTenSeconds > 7_000L, "barely throttled while nearly empty, got ${firstTenSeconds}g")

        s = run(s, 60 * 40)
        val red = s.signals[Channel.Red]
        assertTrue(red > 800, "the tank should be reading nearly full, got $red")

        val lateRate = run(s, 60 * 10).minedGrams - s.minedGrams
        assertTrue(
            lateRate * 4 < firstTenSeconds,
            "should be throttled to a fraction of its early rate: ${firstTenSeconds}g then ${lateRate}g",
        )
        assertTrue((s[grid.index(6, 3)] as Storage).contents!!.mass <= Storage.CAP, "and it never overfills")
    }

    @Test
    fun `the starter vessel ships that same loop, working`() {
        val s = run(starterVessel(Grid(40, 28)), 60 * 40)
        assertTrue(s.signals[Channel.Red] > 800, "the demonstration storage should have nearly filled")
        // And the main line is unaffected by it.
        assertTrue(s.stockpile[Form.IronIngot].total > 0L, "the refinery line still stores iron")
    }

    // ── Editing wiring ────────────────────────────────────────────────────────

    @Test
    fun `wiring edits add, change and remove terms`() {
        val grid = Grid(2, 1)
        val base = VesselState(grid, listOf(Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY), null))

        val added = run(base, 1, OutofspaceInput(listOf(Edit.Wire(0, Action.Run, 99, Trigger(Channel.Red, -1000)))))
        assertEquals(2, added[0]!!.wiring.triggers(Action.Run).size, "a slot past the end appends")

        val changed = run(added, 1, OutofspaceInput(listOf(Edit.Wire(0, Action.Run, 1, Trigger(Channel.Blue, 500)))))
        assertEquals(Trigger(Channel.Blue, 500), changed[0]!!.wiring.triggers(Action.Run)[1])

        val removed = run(changed, 1, OutofspaceInput(listOf(Edit.Wire(0, Action.Run, 1, null))))
        assertEquals(1, removed[0]!!.wiring.triggers(Action.Run).size)
    }

    @Test
    fun `a freshly placed machine is wired to ALWAYS so it simply works`() {
        // Room for the whole footprint: a place that would hang off the grid is refused outright.
        val grid = Grid(8, 6)
        val at = grid.index(3, 3)
        var s = VesselState(grid, List(grid.size) { null })
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(at, MachineKind.Miner, Direction.Right))))
        assertEquals(listOf(Trigger(Channel.Always, 1000)), s[at]!!.wiring.triggers(Action.Run))
    }

    @Test
    fun `wiring survives rotation`() {
        val grid = Grid(2, 1)
        val wired = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
            .withWiring(wiring(Channel.Cyan to 750))
        var s = VesselState(grid, listOf(wired, null))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(0))))
        assertEquals(Direction.Down, (s[0] as Miner).facing)
        assertEquals(listOf(Trigger(Channel.Cyan, 750)), s[0]!!.wiring.triggers(Action.Run))
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
        return VesselState(g, m.toList(), rails = rails.toList())
    }

    @Test
    fun `a storage releases only while it is told to`() {
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to 5_000L))
        val shut = Storage(Direction.Right, stored).copy(wiring = wiring())
        // The downstream tank is what gets checked, not the stockpile: both tanks feed the stockpile
        // now, so its total is 5kg either way and would say nothing about whether the valve opened.
        val g = twoUp(shut).grid
        var s = run(twoUp(shut), 60 * 5)
        assertEquals(5_000L, (s[g.index(3, 3)] as Storage).contents!!.mass, "a closed valve holds everything")
        assertNull((s[g.index(7, 3)] as Storage).contents, "so nothing arrives downstream")

        var s2 = run(twoUp(Storage(Direction.Right, stored)), 60 * 5)
        assertEquals(5_000L, (s2[g.index(7, 3)] as Storage).contents!!.mass, "an open one drains into the next tank")
        assertNull((s2[g.index(3, 3)] as Storage).contents, "and empties itself doing it")
    }

    // ── Conservation still holds with all of it running ───────────────────────

    @Test
    fun `the world still never loses a gram with sensors and wiring in play`() {
        var s = starterVessel(Grid(40, 28))
        repeat(60 * 60) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 89 == 0) {
                assertEquals(
                    s.minedGrams,
                    s.inTransitGrams + s.ventedGrams,
                    "tick ${s.tick}",
                )
            }
        }
    }

    @Test
    fun `two runs of the wired world are identical`() {
        fun digest(s: VesselState) = buildString {
            append(s.tick).append(s.minedGrams).append(s.ventedGrams).append(s.stockpile)
            for (m in s.machines) append(m?.toString() ?: "-")
        }
        val grid = Grid(40, 28)
        assertEquals(digest(run(starterVessel(grid), 900)), digest(run(starterVessel(grid), 900)))
    }
}
