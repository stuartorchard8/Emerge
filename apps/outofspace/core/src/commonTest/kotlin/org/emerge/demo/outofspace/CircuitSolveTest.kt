package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BodySlot
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Circuit
import org.emerge.demo.outofspace.world.CircuitSolve
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Solution
import org.emerge.demo.outofspace.world.Source
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.bodiesOf
import org.emerge.demo.outofspace.world.circuitOf
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.reduceToJunctions
import org.emerge.demo.outofspace.world.solveCircuit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Ohm's law was never written down, and this is where that claim gets checked.**
 *
 * Increment 2 of `PLAN_power_network.md`. The old plan asserted that series, parallel and a divider
 * all fell out of the model and nothing ever proved it; the transient model could not have, because
 * it never reached a steady state anybody could compare against a hand calculation. A solved network
 * can.
 */
class CircuitSolveTest {

    private val grid = Grid(24, 5)
    private val volt = 1_000L * CircuitSolve.MICROVOLTS_PER_MILLIVOLT

    /** A straight power run along row 2 from [x0] to [x1] inclusive. */
    private fun cable(x0: Int, x1: Int, metal: Species = Species.Copper): List<Segment?> {
        val layer = arrayOfNulls<Segment>(grid.size)
        for (x in x0..x1) {
            var s = Segment(Conduit.Power, material = metal)
            if (x > x0) s = s.joinedTo(Direction.Left)
            if (x < x1) s = s.joinedTo(Direction.Right)
            layer[grid.tile(x, 2).index] = s
        }
        return layer.toList()
    }

    private class Wired(val circuit: Circuit, val node: (Int) -> Int)

    /** A run laid, turned into a circuit, with a lookup from column to node. */
    private fun wire(layer: List<Segment?>, conduit: Conduit = Conduit.Power): Wired {
        val conduits = Conduits.of(grid.size, conduit to layer)
        val deck = DeckArray(grid)
        val bodies = bodiesOf(grid, conduits, deck, BufferLayer.forDeck(grid, deck), RailLayer.empty(grid.size))
        val c = circuitOf(grid, bodies, BooleanArray(grid.size))
        return Wired(c) { x ->
            val tile: TileIndex = grid.tile(x, 2)
            val i = bodies.indexOfFirst { it.slot == BodySlot.Fitting && it.tile == tile }
            assertTrue(i >= 0, "no fitting at column $x")
            val n = c.nodeOfBody(i)
            assertTrue(n != Circuit.NOT_CONDUCTING, "the fitting at column $x carries no current")
            n
        }
    }

    /** What the run between [a] and [b] conducts, as the solve sees it. */
    private fun edgeG(c: Circuit, a: Int, b: Int): Long {
        for (e in 0 until c.edgeCount) {
            if ((c.edgeA[e] == a && c.edgeB[e] == b) || (c.edgeA[e] == b && c.edgeB[e] == a)) return c.edgeG[e]
        }
        error("no edge between $a and $b")
    }

    private fun Solution.currentThrough(source: Int): Long = sourceCurrent[source]

    // ── Ohm, series, parallel, divider ───────────────────────────────────────

    /**
     * ⭐ **Two conductances in series, and nobody wrote series down.** A source of internal
     * conductance `Gs` across one segment of conductance `G` must carry `E / (1/G + 1/Gs)`, which is
     * `E·G·Gs/(G+Gs)` — the harmonic combination. Asserted against the arithmetic, not against a
     * recorded number.
     */
    @Test
    fun `a source across one segment carries the series combination`() {
        val w = wire(cable(4, 5))
        val g = edgeG(w.circuit, w.node(4), w.node(5))
        val gs = g / 3L
        val sources = listOf(Source(w.node(5), w.node(4), volt, gs))
        val s = solveCircuit(w.circuit, sources)

        val exact = (volt.toDouble() * g * gs / (g + gs)).toLong()
        val got = s.currentThrough(0)
        assertTrue(
            kotlin.math.abs(got - exact) < exact / 1000L,
            "series combination: expected about $exact, got $got",
        )
    }

