package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.fluid.ApertureField
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.MomentumField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The staggered grid, its apertures, and the momentum stored on it.
 *
 * Nothing here moves any fluid — this is increment A, which is layout only. What it is protecting is
 * the numbering: every pass that follows iterates over faces and asks who is on either side, so an
 * off-by-one in [EdgeGrid] would surface as a fluid sim that is subtly wrong everywhere at once and
 * obviously wrong nowhere. Cheaper to pin it down while it is still arithmetic.
 */
class FluidFieldTest {

    private val grid = Grid(5, 4)
    private val edges = EdgeGrid(grid)

    @Test
    fun `there is one more column of faces than of tiles, and one more row`() {
        assertEquals(6 * 4, edges.xEdgeCount)
        assertEquals(5 * 5, edges.yEdgeCount)
    }

    @Test
    fun `a face knows which two tiles it separates`() {
        val e = edges.xEdge(2, 1)
        assertEquals(grid.index(1, 1), edges.xEdgeBefore(e))
        assertEquals(grid.index(2, 1), edges.xEdgeAfter(e))

        val f = edges.yEdge(3, 2)
        assertEquals(grid.index(3, 1), edges.yEdgeBefore(f))
        assertEquals(grid.index(3, 2), edges.yEdgeAfter(f))
    }

