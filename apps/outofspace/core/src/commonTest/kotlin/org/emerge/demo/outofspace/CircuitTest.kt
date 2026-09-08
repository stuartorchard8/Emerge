package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BodySlot
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Circuit
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TerminalRole
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.bodiesOf
import org.emerge.demo.outofspace.world.circuitOf
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Electrolyzer
import org.emerge.demo.outofspace.world.terminalTile
import org.emerge.demo.outofspace.world.terminalTiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **What is wired to what** — increment 1 of `PLAN_power_network.md`, which builds the electrical
 * contact graph and nothing that runs on it.
 *
 * Every test here is a connectivity question and none of them needs any electricity, which is why
 * this increment was split off: the graph carries the design risk and is testable on its own. The
 * three the plan names are all here — a crossing is two circuits, a terminal makes it one, and a
 * copper-cased machine shorts its own ends while a firebrick-cased one does not.
 */
class CircuitTest {

    private val grid = Grid(14, 9)

    /** A straight run of [conduit] along [row], from [x0] to [x1] inclusive, made of [metal]. */
    private fun run(
        conduit: Conduit,
        row: Int,
        x0: Int,
        x1: Int,
        metal: Species = Species.Copper,
        insulatorAt: Int = -1,
    ): List<Segment?> {
        val layer = arrayOfNulls<Segment>(grid.size)
        for (x in x0..x1) {
            val material = if (x == insulatorAt) Species.Forsterite else metal
            var s = Segment(conduit, material = material)
            if (x > x0) s = s.joinedTo(Direction.Left)
            if (x < x1) s = s.joinedTo(Direction.Right)
            layer[grid.tile(x, row).index] = s
        }
        return layer.toList()
    }

    /** A run down a column, so it can cross one drawn along a row. */
    private fun column(
        conduit: Conduit,
        col: Int,
        y0: Int,
        y1: Int,
        metal: Species = Species.Copper,
    ): List<Segment?> {
        val layer = arrayOfNulls<Segment>(grid.size)
        for (y in y0..y1) {
            var s = Segment(conduit, material = metal)
            if (y > y0) s = s.joinedTo(Direction.Up)
            if (y < y1) s = s.joinedTo(Direction.Down)
            layer[grid.tile(col, y).index] = s
        }
        return layer.toList()
    }

    private fun circuit(
        conduits: Conduits,
        deck: DeckArray = DeckArray(grid),
        buffers: BufferLayer = BufferLayer.forDeck(grid, deck),
        terminals: BooleanArray = terminalTiles(grid, deck),
    ): Pair<Circuit, List<org.emerge.demo.outofspace.world.Body>> {
        val bodies = bodiesOf(grid, conduits, deck, buffers, RailLayer.empty(grid.size))
        return circuitOf(grid, bodies, terminals) to bodies
    }

    /** The node a conduit tile became, asserted to exist. */
    private fun nodeAt(
        c: Circuit,
        bodies: List<org.emerge.demo.outofspace.world.Body>,
        conduit: Conduit,
        tile: TileIndex,
    ): Int {
        val i = bodies.indexOfFirst { it.slot == BodySlot.Fitting && it.conduit == conduit && it.tile == tile }
        assertTrue(i >= 0, "no $conduit fitting at $tile")
        val n = c.circuitOfBody(i)
        assertTrue(n != Circuit.NOT_CONDUCTING, "$conduit at $tile carries no current")
        return n
    }

    // ── The crossing, and the terminal that closes it ────────────────────────

