package org.emerge.demo.fluidlab.world.fluid

import org.emerge.demo.fluidlab.world.Grid

/**
 * Staggered grid: scalars at tile centres, vectors on faces between tiles.
 * Prevents checkerboard pressure (centred velocity decouples neighbours).
 * x-edge (x,y): vertical face between (x-1,y) and (x,y). y-edge (x,y): horizontal face between (x,y-1) and (x,y).
 * +y = down (screen-down = gravity-down). before = negative side, after = positive side (-1 = grid boundary).
 * Pure geometry, immutable, derived from VesselState.
 */
class EdgeGrid(val grid: Grid) {

    /** Faces with an x normal: one more column than there are tiles. */
    val xEdgeCount: Int = (grid.width + 1) * grid.height

    /** Faces with a y normal: one more row than there are tiles. */
    val yEdgeCount: Int = grid.width * (grid.height + 1)

    private val xStride = grid.width + 1

    // ── Naming a face ──

    /** The face between tile `(x-1, y)` and tile `(x, y)`. [x] is in `0..width`. */
    fun xEdge(x: Int, y: Int): Int = y * xStride + x

    /** The face between tile `(x, y-1)` and tile `(x, y)`. [y] is in `0..height`. */
    fun yEdge(x: Int, y: Int): Int = y * grid.width + x

    fun xOfXEdge(edge: Int): Int = edge % xStride
    fun yOfXEdge(edge: Int): Int = edge / xStride
    fun xOfYEdge(edge: Int): Int = edge % grid.width
    fun yOfYEdge(edge: Int): Int = edge / grid.width

    // ── The tiles either side ──

    /** Tile on the -x side of an x-edge, or -1 if that is off the grid. */
    fun xEdgeBefore(edge: Int): Int {
        val x = xOfXEdge(edge)
        return if (x == 0) -1 else grid.index(x - 1, yOfXEdge(edge))
    }

    /** Tile on the +x side of an x-edge, or -1 if that is off the grid. */
    fun xEdgeAfter(edge: Int): Int {
        val x = xOfXEdge(edge)
        return if (x == grid.width) -1 else grid.index(x, yOfXEdge(edge))
    }

    /** Tile on the -y side (above, on screen) of a y-edge, or -1 if that is off the grid. */
    fun yEdgeBefore(edge: Int): Int {
        val y = yOfYEdge(edge)
        return if (y == 0) -1 else grid.index(xOfYEdge(edge), y - 1)
    }

    /** Tile on the +y side (below, on screen) of a y-edge, or -1 if that is off the grid. */
    fun yEdgeAfter(edge: Int): Int {
        val y = yOfYEdge(edge)
        return if (y == grid.height) -1 else grid.index(xOfYEdge(edge), y)
    }

    /** True when one side of this face is off the grid — i.e. it opens onto space. */
    fun isXBoundary(edge: Int): Boolean = xOfXEdge(edge).let { it == 0 || it == grid.width }

    /** True when one side of this face is off the grid — i.e. it opens onto space. */
    fun isYBoundary(edge: Int): Boolean = yOfYEdge(edge).let { it == 0 || it == grid.height }

    // ── A tile's own four faces ──

    fun leftEdgeOf(tile: Int): Int = xEdge(grid.xOf(tile), grid.yOf(tile))
    fun rightEdgeOf(tile: Int): Int = xEdge(grid.xOf(tile) + 1, grid.yOf(tile))

    /** The face above the tile on screen — `Direction.Up` is `dy = -1`. */
    fun upEdgeOf(tile: Int): Int = yEdge(grid.xOf(tile), grid.yOf(tile))

    /** The face below the tile on screen. */
    fun downEdgeOf(tile: Int): Int = yEdge(grid.xOf(tile), grid.yOf(tile) + 1)

    override fun equals(other: Any?): Boolean =
        this === other || (other is EdgeGrid && grid == other.grid)

    override fun hashCode(): Int = grid.hashCode()
}
