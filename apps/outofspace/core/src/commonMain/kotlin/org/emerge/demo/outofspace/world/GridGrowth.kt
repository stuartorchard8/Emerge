package org.emerge.demo.outofspace.world

/**
 * A grid that grew, and how far the world moved underneath whoever was holding a coordinate.
 *
 * `dx`/`dy` are where the **old origin lands in the new grid**, matching [remapped]: growing on the
 * left by four is `dx = +4`, and growing on the right or the bottom is `dx = dy = 0` because the
 * origin does not move. They are negative when [fitToFrame] shrinks — that is the whole reason
 * section 5 exists.
 */
data class GrowResult(val state: VesselState, val dx: Int, val dy: Int, val from: Grid) {
    /**
     * True when the grid changed shape at all, whether or not the origin moved with it.
     *
     * Distinct from `dx != 0 || dy != 0` on purpose: a far-side growth reports a zero offset and is
     * still a resize, and an index held across it is still wrong.
     */
    val grew: Boolean get() = state.grid != from
}

/**
 * The same world on a grid with at least [pad] clear tiles between everything placed and every
 * edge, grown as far as it takes and **never shrunk**.
 *
 * The counterpart of [fitToFrame]: that one states the grid exactly and is only safe at moments where
 * nothing holds a coordinate; this one is safe during play, because it only ever adds vacuum tiles,
 * which carry no mass, no energy and no momentum, so no ledger and no baseline moves.
 *
 * **Any of the four edges.** An earlier draft grew only on `+x`/`+y`, on the grounds that leaving
 * the origin alone left written down coordinates valid. It does not: `index = y * width + x`, so a
 * far-side growth changes `width` and every *stored index* means a different tile afterwards. Since
 * the holders have to be corrected either way, near-side growth is that same correction plus a
 * reported offset — see `HANDOFF_P3.md`, and [VesselState.frameShiftX] for how the offset travels.
 *
 * Called by the reducer at the end of every tick, with the world's own [VesselState.gridPad] — so a
 * world that never opted into a pad never grows, and keeps the frame it was authored in. The
 * contract is `GridGrowTest`.
 */
fun VesselState.growToFit(pad: Int = GRID_PAD): GrowResult {
    val box = placedBounds() ?: return GrowResult(this, 0, 0, grid)

    // The shortfall on each edge, never negative: this grows, and only by what is missing.
    val left = maxOf(0, pad - box[0])
    val top = maxOf(0, pad - box[1])
    val right = maxOf(0, pad - (grid.width - 1 - box[2]))
    val bottom = maxOf(0, pad - (grid.height - 1 - box[3]))
    if (left == 0 && top == 0 && right == 0 && bottom == 0) return GrowResult(this, 0, 0, grid)

    // Only the near edges move the origin — which is the whole of the difference between the two
    // sides, and the reason this is one path rather than two.
    val newGrid = Grid(grid.width + left + right, grid.height + top + bottom)
    return GrowResult(remapped(newGrid, left, top), left, top, grid)
}

/**
 * The world on a grid of exactly [pad] clear tiles on every side, reporting how far the frame moved.
 *
 * Shrinks as readily as it grows, so `dx`/`dy` can be negative — unlike [growToFit], which only ever
 * adds. Records [pad] as the world's [VesselState.gridPad]. Returns the world unchanged, and `grew`
 * false, when nothing is placed or the grid is already this shape.
 *
 * [fitGrid] is this without the offset. The contract is `GridFitTriggerTest`.
 */
fun VesselState.fitToFrame(pad: Int = GRID_PAD): GrowResult {
    val box = placedBounds() ?: return GrowResult(this, 0, 0, grid)

    // ── 1. The bounding box of everything that must be enclosed ──────────
    val minX = box[0]
    val minY = box[1]
    val maxX = box[2]
    val maxY = box[3]

    // ── 2. Expand by pad on every side ────────────────────────────────────
    val nx0 = minX - pad
    val ny0 = minY - pad
    val nx1 = maxX + pad
    val ny1 = maxY + pad
    val newW = nx1 - nx0 + 1
    val newH = ny1 - ny0 + 1

    // ── 3. Early exit: already exactly the fitted shape ──────────────────
    // Still records the pad: a world that is already the right shape is no less opted in.
    if (grid.width == newW && grid.height == newH && nx0 == 0 && ny0 == 0) {
        return GrowResult(copy(gridPad = pad), 0, 0, grid)
    }

    // ── 4. Build the new grid and delegate to remapped ───────────────────
    val newGrid = Grid(newW, newH)
    // dx/dy are "where the old origin lands in the new grid"
    val dx = 0 - nx0
    val dy = 0 - ny0
    return GrowResult(remapped(newGrid, dx, dy).copy(gridPad = pad), dx, dy, grid)
}

/**
 * The pad the world is kept at: four clear tiles between anything placed and any edge.
 *
 * [growToFit] and [fitGrid] both default to it.
 */
const val GRID_PAD: Int = 4

/**
 * The bounding box of everything the grid must enclose, as `(minX, minY, maxX, maxY)`, or null when
 * nothing is placed.
 *
 * Machines by **footprint** — a smelter is stored at its centre and reaches two tiles past it, so a
 * box drawn round the anchors clips the hull off its own ship. **Rocks are excluded** on purpose:
 * §8 of the plan establishes that they live outside the world quite happily, and an earlier attempt
 * that enclosed them fitted the starter vessel to 92×50 instead of 41×26, losing the entire
 * performance case while passing its own tests.
 *
 * Shared by [fitGrid] and [growToFit] so that "what the box encloses" has exactly one definition.
 */
internal fun VesselState.placedBounds(): IntArray? {
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE

    fun cover(x: Int, y: Int, reach: Int) {
        if (x - reach < minX) minX = x - reach
        if (y - reach < minY) minY = y - reach
        if (x + reach > maxX) maxX = x + reach
        if (y + reach > maxY) maxY = y + reach
    }

    for (tile in grid.tiles) {
        val m = machines[tile.index] ?: continue
        cover(grid.xOf(tile), grid.yOf(tile), m.kind.reach)
    }
    for (tile in grid.tiles) {
        val m = deck[tile] ?: continue
        // Tile by tile rather than centre-and-reach: a bridge's footprint is a line, so a reach
        // taken as a square would claim two tiles either side of it that nothing stands on and
        // refuse a shrink that is actually legal.
        for (part in m.tiles(grid)) cover(grid.xOf(part), grid.yOf(part), 0)
    }
    for (c in Conduit.entries) {
        val layer = conduits[c]
        for (tile in grid.tiles) if (layer[tile.index] != null) cover(grid.xOf(tile), grid.yOf(tile), 0)
    }

    return if (minX > maxX) null else intArrayOf(minX, minY, maxX, maxY)
}