    @Test
    fun `neighbouring tiles name the same shared face`() {
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width - 1) {
                assertEquals(
                    edges.rightEdgeOf(grid.index(x, y)),
                    edges.leftEdgeOf(grid.index(x + 1, y)),
                    "vertical face between ($x,$y) and (${x + 1},$y)",
                )
            }
        }
        // +y is down, so the face below one tile is the face above the tile under it.
        for (y in 0 until grid.height - 1) {
            for (x in 0 until grid.width) {
                assertEquals(
                    edges.downEdgeOf(grid.index(x, y)),
                    edges.upEdgeOf(grid.index(x, y + 1)),
                    "horizontal face between ($x,$y) and ($x,${y + 1})",
                )
            }
        }
    }

    @Test
    fun `faces on the rim of the grid have space on one side`() {
        val left = edges.xEdge(0, 2)
        assertTrue(edges.isXBoundary(left))
        assertEquals(-1, edges.xEdgeBefore(left))
        assertEquals(grid.index(0, 2), edges.xEdgeAfter(left))

        val right = edges.xEdge(grid.width, 2)
        assertTrue(edges.isXBoundary(right))
        assertEquals(grid.index(grid.width - 1, 2), edges.xEdgeBefore(right))
        assertEquals(-1, edges.xEdgeAfter(right))

        assertFalse(edges.isXBoundary(edges.xEdge(1, 2)))
    }

    @Test
    fun `every face index is reachable and named exactly once`() {
        val seenX = BooleanArray(edges.xEdgeCount)
        for (y in 0 until grid.height) {
            for (x in 0..grid.width) {
                val e = edges.xEdge(x, y)
                assertFalse(seenX[e], "x-face ($x,$y) collides with an earlier one")
                seenX[e] = true
                assertEquals(x, edges.xOfXEdge(e))
                assertEquals(y, edges.yOfXEdge(e))
            }
        }
        assertTrue(seenX.all { it })

        val seenY = BooleanArray(edges.yEdgeCount)
        for (y in 0..grid.height) {
            for (x in 0 until grid.width) {
                val e = edges.yEdge(x, y)
                assertFalse(seenY[e], "y-face ($x,$y) collides with an earlier one")
                seenY[e] = true
                assertEquals(x, edges.xOfYEdge(e))
                assertEquals(y, edges.yOfYEdge(e))
            }
        }
        assertTrue(seenY.all { it })
    }

    // ── Apertures ──

    /** A 5x5 grid with a ring of hull round the middle, so one tile is sealed in. */
    private fun walledInTile(): Triple<Grid, EdgeGrid, ApertureField> {
        val g = Grid(5, 5)
        val machines = arrayOfNulls<Machine>(g.size)
        for (x in 1..3) for (y in 1..3) {
            if (x == 2 && y == 2) continue
            machines[g.index(x, y)] = Hull()
        }
        val structure = StructureMap.derive(g, machines.toList())
        val e = EdgeGrid(g)
        return Triple(g, e, ApertureField.derive(e, structure))
    }

    @Test
    fun `a tile with hull on all four sides has no open face`() {
        val (g, e, apertures) = walledInTile()
        val sealed = g.index(2, 2)
        assertFalse(apertures.isXOpen(e.leftEdgeOf(sealed)))
        assertFalse(apertures.isXOpen(e.rightEdgeOf(sealed)))
        assertFalse(apertures.isYOpen(e.upEdgeOf(sealed)))
        assertFalse(apertures.isYOpen(e.downEdgeOf(sealed)))
    }

    @Test
    fun `open space is fully open, including out over the rim`() {
        val (g, e, apertures) = walledInTile()
        // Two vacuum tiles along the top row.
        assertEquals(ApertureField.OPEN, apertures.xAt(e.xEdge(1, 0)))
        // The rim itself: gas has to be able to leave, or thrust has nowhere to go.
        assertEquals(ApertureField.OPEN, apertures.xAt(e.xEdge(0, 0)))
        assertEquals(ApertureField.OPEN, apertures.xAt(e.xEdge(g.width, 4)))
        assertEquals(ApertureField.OPEN, apertures.yAt(e.yEdge(0, 0)))
    }

    // ── Momentum ──

    @Test
    fun `still air carries no momentum`() {
        val field = MomentumField.still(edges)
        assertEquals(0L, field.totalX)
        assertEquals(0L, field.totalY)
        assertEquals(0L, field.velocityX(edges.xEdge(2, 1), LongArray(grid.size) { 1000L }).raw)
    }

    @Test
    fun `velocity is momentum over the mass on the face`() {
        val mx = LongArray(edges.xEdgeCount)
        val interior = edges.xEdge(2, 1)
        mx[interior] = 500L
        val field = MomentumField.of(edges, mx, LongArray(edges.yEdgeCount))
        val grams = LongArray(grid.size) { 1000L }

        // 500 g·tiles/tick carried by 1000 g is half a tile per tick.
        assertEquals(MomentumField.SPEED_LIMIT_RAW / 2, field.velocityX(interior, grams).raw)
        assertEquals(500L, field.totalX)
    }

    @Test
    fun `a rim face is carried by the one tile it actually touches`() {
        val mx = LongArray(edges.xEdgeCount)
        val rim = edges.xEdge(0, 2)
        mx[rim] = 500L
        val field = MomentumField.of(edges, mx, LongArray(edges.yEdgeCount))
        val grams = LongArray(grid.size) { 1000L }

        // Half a tile per tick, not a whole one: the vacuum beyond is not half a cell of nothing
        // dragging the average down.
        assertEquals(MomentumField.SPEED_LIMIT_RAW / 2, field.velocityX(rim, grams).raw)
    }

    @Test
    fun `mass in the face and none in the tiles is not a division by zero`() {
        val mx = LongArray(edges.xEdgeCount) { 900L }
        val field = MomentumField.of(edges, mx, LongArray(edges.yEdgeCount))
        assertEquals(0L, field.velocityX(edges.xEdge(2, 1), LongArray(grid.size)).raw)
    }

    @Test
    fun `the CFL limit is a tile per tick, and it can be seen being crossed`() {
        val grams = LongArray(grid.size) { 1000L }
        val slow = LongArray(edges.xEdgeCount).also { it[edges.xEdge(2, 1)] = 900L }
        assertTrue(MomentumField.of(edges, slow, LongArray(edges.yEdgeCount)).isCflSafe(grams))

        val fast = LongArray(edges.xEdgeCount).also { it[edges.xEdge(2, 1)] = 1100L }
        assertFalse(MomentumField.of(edges, fast, LongArray(edges.yEdgeCount)).isCflSafe(grams))
    }
}
