package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Ambient
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Cadence
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TerminalRole
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.DirectedDeckMachine
import org.emerge.demo.outofspace.world.machine.SolarPanel
import org.emerge.demo.outofspace.world.terminalTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **A panel with sky drives a current; a panel without drives nothing.**
 *
 * Increment 3 of `PLAN_power_network.md`. ⭐ Almost nothing here is a rule of its own: exposure is
 * `StructureMap.openToSpace`, which already decides what a hot surface radiates at, the light is one
 * number on [Ambient], and what the panel does to the ship is whatever Kirchhoff says. *The sun is
 * anywhere outside the vessel* (Stu).
 */
class SolarPanelTest {

    private val grid = Grid(16, 9)
    private val panelAt = grid.tile(4, 4)

    private fun panel() = SolarPanel(panelAt)

    /**
     * ⭐ **A panel turns, and turning it swaps its ends** (Stu, 2026-09-09).
     *
     * ⛔ **It had no facing at all**, so `R` did nothing to one and its positive terminal was
     * permanently on its left. That is a machine a player cannot wire: a cell's ends are on *its*
     * centre line too, so a panel and a cell pointed the same way have to be cross-wired and one leg
     * goes the long way round — which costs volts, because
     * [SolarPanel.CONDUCTANCE_PER_FACE] is anchored at about ten tiles of cable.
     *
     * ⚠️ **What it does NOT change is what the panel collects.** Exposure is `openToSpace` over its
     * own faces, and those are the same faces whichever way it points — pinned below so that a future
     * reading of the facing has to argue for itself.
     */
    private fun positive(): TileIndex = terminalTile(grid, panel(), panelAt, TerminalRole.Positive)!!
    private fun negative(): TileIndex = terminalTile(grid, panel(), panelAt, TerminalRole.Negative)!!

    @Test
    fun `turning a panel moves its terminals, and turning it about swaps them`() {
        val right = SolarPanel(panelAt)
        fun turn(p: SolarPanel) = (p as DirectedDeckMachine).rotated() as SolarPanel
        fun ends(p: SolarPanel) = Pair(
            terminalTile(grid, p, panelAt, TerminalRole.Positive),
            terminalTile(grid, p, panelAt, TerminalRole.Negative),
        )

        val down = turn(right)
        assertEquals(Direction.Down, down.facing, "R did not turn it")
        // ⚠️ A QUARTER turn puts both ends on the other axis — it does not swap them. Half a turn is
        // what swaps them, and that is the one a player reaches for when their wiring is crossed.
        assertNotEquals(ends(right), ends(down), "a quarter turn left the terminals where they were")

        val (posRight, negRight) = ends(right)
        val (posLeft, negLeft) = ends(turn(down))
        assertEquals(negRight, posLeft, "turned about, its plus is not where its minus was")
        assertEquals(posRight, negLeft, "turned about, its minus is not where its plus was")

        assertEquals(right.tiles(grid).toSet(), down.tiles(grid).toSet(), "turning moved it")
    }

