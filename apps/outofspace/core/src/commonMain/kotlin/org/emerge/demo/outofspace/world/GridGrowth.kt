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
 * **Not yet implemented.** The contract is `GridGrowTest`, which is written and failing.
 */
fun VesselState.growToFit(pad: Int = 4): GrowResult {
    TODO("P3: grow each edge that has less than `pad` clear tiles, via remapped()")
}