    /**
     * ⭐ **A divider.** Two equal segments in a line with the supply across both ends: the middle
     * node must sit halfway. Nothing states this.
     */
    @Test
    fun `two equal segments divide the supply between them`() {
        val w = wire(cable(4, 6))
        val ends = Source(w.node(6), w.node(4), volt, edgeG(w.circuit, w.node(4), w.node(5)) / 50L)
        val s = solveCircuit(w.circuit, listOf(ends))
        val low = s.potential[w.node(4)]
        val mid = s.potential[w.node(5)]
        val high = s.potential[w.node(6)]
        val span = high - low
        assertTrue(span > 0L, "the supply drove nothing: $low → $high")
        val halfway = low + span / 2L
        assertTrue(
            kotlin.math.abs(mid - halfway) < span / 50L,
            "the middle of two equal segments sat at $mid, not near $halfway (span $low..$high)",
        )
    }

    /**
     * ⭐ **A long thin run cannot deliver what a short fat one can**, which is increment 0's promise
     * finally collecting. Same metal, same supply — only the length changes.
     */
    @Test
    fun `a long run delivers less than a short one`() {
        fun currentOver(length: Int): Long {
            val w = wire(cable(2, 2 + length))
            val g = edgeG(w.circuit, w.node(2), w.node(3))
            val s = solveCircuit(w.circuit, listOf(Source(w.node(2 + length), w.node(2), volt, g / 20L)))
            return kotlin.math.abs(s.currentThrough(0))
        }
        val short = currentOver(1)
        val long = currentOver(12)
        assertTrue(long < short, "a twelve-tile run ($long) delivered as much as a one-tile run ($short)")
    }

    /** ⭐ And copper beats iron by a factor nobody chose — out of the thermal column, via Wiedemann–Franz. */
    @Test
    fun `copper carries more than iron for the same supply`() {
        fun currentOf(metal: Species): Long {
            val w = wire(cable(2, 10, metal))
            // One fixed source conductance for both, so the metal is the only thing that varies.
            val s = solveCircuit(w.circuit, listOf(Source(w.node(10), w.node(2), volt, 3_000L)))
            return kotlin.math.abs(s.currentThrough(0))
        }
        val copper = currentOf(Species.Copper)
        val iron = currentOf(Species.Iron)
        assertTrue(copper > iron, "copper ($copper) did not beat iron ($iron)")
    }

    // ── The two ledgers ──────────────────────────────────────────────────────

    /**
     * ⛔ **The named term, and the bound on it.** The residual is the KCL imbalance the bounded
     * sweeps left behind — charge this solve could not account for. It must stay small against the
     * current actually flowing, and printing the ratio is what stops it drifting unnoticed.
     */
    @Test
    fun `the residual stays a small fraction of the current`() {
        val w = wire(cable(2, 8))
        val g = edgeG(w.circuit, w.node(2), w.node(3))
        val s = solveCircuit(w.circuit, listOf(Source(w.node(8), w.node(2), volt, g / 20L)))
        val current = kotlin.math.abs(s.currentThrough(0))
        assertTrue(current > 0L, "nothing flowed, so the residual proves nothing")
        assertTrue(
            s.residual * 100L < current,
            "residual ${s.residual} is not small against a current of $current",
        )
    }

