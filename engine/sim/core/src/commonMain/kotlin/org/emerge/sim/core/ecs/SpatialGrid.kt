package org.emerge.sim.core.ecs

/**
 * Uniform 2D spatial hash over the full signed-Int coordinate torus. Used to
 * replace O(n²) all-pairs sweeps with O(n·k) neighbour queries, where `k` is
 * the average occupancy of a 3×3 cell window around any body.
 *
 * **Torus wrap.** The raw coordinate space is `Int` (2³²-periodic, matching
 * `Coord.minus` which relies on two's-complement Int overflow for shortest-
 * torus deltas). The grid tiles that full range with `2^numCellsLog2` cells
 * per axis, each of width `2^cellSizeLog2`, with `cellSizeLog2 + numCellsLog2
 * = 32` so cell indices wrap via simple bitmask AND. A pair of bodies whose
 * centres are within `cellSize` of each other along the torus-shortest path
 * always lands within each other's 3×3 cell window — including pairs that
 * straddle the seam, which are looked up via the same `(cx + dx) and mask`
 * wrap that `Int + Int` overflow would give for signed coordinates.
 *
 * **Pick a cell size.** `cellSize` must be `>= 2 * maxBodyRadius` so every
 * overlapping pair falls in a 3×3 window. Undersized cells silently miss
 * pairs; oversized cells just cost extra neighbour lookups. Use
 * [forMinCellSize] to round up to the nearest power of 2 automatically.
 *
 * **Thread safety.** Build on one thread; then reads via [forEachNeighbour]
 * are safe from multiple threads so long as no one mutates the grid
 * concurrently. ContactSystem builds single-threaded before dispatching
 * workers, satisfying this.
 *
 * **Determinism.** Entries within a cell are stored and returned in insertion
 * order. Neighbour cells are visited in a fixed `(dy, dx)` raster order, so
 * two runs with identical input produce identical visit sequences.
 */
class SpatialGrid @PublishedApi internal constructor(
    @PublishedApi internal val cellSizeLog2: Int,
    @PublishedApi internal val numCellsLog2: Int,
) {
    init {
        require(numCellsLog2 in MIN_NUM_CELLS_LOG2..MAX_NUM_CELLS_LOG2) {
            "numCellsLog2=$numCellsLog2 outside [$MIN_NUM_CELLS_LOG2, $MAX_NUM_CELLS_LOG2]"
        }
        require(cellSizeLog2 in 0..31) {
            "cellSizeLog2=$cellSizeLog2 outside [0, 31]"
        }
    }

    @PublishedApi internal val mask: Int = (1 shl numCellsLog2) - 1

    /**
     * Flat backing store, indexed by `(cy shl numCellsLog2) or cx` where
     * `cx`, `cy` are already `and`-masked into `[0, 2^numCellsLog2)`.
     * A flat array beats a `HashMap<Long, _>` here because the cell-index
     * space is bounded and small (capped at 1M total cells), so every
     * insert/query is a pair of bit ops plus one indexed array access.
     */
    @PublishedApi internal val cells: Array<IntList?> =
        arrayOfNulls(1 shl (numCellsLog2 * 2))

    fun insert(index: Int, xRaw: Int, yRaw: Int) {
        val cx = (xRaw shr cellSizeLog2) and mask
        val cy = (yRaw shr cellSizeLog2) and mask
        val key = (cy shl numCellsLog2) or cx
        val list = cells[key]
        if (list == null) {
            cells[key] = IntList().also { it.add(index) }
        } else {
            list.add(index)
        }
    }

    /**
     * Calls [visit] once for every entity index previously inserted whose cell
     * is within the 3×3 window around `(xRaw, yRaw)`'s cell, with neighbour
     * cell indices wrapped into the torus. The invoking index itself is NOT
     * filtered — callers typically skip self-pairs with an `idx > i` or
     * `idx != i` test.
     */
    inline fun forEachNeighbour(xRaw: Int, yRaw: Int, visit: (idx: Int) -> Unit) {
        val cx = (xRaw shr cellSizeLog2) and mask
        val cy = (yRaw shr cellSizeLog2) and mask
        for (dy in -1..1) {
            val ny = (cy + dy) and mask
            for (dx in -1..1) {
                val nx = (cx + dx) and mask
                val list = cells[(ny shl numCellsLog2) or nx] ?: continue
                val size = list.size
                var k = 0
                while (k < size) {
                    visit(list[k])
                    k += 1
                }
            }
        }
    }

    companion object {
        /**
         * Minimum cells-per-axis of 4 (`2^2`). Any fewer and the 3×3 neighbour
         * window wraps onto the same cell twice (e.g. cell 0 with 2 cells per
         * axis has neighbours `{-1 and 1, 0, 1 and 1} = {1, 0, 1}`), which
         * would double-visit pairs. 4 per axis gives `{3, 0, 1}` — distinct.
         */
        private const val MIN_NUM_CELLS_LOG2 = 2

        /**
         * Cap cells-per-axis at `2^10 = 1024` (1M cells total, ~8MB on JVM
         * for the reference array). Sims with `maxRadius` below `2^22` raw
         * units end up with coarser cells than strictly optimal, but stay
         * correct. No realistic physics scenario in this project hits the cap
         * — drockets' planets force `maxRadius ≈ 2^28`, giving 8 per axis.
         */
        private const val MAX_NUM_CELLS_LOG2 = 10

        /**
         * Build a grid whose cell size is the smallest power-of-2 at least
         * [minCellSize], and whose axis-cell count tiles the full 2³²
         * coordinate range exactly. Cells-per-axis is clamped to
         * `[2^MIN_NUM_CELLS_LOG2, 2^MAX_NUM_CELLS_LOG2]`; when
         * [minCellSize] is so large that fewer than 4 cells would fit, returns
         * `null` and the caller must fall back to an O(n²) sweep.
         */
        fun forMinCellSize(minCellSize: Long): SpatialGrid? {
            require(minCellSize > 0) { "minCellSize must be positive, was $minCellSize" }
            var cellSizeLog2 = 0
            while (cellSizeLog2 < 32 && (1L shl cellSizeLog2) < minCellSize) {
                cellSizeLog2 += 1
            }
            var numCellsLog2 = 32 - cellSizeLog2
            if (numCellsLog2 < MIN_NUM_CELLS_LOG2) return null
            if (numCellsLog2 > MAX_NUM_CELLS_LOG2) {
                numCellsLog2 = MAX_NUM_CELLS_LOG2
                cellSizeLog2 = 32 - numCellsLog2
            }
            return SpatialGrid(cellSizeLog2, numCellsLog2)
        }
    }
}
