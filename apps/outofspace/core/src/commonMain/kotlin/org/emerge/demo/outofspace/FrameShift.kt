package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.VesselState

/**
 * Keeps a coordinate a holder wrote down meaning the tile it meant, across a grid that grows.
 *
 * P3 lets the grid grow under a running world, and every coordinate stored outside [VesselState]
 * goes wrong when it does — in two separate ways, which is why this exists rather than one `+= dx`
 * at each site:
 *
 * - **A tile position** (`camX`, a pointer position) moves by the offset the origin moved by, which
 *   is zero for a far-side growth.
 * - **A tile index** (`selected`, `injectTile`, the conduit drag anchor) is `y * width + x`, so it
 *   goes wrong whenever the *width* changes — including on a far-side growth, where the offset is
 *   zero and nothing appears to have happened. Arithmetic on the raw int cannot fix it; it has to
 *   be taken apart through the old grid and put back together through the new one.
 *
 * Usage: one of these per holder, [advance] once per step or frame, then apply the [Move].
 */
class FrameShift(state: VesselState) {

    private var grid: Grid = state.grid
    private var shiftX: Int = state.frameShiftX
    private var shiftY: Int = state.frameShiftY

    /**
     * What has happened to the frame since the last call, and remembers this one.
     *
     * Safe to call every frame on a world that never grows: the [Move] is then a no-op that reports
     * `(0, 0)` and reindexes to itself.
     */
    fun advance(state: VesselState): Move {
        val move = Move(
            dx = state.frameShiftX - shiftX,
            dy = state.frameShiftY - shiftY,
            from = grid,
            to = state.grid,
        )
        grid = state.grid
        shiftX = state.frameShiftX
        shiftY = state.frameShiftY
        return move
    }

    /** Forget everything and start again from [state] — for a world replaced wholesale. */
    fun reset(state: VesselState) {
        grid = state.grid
        shiftX = state.frameShiftX
        shiftY = state.frameShiftY
    }
}

/** How far the frame moved between two observations of it, and how to follow it. */
class Move(val dx: Int, val dy: Int, val from: Grid, val to: Grid) {

    /** True when anything at all changed, so a holder can skip the work in the usual case. */
    val moved: Boolean get() = dx != 0 || dy != 0 || from != to

    /**
     * A tile index from the old frame, as an index in the new one — or `-1` if that tile is not on
     * the new grid, which is also what an already-`-1` "nothing" stays.
     */
    fun reindex(tile: Int): Int {
        if (tile < 0 || tile >= from.size) return -1
        if (!moved) return tile
        val x = from.xOf(tile) + dx
        val y = from.yOf(tile) + dy
        return if (to.inBounds(x, y)) to.index(x, y) else -1
    }
}
