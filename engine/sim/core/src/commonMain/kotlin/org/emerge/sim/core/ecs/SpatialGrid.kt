package org.emerge.sim.core.ecs

/**
 * Uniform 2D spatial hash keyed by raw `Long` world coordinates. Used to replace
 * O(n²) all-pairs sweeps with O(n·k) neighbour queries, where `k` is the average
 * occupancy of a 3×3 cell window around any body.
 *
 * Usage pattern per tick:
 * ```
 * val grid = SpatialGrid(cellSize)
 * for (i in 0 until n) grid.insert(i, xRaw[i], yRaw[i])
 * for (i in 0 until n) grid.forEachNeighbour(xRaw[i], yRaw[i]) { j -> ... }
 * ```
 *
 * **Cell size.** Must be `>= 2 * maxBodyRadius` in whatever units `xRaw`/`yRaw`
 * use, so that any contact pair's centres fall within the 3×3 cell window of
 * either body. Undersized cells silently miss pairs; oversized cells just cost
 * extra neighbour lookups.
 *
 * **No torus wrap.** The current physics code subtracts raw `Long` positions
 * without wrapping, so pairs that would only touch across the torus seam are
 * not considered contacts under either the O(n²) sweep or this grid. Consistent
 * with existing behaviour; changing it is out of scope for broadphase.
 *
 * **Thread safety.** Build on one thread; then reads from [forEachNeighbour]
 * are safe from multiple threads as long as no one mutates the grid concurrently.
 * ContactSystem builds the grid single-threaded before dispatching workers, so
 * this is already true there.
 *
 * **Determinism.** [insert] order is preserved per cell: entries within a cell
 * are returned in insertion order by [forEachNeighbour]. Neighbour cells are
 * visited in a fixed `(dy, dx)` raster order, so two runs with identical input
 * produce identical visit sequences.
 */
class SpatialGrid(private val cellSize: Long) {
    init {
        require(cellSize > 0) { "cellSize must be positive, was $cellSize" }
    }

    private val cells = HashMap<Long, IntList>()

    fun insert(index: Int, xRaw: Long, yRaw: Long) {
        val cx = cellIndex(xRaw)
        val cy = cellIndex(yRaw)
        val key = packKey(cx, cy)
        cells.getOrPut(key) { IntList() }.add(index)
    }

    /**
     * Calls [visit] once for every entity index previously inserted whose cell
     * is within the 3×3 window around `(xRaw, yRaw)`'s cell. The invoking index
     * itself is NOT filtered — callers typically skip self-pairs with an `idx > i`
     * or `idx != i` test.
     */
    inline fun forEachNeighbour(xRaw: Long, yRaw: Long, visit: (idx: Int) -> Unit) {
        val cx = cellIndex(xRaw)
        val cy = cellIndex(yRaw)
        for (dy in -1..1) {
            val ny = cy + dy
            for (dx in -1..1) {
                val nx = cx + dx
                val list = cellAt(nx, ny) ?: continue
                val size = list.size
                var k = 0
                while (k < size) {
                    visit(list[k])
                    k += 1
                }
            }
        }
    }

    @PublishedApi
    internal fun cellAt(cx: Int, cy: Int): IntList? = cells[packKey(cx, cy)]

    @PublishedApi
    internal fun cellIndex(raw: Long): Int {
        // floorDiv keeps cell indexing contiguous across negative coordinates.
        return raw.floorDiv(cellSize).toInt()
    }

    @PublishedApi
    internal fun packKey(cx: Int, cy: Int): Long =
        (cx.toLong() and 0xFFFFFFFFL) or (cy.toLong() shl 32)
}
