package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A consumer on a **loop**, and the walk that came round it and took its own feed edge away.
 *
 * ⛔ **Every rule [FlowGraph] has about which way an edge should point is stated in terms of a
 * producer, and on a cycle none of them has any teeth.** No edge of a loop is a bridge, so
 * `beyond` answers true in every direction and every revocation is permitted. That is stated
 * plainly in the class and was thought harmless: a loop has no right answer, so the greedy one
 * would do. It is not harmless when the walk that goes round the loop is the consumer's *own*, and
 * what it takes back at the end of the lap is the edge it granted on its first step.
 *
 * From Stu's save (2026-08-20): a rail marked for deconstruction at `D`, a **ghost rail** at `G`
 * one tile from the storage output `S` that also feeds it, and a four-tile loop of track above.
 * `G`'s traversal granted `SE → G`, walked up through the loop, came back at `G` from the far side
 * and reversed that grant — leaving `G` an outgoing edge pointing up into the loop and the loop
 * with no way down into `G`. `D`'s iron went up into the loop and died on the dead end the reversal
 * had made; the ghost sat at 23% for ever.
 *
 * ⚠️ **The storage below is load-bearing to the bug, which is why it took a shape this size to
 * show it.** `S` is a real producer *past* `G`, so looking down from `SE` there genuinely is a
 * producer beyond the edge — and that is the only reason the guard that refuses a consumer an
 * outgoing edge let this one through. Delete `S` and the same loop behaves.
 *
 * ```
 *   NW -- NE
 *   |     |
 *   SW -- SE
 *   |     |
 * left    G      <- ghost rail, the sink
 *   |     |
 *   D     b1     <- D is deconstructing: a producer
 *         |
 *         b2 -- S   <- storage output: a producer
 * ```
 */
class FlowLoopSeedTest {

    private val grid = Grid(6, 8)

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

    private val nw = grid.tile(0, 1)
    private val ne = grid.tile(1, 1)
    private val sw = grid.tile(0, 2)
    private val se = grid.tile(1, 2)
    private val left = grid.tile(0, 3)
    private val g = grid.tile(1, 3)
    private val d = grid.tile(0, 4)
    private val b1 = grid.tile(1, 4)
    private val b2 = grid.tile(1, 5)
    private val s = grid.tile(2, 5)

    private fun flow(): FlowGraph {
        val n = Net(grid)
            .join(nw, Direction.Right)
            .join(nw, Direction.Down)
            .join(ne, Direction.Down)
            .join(sw, Direction.Right)
            .join(sw, Direction.Down)
            .join(left, Direction.Down)
            .join(se, Direction.Down)
            .join(g, Direction.Down)
            .join(b1, Direction.Down)
            .join(b2, Direction.Right)
        return FlowGraph.build(n.tiles, setOf(d, s), setOf(g), n::linked, grid)
    }

    /**
     * ⛔ **The case itself.** A walk never hands its own seed a road out, so the lap round the loop
     * arrives back at `G`, finds the seed, and leaves the feed edge alone.
     */
    @Test
    fun `a loop does not let a consumers own walk reverse the edge that feeds it`() {
        val f = flow()
        assertTrue(Direction.Down in f.successorDirections(se), "SE must still feed the ghost")
        assertEquals(emptyList(), f.successorDirections(g), "the ghost is a dead end: it sends nowhere")
    }

    /**
     * And the point of it: the deconstructing rail's iron has a route to the ghost. Following the
     * permitted edges from `D` has to arrive, rather than run out on a tile with nowhere to send.
     */
    @Test
    fun `the deconstructing rail can reach the ghost`() {
        val f = flow()
        val seen = mutableSetOf<TileIndex>()
        val queue = ArrayDeque(listOf(d))
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (!seen.add(at)) continue
            queue.addAll(f.successorTiles(at))
        }
        assertTrue(g in seen, "the ghost is unreachable from the rail being taken up: $seen")
    }
}