    /** Cable laid along [path], each tile joined to the next. */
    private fun cable(path: List<TileIndex>): List<Segment?> {
        val layer = arrayOfNulls<Segment>(grid.size)
        for (t in path) layer[t.index] = Segment(Conduit.Power, material = Species.Copper)
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val dir = Direction.entries.first { grid.neighbour(a, it) == b }
            layer[a.index] = layer[a.index]!!.joinedTo(dir)
            layer[b.index] = layer[b.index]!!.joinedTo(dir.opposite)
        }
        return layer.toList()
    }

    /**
     * ⛔ **Silicon, and the reason is the point of §5.** A machine's casing is a parallel path
     * between its own two terminals, so a panel made of anything conductive shorts itself and drives
     * nothing at all. Silicon is a semiconductor and `Conductivity.kt` declares it a non-metal, so a
     * silicon panel works — which is Stu's P/N framing arriving through the back door rather than
     * being written down. `a steel panel shorts itself` below is the other half of this.
     */
    private fun world(
        power: List<Segment?>,
        walls: Boolean = false,
        ambient: Ambient = Ambient.VACUUM,
        casing: Species = Species.Silicon,
    ): VesselState {
        val deck = DeckArray(grid)
        deck.stand(panel(), withCasing = true, material = casing)
        if (walls) {
            for (x in 2..6) for (y in 2..6) {
                if (x in 3..5 && y in 3..5) continue
                deck += Hull(grid.tile(x, y))
            }
        }
        return VesselState(
            grid, deck,
            // ⚠️ **`Conduits.of`, not `empty().with()`** — the latter skips `finished()`, which is
            // what puts the metal in the track. Cable that has not been paid for is a **ghost** and
            // conducts nothing (decision 6), so the first version of this fixture wired every panel
            // to a run that was not there. The old charge model could not have noticed.
            conduits = Conduits.of(grid.size, Conduit.Power to power),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            ambient = ambient,
        )
    }

    private fun run(state: VesselState, ticks: Int = 8): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun VesselState.across(): Long = potential[positive().index] - potential[negative().index]

    /** Both terminals stubbed and nothing joining them — an open circuit. */
    private fun openStubs(): List<Segment?> {
        val layer = arrayOfNulls<Segment>(grid.size)
        layer[positive().index] = Segment(Conduit.Power, material = Species.Copper)
        layer[negative().index] = Segment(Conduit.Power, material = Species.Copper)
        return layer.toList()
    }

    /** The two terminals joined the long way round, outside the footprint. */
    private fun loop(): List<Segment?> = cable(
        listOf(
            positive(), grid.tile(2, 4), grid.tile(2, 5), grid.tile(2, 6),
            grid.tile(3, 6), grid.tile(4, 6), grid.tile(5, 6), grid.tile(6, 6),
            grid.tile(6, 5), grid.tile(6, 4), negative(),
        )
    )

    // ── What the overlay is hung off ─────────────────────────────────────────

    /**
     * ⛔ **The solve stamps a live tick, and a zero span — never [Cadence.SETTLED].**
     *
     * The circuit overlay fades from *what the stamp last said*, so a stamp that never advances is
     * read as "nothing has happened since": the tint was sampled on the first frame the overlay was
     * drawn and held for ever after, and a circuit the player changed while looking at it did not
     * change on screen. Found by increment 3c's terminal, whose whole picture is a circuit changing.
     *
     * ⚠️ **The span is zero and that is the other half.** A pass that runs every tick has nothing to
     * ease across; what it needs is to be re-read, which is what a live stamp buys.
     */
    @Test
    fun `the circuit is stamped fresh on every tick`() {
        val one = run(world(loop()), ticks = 1)
        val eight = run(world(loop()), ticks = 8)
        assertEquals(
            one.tick - 1,
            one.cadences.circuit.writtenAtTick,
            "the power solve did not stamp the tick it ran on",
        )
        assertEquals(
            eight.tick - 1,
            eight.cadences.circuit.writtenAtTick,
            "the circuit stamp stopped advancing, so the overlay would freeze",
        )
        assertEquals(0, eight.cadences.circuit.spanTicks, "a pass that runs every tick has a span to fade across")
    }

    // ── The stall ────────────────────────────────────────────────────────────

    /**
     * ⭐ **Open-circuit, a panel sits at its stall and drives nothing** — which is the half of a
     * photovoltaic cell the old model was missing, and the reason it overran its own overflow bound
     * in about 2500 ticks. ⚠️ [Ambient.VACUUM] is full sun: the light is a scalar and vacuum does
     * not dim it.
     */
    @Test
    fun `an open circuit panel sits at its open circuit voltage`() {
        val s = run(world(openStubs(), ambient = Ambient.VACUUM))
        assertEquals(
            SolarPanel.OPEN_CIRCUIT_MICROVOLTS,
            s.across(),
            "an unloaded panel did not stall at its open-circuit voltage",
        )
    }

    /** ⭐ And loaded, it drives a current and the run it drives warms up — `I²R`, written nowhere. */
    @Test
    fun `a loaded panel drives a current and warms the run`() {
        val s = run(world(loop(), ambient = Ambient.VACUUM))
        val across = s.across()
        assertTrue(across > 0L, "a loaded panel drove nothing")
        assertTrue(
            across < SolarPanel.OPEN_CIRCUIT_MICROVOLTS,
            "a loaded panel sat at its open-circuit voltage ($across), so no current flowed",
        )
        assertTrue(s.generatedEnergy > 0L, "the run carried a current and did not warm up")
        // ⚠️ **What the overlay reads**, asserted here so a dead picture can be told from a dead
        // circuit. `CircuitView` is a flattening of the same solve, and if it is empty the overlay
        // has nothing to draw however well it draws it.
        assertTrue(s.circuit.peak > 0L, "the solve carried a current the overlay could not see")
        assertTrue(
            s.circuit.current.any { it != 0L },
            "no face of any tile reported a current",
        )
    }

    // ── What stops it ────────────────────────────────────────────────────────

    /**
     * ⛔ **Every tile of a series loop carries the same current**, and the overlay has to be able to
     * see it on every one of them. A loop is a chain: what goes in at one end comes out at the
     * other, so a picture that lights one segment and not the rest is drawing a lie.
     */
    @Test
    fun `every tile of the loop reports the same current`() {
        val s = run(world(loop()))
        val ring = listOf(
            grid.tile(2, 4), grid.tile(2, 5), grid.tile(2, 6),
            grid.tile(3, 6), grid.tile(4, 6), grid.tile(5, 6), grid.tile(6, 6),
            grid.tile(6, 5), grid.tile(6, 4),
        )
        val carried = ring.map { tile ->
            (0 until 4).maxOf {
                val c = s.circuit.current[tile.index * 4 + it]
                if (c < 0L) -c else c
            }
        }
        assertTrue(carried.all { it > 0L }, "some of the loop carried nothing: $carried")
        val most = carried.max()
        val least = carried.min()
        assertTrue(
            least * 100L > most * 99L,
            "a series loop carried different currents at different tiles: $carried",
        )
        assertEquals(most, s.circuit.peak, "the loop's own current was not the peak the view scales by")
    }

    /** Bury one and it has no sky. Nothing forbids it; it simply makes nothing. */
    @Test
    fun `a panel walled in on every side makes nothing`() {
        val s = run(world(openStubs(), walls = true, ambient = Ambient.VACUUM))
        assertEquals(0L, s.across(), "a buried panel drove its terminals apart")
    }

    /** Far from the sun there is less of it, and that is one scalar on [Ambient]. */
    @Test
    fun `a vessel at a gas giant collects less than one near the sun`() {
        val near = run(world(loop(), ambient = Ambient.VACUUM))
        val far = run(world(loop(), ambient = Ambient.GAS_GIANT))
        assertTrue(
            far.across() < near.across(),
            "a panel at Jupiter (${far.across()}) drove as hard as one at Earth (${near.across()})",
        )
    }

    /**
     * ⛔ **A panel built out of anything conductive shorts itself**, because its casing is a parallel
     * path between its own two ends — `PLAN_power_network.md` §5, applied to the machine that makes
     * the power rather than to one that spends it.
     *
     * ⚠️ **`materialBefore` says a panel used to be steel**, which under this model is a dead panel.
     * That table is historical rather than normative and is left alone; what it means is that
     * *choosing* the substance is now part of building one.
     */
    @Test
    fun `a steel panel shorts itself through its own casing`() {
        val silicon = run(world(loop(), casing = Species.Silicon)).across()
        val steel = run(world(loop(), casing = Species.Steel)).across()
        assertTrue(silicon > 0L, "fixture: the silicon panel was supposed to drive something")
        // ⚠️ **Much less, not nothing.** A short is a *lower*-resistance path and not a zero one, so
        // a steel-cased panel still develops something across its ends — it is just spending most of
        // what it makes on warming its own chassis.
        assertTrue(
            steel * 2L < silicon,
            "a steel-cased panel held $steel against a silicon one's $silicon — its casing is not shorting it",
        )
    }

    /** A panel needs a conductor on both ends. One is not a circuit. */
    @Test
    fun `a panel with only one terminal wired drives nothing`() {
        val layer = arrayOfNulls<Segment>(grid.size)
        layer[positive().index] = Segment(Conduit.Power, material = Species.Copper)
        val s = run(world(layer.toList(), ambient = Ambient.VACUUM))
        assertEquals(0L, s.across(), "a panel wired at one end drove something")
    }
}