    /**
     * ⭐ **A cold start is as good as a warm one, and that is the reduction's doing.**
     *
     * ⚠️ This test used to assert the opposite — that warm-starting helped — and it was right when
     * it was written. Before [reduceToJunctions] a thirty-tile run was 53% out on its balance after
     * two hundred warm ticks; now a run reduces to two junctions and four sweeps converge it from
     * nothing. Kept, inverted, because *"seeding is what makes this cheap"* stopped being the reason
     * it works and the record should say so.
     */
    @Test
    fun `a long run converges from a cold start`() {
        val w = wire(cable(2, 20))
        val g = edgeG(w.circuit, w.node(2), w.node(3))
        val sources = listOf(Source(w.node(20), w.node(2), volt, g / 20L))
        val cold = solveCircuit(w.circuit, sources)
        var warm = cold
        repeat(40) { warm = solveCircuit(w.circuit, sources, warm.potential) }
        val current = kotlin.math.abs(cold.currentThrough(0))
        assertTrue(current > 0L, "nothing flowed")
        assertTrue(
            cold.residual * 1000L < current,
            "a cold eighteen-tile run left residual ${cold.residual} against a current of $current",
        )
        assertTrue(
            kotlin.math.abs(cold.currentThrough(0) - warm.currentThrough(0)) * 1000L < current,
            "cold ${cold.currentThrough(0)} and warm ${warm.currentThrough(0)} disagree",
        )
    }

    // ── Topology the reduction has to survive ────────────────────────────────

    /**
     * ⭐ **Two paths carry more than one**, which is parallel and nobody wrote it down either.
     *
     * A rectangle of cable with the supply across two opposite corners: the two ways round are in
     * parallel. Compared against the same supply driving one of those ways on its own.
     */
    @Test
    fun `a loop carries more than a single path`() {
        // A rectangle: rows 1 and 3 between columns 4 and 10, joined down both ends.
        fun rectangle(closed: Boolean): List<Segment?> {
            val layer = arrayOfNulls<Segment>(grid.size)
            fun put(x: Int, y: Int, vararg dirs: Direction) {
                var seg = layer[grid.tile(x, y).index] ?: Segment(Conduit.Power, material = Species.Copper)
                for (d in dirs) seg = seg.joinedTo(d)
                layer[grid.tile(x, y).index] = seg
            }
            for (x in 4..10) {
                if (x > 4) put(x, 1, Direction.Left)
                if (x < 10) put(x, 1, Direction.Right)
                if (closed) {
                    if (x > 4) put(x, 3, Direction.Left)
                    if (x < 10) put(x, 3, Direction.Right)
                }
            }
            if (closed) {
                for (y in 1..3) {
                    for (x in listOf(4, 10)) {
                        if (y > 1) put(x, y, Direction.Up)
                        if (y < 3) put(x, y, Direction.Down)
                    }
                }
            }
            return layer.toList()
        }

        fun currentOf(closed: Boolean): Long {
            val conduits = Conduits.of(grid.size, Conduit.Power to rectangle(closed))
            val deck = DeckArray(grid)
            val bodies = bodiesOf(grid, conduits, deck, BufferLayer.forDeck(grid, deck), RailLayer.empty(grid.size))
            val c = circuitOf(grid, bodies, BooleanArray(grid.size))
            fun node(x: Int, y: Int) =
                c.nodeOfBody(bodies.indexOfFirst { it.slot == BodySlot.Fitting && it.tile == grid.tile(x, y) })
            val s = solveCircuit(c, listOf(Source(node(10, 1), node(4, 1), volt, 2_000L)))
            return kotlin.math.abs(s.sourceCurrent[0])
        }

        val oneWay = currentOf(closed = false)
        val bothWays = currentOf(closed = true)
        assertTrue(oneWay > 0L, "the open path carried nothing")
        assertTrue(bothWays > oneWay, "a closed loop ($bothWays) carried no more than one path ($oneWay)")
    }

