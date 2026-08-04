package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.world.Grid

/**
 * The staggered grid: scalars at tile centres, vectors on the **faces between tiles**.
 *
 * This is the one piece of structure taken wholesale from Sebastian Lague's smoke sim, and it is
 * taken because the alternative has a known pathology. Storing velocity at tile centres — the
 * obvious layout — decouples a cell's pressure from its immediate neighbours, because a centred
 * difference skips over them. The field then splits into two independent interleaved lattices and
 * settles into a checkerboard which never equalises. That is not a hypothetical: it is exactly the
 * failure `AirField.STABLE_SHARE` documents having hit from the other direction. Putting the vector
 * quantity *on the face* makes every difference a difference between actual neighbours, and the
 * checkerboard has nowhere to live.
 *
 * The cost is that there are two sets of faces with different counts, and that a face's identity is
 * "between these two tiles" rather than "at this tile". Hence this class: everything that follows
 * iterates over **edges**, and this is the only place that knows how an edge is numbered.
 *
 * ### The conventions, once, so nothing downstream has to guess
 *
 * - An **x-edge** `(x, y)` is the vertical face between tile `(x-1, y)` and tile `(x, y)`. `x` runs
 *   `0..width` inclusive, so there is one more column of faces than of tiles.
 * - A **y-edge** `(x, y)` is the horizontal face between tile `(x, y-1)` and tile `(x, y)`. `y` runs
 *   `0..height` inclusive.
 * - Positive is toward **+x** and **+y**. Since [org.emerge.demo.outofspace.world.Direction] has +y
 *   pointing *down* — the world is side-on and screen-down is gravity-down — positive y-momentum is
 *   momentum heading toward the floor. The one place that surprises people, and it surprises them
 *   here rather than in four separate call sites.
 * - `before` is the tile on the negative side of a face, `after` the tile on the positive side.
 *   Either may be `-1`, meaning the face is on the boundary of the grid and looks out at space.
 *
 * Pure geometry. It holds no field data and never changes, so a [VesselState][
 * org.emerge.demo.outofspace.world.VesselState] can derive one and hand it around freely.
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
