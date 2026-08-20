package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Track no producer grounds, which is oriented by distance to the nearest sink.
 *
 * ⛔ **The fallback this replaces was not merely arbitrary, it was unstable.** Every rule
 * [FlowGraph] has about which way an edge should point is stated in terms of a producer, so with
 * none in the component all of them fall silent and the answer was whatever the last consumer
 * traversed happened to claim. That answer depended on `carrying` — where the packets were standing
 * *this tick* — so a lump moving one tile rebuilt the graph differently and reversed the very edge
 * it had just crossed.
 *
 * From Stu's save, the ore column at (24,30), with a storage four tiles away at (22,30): the packet
 * went up to (24,29), back down to (24,30), up and left to (23,29), all the way back to (24,30),
 * and so on — one segment further each round trip, because a two-way fork is round-robined by
 * [org.emerge.demo.outofspace.world.FlowCursors]. Some forty ticks to cross four tiles, and the
 * packet behind it could not start until it was done.
 *
 * The three properties below are what the distance orientation buys, and the first is the one the
 * bug was: **the graph does not mention matter, so moving a packet cannot change it.**
 */
class FlowNoSourceTest {

    private class Net(val grid: Grid) {
        val tiles = mutableSetOf<TileIndex>()
        private val links = mutableSetOf<Pair<TileIndex, Direction>>()

        fun join(a: TileIndex, dir: Direction): Net = apply {
            val b = grid.neighbour(a, dir)
            require(b != TileIndex.NONE)
            tiles.add(a); tiles.add(b); links.add(a to dir); links.add(b to dir.opposite)
        }

        fun linked(tile: TileIndex, dir: Direction): Boolean = (tile to dir) in links
    }

    private val grid = Grid(12, 8)
    private fun row(y: Int, xs: IntRange) = xs.map { grid.tile(it, y) }

    private fun corridor(y: Int, xs: IntRange): Pair<Net, List<TileIndex>> {
        val n = Net(grid)
        for (x in xs.first until xs.last) n.join(grid.tile(x, y), Direction.Right)
        return n to row(y, xs)
    }

    /**
     * ⛔ **The invariant the ping-pong was a violation of.** The same corridor, the same sink, the
     * lump walked one tile at a time along it: every tile's edges must be identical at every step,
     * because none of them is a fact about where the lump is.
     */
    @Test
    fun `moving a lump along the track does not change a single edge`() {
        val (n, r) = corridor(2, 1..8)
        val sink = r[0]

        fun edges(lump: TileIndex): List<Pair<TileIndex, List<TileIndex>>> {
            val f = FlowGraph.build(n.tiles, emptySet(), setOf(sink), n::linked, grid, carrying = { it == lump })
            return r.map { it to f.successorTiles(it) }
        }

        val baseline = edges(r[7])
        for (at in r.drop(1)) {
            assertEquals(baseline, edges(at), "the graph moved when the lump stood at $at")
        }
    }

    /**
     * The rule itself: with nothing to be grounded by, a packet heads for the closest consumer it
     * can reach. Two sinks, one at each end of a corridor, and the whole line points at whichever
     * of them is nearer — so the run splits at its midpoint, which is the answer distance gives and
     * the one Stu asked for.
     */
    @Test
    fun `a packet goes to the closest sink`() {
        val (n, r) = corridor(3, 1..8)
        val f = FlowGraph.build(n.tiles, emptySet(), setOf(r[0], r[7]), n::linked, grid)

        for (i in 1..3) assertEquals(listOf(r[i - 1]), f.successorTiles(r[i]), "tile $i turns left")
        for (i in 4..6) assertEquals(listOf(r[i + 1]), f.successorTiles(r[i]), "tile $i turns right")
        assertEquals(emptyList(), f.successorTiles(r[0]), "and neither sink has a road out")
        assertEquals(emptyList(), f.successorTiles(r[7]))
    }

    /**
     * ⛔ **Equidistant is a fork, and a fork is still not a cycle.** The middle of an odd corridor
     * is the same distance from both ends; it gets both edges and [FlowCursors] alternates them.
     * What must never happen is the two tiles either side of it pointing at each other.
     */
    @Test
    fun `an equidistant tile forks rather than choosing`() {
        val (n, r) = corridor(4, 1..7)
        val f = FlowGraph.build(n.tiles, emptySet(), setOf(r[0], r[6]), n::linked, grid)

        assertEquals(listOf(r[2], r[4]), f.successorTiles(r[3]), "the middle can go either way")
        assertEquals(listOf(r[1]), f.successorTiles(r[2]), "and its neighbours do not point back at it")
        assertEquals(listOf(r[5]), f.successorTiles(r[4]))
    }

    /**
     * ⛔ **Every edge steps strictly nearer a sink, so the orientation is a DAG.** This is the
     * structural reason the packet cannot wander: not that some rule forbids a reversal, but that
     * there is nowhere for a reversal to be expressed. Asserted over a shape with a loop in it,
     * since a cycle in the *track* is exactly where the old rules had no teeth at all.
     */
    @Test
    fun `the orientation of producer-less track is acyclic`() {
        // A lollipop: a stick from the sink into a four-tile loop.
        val n = Net(grid)
        n.join(grid.tile(1, 6), Direction.Right).join(grid.tile(2, 6), Direction.Right)
        n.join(grid.tile(3, 6), Direction.Down).join(grid.tile(3, 7), Direction.Right)
        n.join(grid.tile(4, 7), Direction.Up).join(grid.tile(4, 6), Direction.Left)
        val sink = grid.tile(1, 6)

        val f = FlowGraph.build(n.tiles, emptySet(), setOf(sink), n::linked, grid, carrying = { it == grid.tile(4, 7) })

        // A depth-first walk over the permitted edges must never meet a tile already on its stack.
        val onStack = mutableSetOf<TileIndex>()
        val done = mutableSetOf<TileIndex>()
        fun walk(at: TileIndex) {
            assertTrue(at !in onStack, "the orientation contains a cycle through $at")
            if (!done.add(at)) return
            onStack.add(at)
            for (next in f.successorTiles(at)) walk(next)
            onStack.remove(at)
        }
        for (tile in n.tiles) walk(tile)
    }
}
