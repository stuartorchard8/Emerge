package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A consumer on the far side of a run of **ghost rail**, and the corridor it claimed straight
 * through them.
 *
 * ⛔ **Material does not come out the far side of unpaid track.** A ghost rail is a free length of
 * track until it is paid for, so a player must not be able to route a network over one — the
 * anti-exploit the whole ghost design rests on, and until now stated only one lump at a time, at the
 * door, by [org.emerge.demo.outofspace.world.Acceptance.stopsTraffic]. The *graph* went on claiming
 * routes straight across, and a claim it cannot honour is not free: it costs the ghosts their feed.
 *
 * From Stu's save (2026-08-20): a titanium storage at the left end of a corridor, four rail ghosts
 * next to it, and a storage construction site away to the right. The site's walk ran up the
 * corridor, across every ghost, and reached the storage. The storage is a real producer, so it then
 * conferred [FlowGraph] `leading` on the whole run — which is unrevocable — and every ghost's only
 * edge pointed *away* from itself toward a site the titanium could never reach. The iron actually
 * arriving from below the junction beside them could not turn back. Cutting a single rail four tiles
 * away, at the site's own door, freed them; that is what a claim with nothing to honour it costs.
 *
 * ⚠️ **A wall may still feed another wall, and must.** What crosses a ghost is what the ghost is
 * made of, because that is the only thing it admits — so the tile beyond has a claim on it exactly
 * when it is unpaid track too. That is a drawn run building itself several lumps deep. Forbidding it
 * outright drops the run to single file, which `GhostTest` measures directly: it idled four rail
 * periods with iron on the belt and ghosts waiting.
 *
 * ```
 *                        K        <- storage construction site
 *                        |
 * S - g1 - g2 - g3 - g4 - J - a - b
 *                        |
 *                        P        <- the iron that is actually available
 * ```
 */
class FlowWallTest {

    private val grid = Grid(12, 6)

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

    private val s = grid.tile(2, 3)
    private val g1 = grid.tile(3, 3)
    private val g2 = grid.tile(4, 3)
    private val g3 = grid.tile(5, 3)
    private val g4 = grid.tile(6, 3)
    private val j = grid.tile(7, 3)
    private val a = grid.tile(8, 3)
    private val b = grid.tile(9, 3)
    private val k = grid.tile(9, 2)
    private val p = grid.tile(7, 4)

    private val ghosts = setOf(g1, g2, g3, g4)

    /** ⚠️ `K` sorts **before** every ghost, so its walk claims the corridor first. That is the case. */
    private fun flow(walls: Set<TileIndex>): FlowGraph {
        val n = Net(grid)
            .join(s, Direction.Right).join(g1, Direction.Right).join(g2, Direction.Right)
            .join(g3, Direction.Right).join(g4, Direction.Right).join(j, Direction.Right)
            .join(a, Direction.Right).join(b, Direction.Up).join(j, Direction.Down)
        return FlowGraph.build(
            n.tiles, setOf(s, p), setOf(g1, g2, g3, g4, k), n::linked, grid,
            walls = walls,
        )
    }

    /**
     * ⛔ **The case itself.** The junction beside the ghosts forks — it feeds the run on its left
     * and the site beyond on its right — instead of pointing wholly away from a run that has no
     * other way of being fed.
     */
    @Test
    fun `a site beyond a run of ghosts does not claim its road through them`() {
        val f = flow(ghosts)
        assertTrue(Direction.Left in f.successorDirections(j), "J must be able to feed the ghost run")
        assertTrue(Direction.Right in f.successorDirections(j), "and must still feed the site beyond")
        for (g in listOf(g2, g3, g4)) {
            assertEquals(listOf(Direction.Left), f.successorDirections(g), "the run builds away from its feed")
        }
    }

    /**
     * The other half of the rule: unpaid track delivers into unpaid track, so a drawn run still
     * builds several lumps deep rather than one at a time. `g1` is the far end and has finished
     * track beyond it, so it is where the run stops.
     */
    @Test
    fun `a wall feeds a wall but never delivers into finished track`() {
        val f = flow(ghosts)
        assertEquals(emptyList(), f.successorDirections(g1), "a ghost does not deliver out into paid track")
        assertTrue(Direction.Left in f.successorDirections(g2), "but it does feed the ghost beyond it")
    }

    /**
     * ⚠️ **This case no longer reproduces (2026-08-22), and is kept saying so.** It recorded what
     * the save did before the wall rule existed: told nothing about walls, the site's walk took the
     * whole corridor and the junction pointed away from the ghosts entirely. A producer no longer
     * confers `leading` on a consumer standing next to it, so `s` cannot freeze `g1` and through it
     * the run, and this fixture now comes out the same with walls and without.
     *
     * That does **not** retire the wall rule: what the rule is for is the ghosts' own feed, asserted
     * in the two cases above with the walls declared. It does mean this fixture is no longer a
     * demonstration of the bug, and a smaller one that still is has not been found.
     */
    @Test
    fun `the corridor is no longer seized when the graph is told nothing about walls`() {
        val f = flow(emptySet())
        assertEquals(listOf(Direction.Left, Direction.Right), f.successorDirections(j))
        for (g in listOf(g2, g3, g4)) assertEquals(listOf(Direction.Left), f.successorDirections(g))
    }
}