    /**
     * ⭐ **The rule the whole design rests on.** Heat joins everything standing on a tile; charge
     * does not, so two runs of different layers may cross without shorting. That is what makes
     * crossings free and what means no power bridge has to exist.
     */
    @Test
    fun `a rail crossing a power run is two circuits`() {
        val cross = grid.tile(6, 4)
        val conduits = Conduits.of(
            grid.size,
            Conduit.Rail to run(Conduit.Rail, row = 4, x0 = 2, x1 = 10),
            Conduit.Power to column(Conduit.Power, col = 6, y0 = 1, y1 = 7),
        )
        val (c, bodies) = circuit(conduits)
        assertEquals(2, c.componentCount, "a crossing shorted two layers together")
        val rail = nodeAt(c, bodies, Conduit.Rail, cross)
        val power = nodeAt(c, bodies, Conduit.Power, cross)
        assertTrue(rail != power, "rail and power met on a tile with no terminal on it")
    }

    /** ⭐ And a terminal is what joins them — the rod touching every layer at its tile. */
    @Test
    fun `a terminal joins the layers standing on its tile`() {
        val cross = grid.tile(6, 4)
        val conduits = Conduits.of(
            grid.size,
            Conduit.Rail to run(Conduit.Rail, row = 4, x0 = 2, x1 = 10),
            Conduit.Power to column(Conduit.Power, col = 6, y0 = 1, y1 = 7),
        )
        val terminals = BooleanArray(grid.size).also { it[cross.index] = true }
        val (c, bodies) = circuit(conduits, terminals = terminals)
        assertEquals(1, c.componentCount, "a terminal failed to bond the layers under it")
        assertEquals(
            nodeAt(c, bodies, Conduit.Rail, cross),
            nodeAt(c, bodies, Conduit.Power, cross),
            "rail and power are on one terminal and still read as two circuits",
        )
    }

    // ── The casing as a parallel path ────────────────────────────────────────

    /**
     * Two power stubs, one under each of a cell's arms, and nothing else joining them but the
     * machine itself. What the casing is made of is the only thing that varies.
     */
    private fun cellBetweenStubs(casing: Species): Pair<Circuit, List<org.emerge.demo.outofspace.world.Body>> {
        val centre = grid.tile(6, 4)
        val deck = DeckArray(grid)
        deck.stand(Electrolyzer(centre, Direction.Right), withCasing = true, material = casing)
        val negative = terminalTile(grid, Electrolyzer(centre, Direction.Right), centre, TerminalRole.Negative)
        val positive = terminalTile(grid, Electrolyzer(centre, Direction.Right), centre, TerminalRole.Positive)
        assertNotNull(negative); assertNotNull(positive)

        // A one-tile stub of cable on each arm. They touch nothing but the machine.
        val power = arrayOfNulls<Segment>(grid.size)
        power[negative.index] = Segment(Conduit.Power, material = Species.Copper)
        power[positive.index] = Segment(Conduit.Power, material = Species.Copper)
        val conduits = Conduits.of(grid.size, Conduit.Power to power.toList())
        return circuit(conduits, deck)
    }

    /**
     * ⛔ **A machine built out of copper cannot use power**, and this is the connectivity half of
     * that: its casing is a lower-resistance path between its own two ends than anything inside it,
     * so the two ends are one node. `PLAN_power_network.md` §5.
     */
    @Test
    fun `a copper cased cell shorts its own two terminals together`() {
        val (c, bodies) = cellBetweenStubs(Species.Copper)
        val centre = grid.tile(6, 4)
        val m = Electrolyzer(centre, Direction.Right)
        val neg = nodeAt(c, bodies, Conduit.Power, terminalTile(grid, m, centre, TerminalRole.Negative)!!)
        val pos = nodeAt(c, bodies, Conduit.Power, terminalTile(grid, m, centre, TerminalRole.Positive)!!)
        assertEquals(pos, neg, "a copper casing failed to short the ends it stands between")
    }

