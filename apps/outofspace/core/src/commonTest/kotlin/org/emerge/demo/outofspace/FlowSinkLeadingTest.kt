package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A consumer with a producer next door still has to be able to feed itself.
 *
 * **Leading** is conferred on a source's successors, and a source does not ask whether its successor
 * happens to be a consumer. So a machine that stands beside a producer begins its own traversal
 * already marked — and the mark is read as "this route is justified, do not re-litigate it", which
 * is exactly the wrong thing to say about the one walk in which that consumer asks to be fed.
 *
 * Stu's save, 2026-08-22: a storage input at `(18,28)` with a processor's tailings output at
 * `(17,28)` beside it, its feed tile at `(18,29)` and a corridor running east to a storage output
 * away at `(23,19)`. An unrelated construction site's walk had claimed the corridor eastward; the
 * tailings then made the storage leading; and the storage was thereafter unable to say a word about
 * either its own door or the corridor beyond it. The packet one tile from the storage sat still for
 * good, with a producer far upstream that should have been driving it straight in.
 */
class FlowSinkLeadingTest {

    private val grid = Grid(12, 6)

    private val tailings = grid.tile(1, 1)   // producer, next door to the storage
    private val storage = grid.tile(2, 1)    // the starved consumer
    private val door = grid.tile(2, 2)       // the tile it is fed from
    private val mid = grid.tile(3, 2)
    private val far = grid.tile(4, 2)
    private val supply = grid.tile(5, 2)     // producer away up the corridor
    private val site = grid.tile(5, 0)       // consumer that walks first, and claims the corridor

    private val links = mutableSetOf<Pair<TileIndex, Direction>>()
    private val tiles = mutableSetOf<TileIndex>()

    private fun join(a: TileIndex, dir: Direction) {
        val b = grid.neighbour(a, dir)
        tiles.add(a); tiles.add(b)
        links.add(a to dir); links.add(b to dir.opposite)
    }

    private fun build(): FlowGraph {
        join(tailings, Direction.Right)
        join(storage, Direction.Down)
        join(door, Direction.Right)
        join(mid, Direction.Right)
        join(far, Direction.Right)
        join(supply, Direction.Up)
        join(grid.tile(5, 1), Direction.Up)
        return FlowGraph.build(
            tiles,
            sources = setOf(tailings, supply),
            sinks = setOf(site, storage),
            linked = { t, d -> (t to d) in links },
            grid = grid,
        )
    }

    /**
     * `site` sorts first and its walk runs the corridor west, past the storage's door and through
     * the storage to the tailings — which then confers [leading] on the storage. Everything the
     * storage needs to say comes after that.
     */
    @Test
    fun `a sink beside a producer still claims its own door`() {
        val f = build()
        assertTrue(storage in f.successorTiles(door), "the storage's door must point into it")
        assertTrue(door !in f.successorTiles(storage), "and the storage must not point back out")
    }

    /** And the corridor beyond the door turns round with it, all the way to the supply. */
    @Test
    fun `and the corridor upstream of it turns to face it`() {
        val f = build()
        assertEquals(listOf(door), f.successorTiles(mid))
        assertEquals(listOf(mid), f.successorTiles(far))
    }
}
