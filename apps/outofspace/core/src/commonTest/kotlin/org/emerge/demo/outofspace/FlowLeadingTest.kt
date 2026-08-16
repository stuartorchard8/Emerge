package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two arrangements that pinned down what **leading** has to mean, worked by hand first and
 * written down here as they were reasoned about.
 *
 * Both exist because a one-way edge makes the order consumers claim track in matter. A consumer
 * walking outward looks for a producer in both directions and claims the edge either way, including
 * the way with no producer down it. Leading is what lets a later consumer take that unjustified
 * claim back without being able to touch a route a producer actually depends on.
 */
class FlowLeadingTest {

    private val grid = Grid(12, 6)

    /** A network drawn as a set of joined tiles, with the flow read back as a picture. */
    private inner class Net {
        val tiles = mutableSetOf<TileIndex>()
        private val links = mutableSetOf<Pair<TileIndex, Direction>>()

        fun join(a: TileIndex, dir: Direction): Net = apply {
            val b = grid.neighbour(a, dir)
            require(b != TileIndex.NONE)
            tiles.add(a); tiles.add(b)
            links.add(a to dir)
            links.add(b to dir.opposite)
        }

        fun linked(tile: TileIndex, dir: Direction): Boolean = (tile to dir) in links

        fun flow(sources: Set<TileIndex>, sinks: Set<TileIndex>): FlowGraph =
            FlowGraph.build(tiles, sources, sinks, ::linked, grid)
    }

    /**
     * Reads a horizontal run back as the arrows used while working these cases out: `A>B` for a tile
     * that may move right into its neighbour, `A<B` for one that may move left.
     */
    private fun picture(f: FlowGraph, names: List<Pair<String, TileIndex>>): String = buildString {
        for ((i, entry) in names.withIndex()) {
            val (name, tile) = entry
            append(name)
            if (i == names.size - 1) break
            val next = names[i + 1].second
            val right = f.successorTiles(tile).contains(next)
            val left = f.successorTiles(next).contains(tile)
            append(if (right && left) "=" else if (right) ">" else if (left) "<" else "-")
        }
    }

    /**
     * `Z-S-A-B-C-D-E`, source at `S`, consumers at `B` and `D`.
     *
     * `B` claims the edge toward `C` while hunting for a producer that is not that way, which under
     * a one-way rule would strand everything behind `B` the moment `B` fills. The leading mark laid
     * down from `S` reaches `B` before `D`'s traversal gets its turn, so `B` keeps pointing onward
     * and `D` is fed in its turn.
     */
    @Test
    fun `a consumer partway along a line does not seize the track behind it`() {
        val z = grid.tile(1, 3); val s = grid.tile(2, 3); val a = grid.tile(3, 3)
        val b = grid.tile(4, 3); val c = grid.tile(5, 3); val d = grid.tile(6, 3)
        val e = grid.tile(7, 3)
        val n = Net()
        for (x in 1 until 7) n.join(grid.tile(x, 3), Direction.Right)
        val names = listOf("Z" to z, "S" to s, "A" to a, "B" to b, "C" to c, "D" to d, "E" to e)

        assertEquals("Z>S>A>B>C>D<E", picture(n.flow(sources = setOf(s), sinks = setOf(b, d)), names))
    }

    @Test
    fun `and it comes out the same whichever consumer is traversed first`() {
        // Seeding is by ascending tile index, so the only way to swap the order is to lay the same
        // line out reversed. Mirrored, the answer must mirror too and nothing else may change.
        val z = grid.tile(1, 3); val s = grid.tile(2, 3); val a = grid.tile(3, 3)
        val b = grid.tile(4, 3); val c = grid.tile(5, 3); val d = grid.tile(6, 3)
        val e = grid.tile(7, 3)
        val n = Net()
        for (x in 1 until 7) n.join(grid.tile(x, 3), Direction.Right)
        // E-D-C-B-A-S-Z read right to left: the same network, traversed in the other order.
        val mirrored = listOf("E" to e, "D" to d, "C" to c, "B" to b, "A" to a, "S" to s, "Z" to z)

        assertEquals("E>D<C<B<A<S<Z", picture(n.flow(sources = setOf(s), sinks = setOf(b, d)), mirrored))
    }

    /**
     * ```
     *   X
     *   |
     * A-B-C-D
     * ```
     * Producers at `A` and `C`, consumers at `D` and `X`.
     *
     * The case that settles what [FlowGraph] does with a walk that steps *past* a producer. Reaching
     * `C` from `B`, the walk carries on and claims `D → C` — a consumer pointing into a producer,
     * backwards. If being fed protected that edge, `D` could never take it back and would starve on
     * a network that plainly ought to feed it.
     */
    @Test
    fun `a consumer starved by a claim made past a producer takes the edge back`() {
        val aT = grid.tile(2, 3)
        val bT = grid.tile(3, 3)
        val cT = grid.tile(4, 3)
        val dT = grid.tile(5, 3)
        val xT = grid.tile(3, 2)
        val n = Net()
            .join(aT, Direction.Right)
            .join(bT, Direction.Right)
            .join(cT, Direction.Right)
            .join(bT, Direction.Up)

        val f = n.flow(sources = setOf(aT, cT), sinks = setOf(dT, xT))
        val names = listOf("A" to aT, "B" to bT, "C" to cT, "D" to dT)

        assertEquals("A>B>C>D", picture(f, names), "the row")
        assertEquals(listOf(xT), f.successorTiles(bT).filter { it == xT }, "B feeds the tank above it")
        assertEquals(emptyList(), f.successorTiles(xT), "and the tank is the end of the line")
    }
}
