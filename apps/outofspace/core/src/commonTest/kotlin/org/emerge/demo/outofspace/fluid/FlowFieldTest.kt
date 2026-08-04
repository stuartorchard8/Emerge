package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.FlowField
import org.emerge.demo.outofspace.world.fluid.MomentumField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tile-centre reconstruction of the face velocities.
 *
 * This is a *view*, so it cannot be wrong about the physics — but it can be wrong about which way is
 * which, and a flow overlay that draws the world's air going the wrong way is worse than no overlay
 * at all, because it will be believed. So the signs are pinned here rather than eyeballed on screen.
 */
class FlowFieldTest {

    private val grid = Grid(4, 4)
    private val edges = EdgeGrid(grid)

    /** A field with one gram-tile-per-tick of momentum on every face, so velocities are the mass ratio. */
    private fun flowWith(build: (LongArray, LongArray) -> Unit): FlowField {
        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)
        build(mx, my)
        val tileGrams = LongArray(grid.size) { 1_000L }
        return FlowField.derive(edges, MomentumField.of(edges, mx, my), tileGrams)
    }

    @Test
    fun `still air has no flow anywhere`() {
        val flow = flowWith { _, _ -> }
        for (tile in 0 until grid.size) assertEquals(0f, flow.speedAt(tile))
    }

    @Test
    fun `air crossing a tile rightwards reads as moving along positive x`() {
        val tile = grid.index(1, 1)
        val flow = flowWith { mx, _ ->
            mx[edges.leftEdgeOf(tile)] = 500L
            mx[edges.rightEdgeOf(tile)] = 500L
        }
        assertTrue(flow.xAt(tile) > 0L, "flow should be toward +x")
        assertEquals(0L, flow.yAt(tile))
    }

    /**
     * The one that would be silently inverted. `+y` is *down* on this grid — the world is side-on —
     * so momentum toward `+y` is air heading for the floor, and the overlay has to draw it that way.
     */
    @Test
    fun `positive y momentum is air heading downward`() {
        val tile = grid.index(1, 1)
        val flow = flowWith { _, my ->
            my[edges.upEdgeOf(tile)] = 500L
            my[edges.downEdgeOf(tile)] = 500L
        }
        assertTrue(flow.yAt(tile) > 0L, "positive y is toward the floor")
        assertEquals(0L, flow.xAt(tile))
    }

    /**
     * Documenting the reconstruction's known blind spot rather than pretending it does not exist.
     *
     * Air arriving from both sides at once is *compressing*, not moving, and averaging the two faces
     * gives zero — correctly, since the tile's contents are on the whole going nowhere. Divergence is
     * a different question and the pressure solve is what asks it; a picture that tried to show both
     * at once would show neither clearly.
     */
    @Test
    fun `air squeezed in from both sides reads as still, because on the whole it is`() {
        val tile = grid.index(2, 2)
        val flow = flowWith { mx, _ ->
            mx[edges.leftEdgeOf(tile)] = 500L      // in from the left
            mx[edges.rightEdgeOf(tile)] = -500L    // in from the right
        }
        assertEquals(0L, flow.xAt(tile))
    }

    @Test
    fun `speed is in tiles per tick, so a face at the CFL limit reads as one`() {
        val tile = grid.index(1, 1)
        // Momentum equal to the face mass is exactly one tile per tick.
        val flow = flowWith { mx, _ ->
            mx[edges.leftEdgeOf(tile)] = 1_000L
            mx[edges.rightEdgeOf(tile)] = 1_000L
        }
        assertEquals(1f, flow.speedAt(tile), 0.001f)
    }

    @Test
    fun `peak speed finds the fastest tile in the field`() {
        val slow = grid.index(0, 0)
        val fast = grid.index(3, 3)
        val flow = flowWith { mx, _ ->
            mx[edges.leftEdgeOf(slow)] = 100L
            mx[edges.rightEdgeOf(slow)] = 100L
            mx[edges.leftEdgeOf(fast)] = 800L
            mx[edges.rightEdgeOf(fast)] = 800L
        }
        assertEquals(flow.speedAt(fast), flow.peakSpeed())
    }
}
