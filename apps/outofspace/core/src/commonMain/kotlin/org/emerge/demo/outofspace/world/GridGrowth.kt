package org.emerge.demo.outofspace.world

/**
 * A grid that grew, and how far the world moved underneath whoever was holding a coordinate.
 *
 * `dx`/`dy` are where the **old origin lands in the new grid**, matching [remapped]: growing on the
 * left by four is `dx = +4`, and growing on the right or the bottom is `dx = dy = 0` because the
 * origin does not move. They are never negative — [growToFit] only grows.
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
 * The counterpart of [fitGrid]: that one states the grid exactly and is only safe at moments where
 * nothing holds a coordinate; this one is safe during play, because it only ever adds vacuum tiles,
 * which carry no grams, no joules and no momentum, so no ledger and no baseline moves.
 *
 * **Any of the four edges.** An earlier draft grew only on `+x`/`+y`, on the grounds that leaving
 * the origin alone left written-down coordinates valid. It does not: `index = y * width + x`, so a
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

/** The pad the world is kept at: four clear tiles between anything placed and any edge. */
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

    for (i in machines.indices) {
        val m = machines[i] ?: continue
        cover(grid.xOf(i), grid.yOf(i), m.kind.size / 2)
    }
    for (i in bridges.indices) {
        if (bridges[i] == null) continue
        cover(grid.xOf(i), grid.yOf(i), 0)
    }
    for (c in Conduit.entries) {
        val layer = conduits[c]
        for (i in layer.indices) if (layer[i] != null) cover(grid.xOf(i), grid.yOf(i), 0)
    }
    for (tile in debris.tiles()) cover(grid.xOf(tile), grid.yOf(tile), 0)

    return if (minX > maxX) null else intArrayOf(minX, minY, maxX, maxY)
}
