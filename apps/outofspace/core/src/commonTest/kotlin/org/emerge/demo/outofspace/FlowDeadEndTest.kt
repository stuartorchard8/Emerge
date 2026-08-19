package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A consumer at the end of a cul-de-sac, and the walk that used to rob it.
 *
 * ⛔ **The walk hunts upstream, so it must never step into a sink it can only come back out of.**
 * Claiming an edge *out of* such a tile means revoking the edge *into* it — the one edge feeding it —
 * and the walk gains nothing by it, because there is nothing beyond a dead end to find.
 *
 * Found in Stu's save: a ghost rail one tile from a belt carrying 100kg of the very iron it was short
 * of, pointing at its own supplier for ever, twice on one chain.
 */
class FlowDeadEndTest {

    private val grid = Grid(6, 6)

    private class Net(val grid: Grid) {
        val tiles = mutableSetOf<TileIndex>()
        val links = mutableSetOf<Pair<TileIndex, Direction>>()

        fun join(a: TileIndex, dir: Direction): Net = apply {
            val b = grid.neighbour(a, dir)
            require(b != TileIndex.NONE)
            tiles.add(a); tiles.add(b); links.add(a to dir); links.add(b to dir.opposite)
        }

        fun linked(tile: TileIndex, dir: Direction): Boolean = (tile to dir) in links

        /** Everything joined to [from], however far round. */
        fun reaches(from: TileIndex): Set<TileIndex> {
            val seen = mutableSetOf(from)
            val queue = ArrayDeque(listOf(from))
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                for (dir in Direction.entries) {
                    val next = grid.neighbour(at, dir)
                    if (!linked(at, dir) || next !in tiles || !seen.add(next)) continue
                    queue.addLast(next)
                }
            }
            return seen
        }

        fun flow(sources: Set<TileIndex>, sinks: Set<TileIndex>): FlowGraph =
            FlowGraph.build(tiles, sources, sinks, ::linked, grid)
    }

    /**
     * The whole bug in three tiles: `G—X—Y`, with `G` and `X` both consumers.
     *
     * `G` sorts first and claims `X→left`, the only edge that can ever feed it. `X`'s traversal then
     * walks *into* `G` hunting for a producer, finds a cul-de-sac, and takes that edge back on the
     * way in — so the consumer that was fed first ends up pointing at its own supplier, and the one
     * that walked second gains a road to nowhere.
     *
     * ⚠️ The producer is below `X`, so **no producer lies up the spur at all** — which is exactly
     * what makes `X`'s step into `G` pointless. The leading mark does not save `G` here: it runs
     * downstream from the producer along claimed edges, and the spur is not on that road.
     */
    @Test
    fun `a dead-end consumer keeps the edge that feeds it`() {
        val g = grid.tile(1, 2)
        val x = grid.tile(1, 3)
        val n = Net(grid)
        n.join(g, Direction.Down)
        // The run carries on past X to a producer, which is what makes the spur worth walking: the
        // iron X is waiting on is real, and it arrives from below.
        n.join(x, Direction.Down)
        val p = grid.tile(1, 4)
        val f = n.flow(sources = setOf(p), sinks = setOf(g, x))

        assertFalse(f.allows(g, Direction.Down), "the ghost points back into its own supplier")
        assertTrue(f.allows(x, Direction.Up), "the ghost's only supplier stopped pointing at it")
        assertEquals(listOf(x), f.feeders(g), "the ghost is fed by nobody")
    }

    /**
     * ⚠️ **A dead end that *produces* is the opposite case and must still be entered.** A storage's
     * output stub is exactly that shape, and it is where material comes from — pruning it would
     * strand every machine that feeds the network from a spur.
     */
    @Test
    fun `a dead-end producer is still walked into`() {
        val p = grid.tile(1, 2)
        val x = grid.tile(2, 2)
        val s = grid.tile(4, 2)
        val n = Net(grid)
        for (i in 1 until 4) n.join(grid.tile(i, 2), Direction.Right)
        val f = n.flow(sources = setOf(p), sinks = setOf(s))

        assertTrue(f.allows(p, Direction.Right), "the producer on a spur cannot reach the network")
        assertTrue(f.allows(x, Direction.Right), "the route out of the spur stops one tile along")
    }

    /**
     * The rule stated as an invariant over shapes nobody hand-picked, because the arrangement that
     * bit was one nobody would have thought to draw: the claim order that does the damage depends on
     * which consumer sorts first and on how far each traversal happens to get.
     *
     * A sink with exactly one way in must never be allowed to send material out of it — there is
     * nowhere for that material to go, and the permission can only have come from an edge stolen off
     * the tile that was feeding it.
     */
    @Test
    fun `no dead-end consumer ever points at its only neighbour`() {
        val rnd = Random(7)
        var checked = 0
        repeat(3000) {
            val n = Net(grid)
            for (y in 0 until 5) for (x in 0 until 5) {
                for (d in listOf(Direction.Right, Direction.Down)) {
                    if (rnd.nextInt(100) < 42) n.join(grid.tile(x, y), d)
                }
            }
            if (n.tiles.size < 4) return@repeat
            val all = n.tiles.sortedBy { it.index }
            val sources = setOf(all[rnd.nextInt(all.size)])
            val sinks = (0..rnd.nextInt(2)).map { all[rnd.nextInt(all.size)] }.toSet() - sources
            if (sinks.isEmpty()) return@repeat
            val f = n.flow(sources, sinks)

            for (sink in sinks) {
                val ways = Direction.entries.filter { n.linked(sink, it) && grid.neighbour(sink, it) in n.tiles }
                if (ways.size != 1) continue
                // ⚠️ **Only where material could actually arrive.** With no producer reachable at
                // all, nothing justifies any direction over any other and the graph is free to
                // point wherever it likes — there is nothing to move down it either way.
                if (sources.none { it in n.reaches(sink) }) continue
                checked++
                assertFalse(
                    f.allows(sink, ways[0]),
                    "a dead-end consumer at $sink sends ${ways[0]}, the only way it can be fed",
                )
            }
        }
        assertTrue(checked > 100, "the search stopped generating dead-end consumers ($checked)")
    }
}