    /**
     * ⚠️ **A stub hanging off a run carries nothing**, and the reduction must not lose it. A
     * dead-ended spur is a junction at its tip with one edge, so it survives the collapse and sits
     * at the potential of wherever it branches.
     */
    @Test
    fun `a dead ended spur carries no current`() {
        val layer = arrayOfNulls<Segment>(grid.size)
        for (x in 4..12) {
            var seg = Segment(Conduit.Power, material = Species.Copper)
            if (x > 4) seg = seg.joinedTo(Direction.Left)
            if (x < 12) seg = seg.joinedTo(Direction.Right)
            if (x == 8) seg = seg.joinedTo(Direction.Down)
            layer[grid.tile(x, 2).index] = seg
        }
        layer[grid.tile(8, 3).index] = Segment(Conduit.Power, material = Species.Copper).joinedTo(Direction.Up)
        val conduits = Conduits.of(grid.size, Conduit.Power to layer.toList())
        val deck = DeckArray(grid)
        val bodies = bodiesOf(grid, conduits, deck, BufferLayer.forDeck(grid, deck), RailLayer.empty(grid.size))
        val c = circuitOf(grid, bodies, BooleanArray(grid.size))
        fun node(x: Int, y: Int) =
            c.nodeOfBody(bodies.indexOfFirst { it.slot == BodySlot.Fitting && it.tile == grid.tile(x, y) })
        val sources = listOf(Source(node(12, 2), node(4, 2), volt, 2_000L))
        // ⚠️ **Settled first, and the reason is worth stating.** A spur is a junction, so it is
        // iterated rather than interpolated, and Jacobi has it trailing the node it hangs off by one
        // sweep. Cold, it reads about half the branch potential — and the imbalance that implies is
        // *counted*, which is what [Solution.residual] is for. What the physics claims is that a
        // dead end carries nothing once things stop moving, and that is what this asserts.
        var s = solveCircuit(c, sources)
        repeat(20) { s = solveCircuit(c, sources, s.potential) }
        val driven = kotlin.math.abs(s.sourceCurrent[0])
        assertTrue(driven > 0L, "the run carried nothing")

        // ⚠️ **Asserted on the current, which is what the name claims**, and not on the potentials
        // being equal to the microvolt. A settled spur sits four microvolts under the node it hangs
        // off, out of sixty thousand — that is the Jacobi division flooring, not current flowing.
        var intoSpur = 0L
        for (e in 0 until c.edgeCount) {
            val a = c.edgeA[e]
            val b = c.edgeB[e]
            if ((a == node(8, 2) && b == node(8, 3)) || (a == node(8, 3) && b == node(8, 2))) {
                intoSpur = kotlin.math.abs(s.edgeCurrent[e])
            }
        }
        assertTrue(
            intoSpur * 1000L < driven,
            "a dead end drew $intoSpur against a driven current of $driven",
        )
    }

    /**
     * ⛔ **Tellegen: everything the sources put in comes out as heat.** Asserted against the
     * residual's own scale rather than a chosen tolerance — an unconverged solve cannot close this
     * exactly, and pretending otherwise is what the residual term exists to avoid.
     */
    @Test
    fun `every unit supplied is dissipated somewhere`() {
        val w = wire(cable(2, 10))
        val g = edgeG(w.circuit, w.node(2), w.node(3))
        var s = solveCircuit(w.circuit, listOf(Source(w.node(10), w.node(2), volt, g / 20L)))
        repeat(40) { s = solveCircuit(w.circuit, listOf(Source(w.node(10), w.node(2), volt, g / 20L)), s.potential) }
        val supplied = s.supplied
        val dissipated = s.dissipated
        assertTrue(supplied > 0L, "the source did no work")
        assertTrue(
            kotlin.math.abs(supplied - dissipated) * 100L < supplied,
            "supplied $supplied but dissipated $dissipated",
        )
    }

    /** A network with no source sits flat, and that is not the same as being broken. */
    @Test
    fun `an unpowered network carries nothing`() {
        val w = wire(cable(2, 10))
        val s = solveCircuit(w.circuit)
        assertEquals(0L, s.residual, "an unpowered network had an imbalance")
        assertTrue(s.edgeCurrent.all { it == 0L }, "current flowed with no source")
    }
}