    /** ⭐ And a resistive casing leaves them apart, which is the machine that works. */
    @Test
    fun `a firebrick cased cell keeps its two terminals apart`() {
        val (c, bodies) = cellBetweenStubs(Species.Firebrick)
        val centre = grid.tile(6, 4)
        val m = Electrolyzer(centre, Direction.Right)
        val neg = nodeAt(c, bodies, Conduit.Power, terminalTile(grid, m, centre, TerminalRole.Negative)!!)
        val pos = nodeAt(c, bodies, Conduit.Power, terminalTile(grid, m, centre, TerminalRole.Positive)!!)
        assertTrue(pos != neg, "a firebrick casing conducted between the ends it stands between")
        assertTrue(
            bodies.none { it.slot == BodySlot.DeckStore && c.circuitOfBody(bodies.indexOf(it)) != Circuit.NOT_CONDUCTING },
            "a firebrick casing turned up in the graph at all",
        )
    }

    // ── What is not in the graph ─────────────────────────────────────────────

    /**
     * ⭐ **One insulating segment in a metal run is a galvanic isolator made of track** (Stu). It
     * needed nothing built: a non-metal conducts zero, so the walk stops at it.
     */
    @Test
    fun `an insulating segment breaks a run in two`() {
        val conduits = Conduits.of(
            grid.size,
            Conduit.Power to run(Conduit.Power, row = 4, x0 = 2, x1 = 10, insulatorAt = 6),
        )
        val (c, bodies) = circuit(conduits)
        assertEquals(2, c.componentCount, "an insulating segment did not break the run")
        val left = nodeAt(c, bodies, Conduit.Power, grid.tile(4, 4))
        val right = nodeAt(c, bodies, Conduit.Power, grid.tile(8, 4))
        assertTrue(left != right, "charge crossed a length of forsterite")
        val bridgeTile = grid.tile(6, 4)
        val i = bodies.indexOfFirst { it.slot == BodySlot.Fitting && it.tile == bridgeTile }
        assertEquals(
            Circuit.NOT_CONDUCTING,
            c.circuitOfBody(i),
            "the forsterite segment is a node in the circuit",
        )
    }

    /** ⛔ A site that holds no metal is not wire yet — decision 6. */
    @Test
    fun `a ghost segment carries nothing`() {
        val power = arrayOfNulls<Segment>(grid.size)
        for (x in 2..6) {
            var s = Segment(Conduit.Power, material = Species.Copper)
            if (x > 2) s = s.joinedTo(Direction.Left)
            if (x < 6) s = s.joinedTo(Direction.Right)
            power[grid.tile(x, 4).index] = s
        }
        // `finished` is what puts the metal in; without it every one of these is a construction site.
        val unpaid = Conduits.of(Array(Conduit.entries.size) {
            if (it == Conduit.Power.ordinal) power.toList() else List<Segment?>(grid.size) { null }
        }, org.emerge.demo.outofspace.world.TrackLayers.empty(grid.size))
        val (c, _) = circuit(unpaid)
        assertEquals(0, c.componentCount, "unpaid track carried a current")
    }

    /** ⛔ Decision 5 — a warehouse full of copper does not conduct. */
    @Test
    fun `what a machine is holding is not part of the circuit`() {
        val centre = grid.tile(6, 4)
        val deck = DeckArray(grid)
        deck.stand(Electrolyzer(centre, Direction.Right), withCasing = true, material = Species.Firebrick)
        val buffers = BufferLayer.forDeck(grid, deck)
        val store = terminalTile(grid, Electrolyzer(centre, Direction.Right), centre, TerminalRole.Negative)!!
        buffers.put(store, Mixture.of(Species.Copper to 400_000L, energy = 120_000_000L))
        val bodies = bodiesOf(grid, Conduits.empty(grid.size), deck, buffers, RailLayer.empty(grid.size))
        val c = circuitOf(grid, bodies, terminalTiles(grid, deck))
        val held = bodies.indexOfFirst { it.slot == BodySlot.BufferStore }
        assertTrue(held >= 0, "fixture: the buffer was supposed to become a body")
        assertFalse(
            c.circuitOfBody(held) != Circuit.NOT_CONDUCTING,
            "a hopper of copper joined the circuit",
        )
    }
}
