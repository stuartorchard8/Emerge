package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Maps Cyto's logical units (cell radii, Float, as in the original Box2D sim) onto the
 * engine's fixed-point torus.
 *
 * Scale decision (user): a base cell — logical radius 1.0 — has engine radius
 * `Frac(1, 1024)`. The torus is 2.0 wide (normalised −1..1), so it spans
 * [CELLS_PER_AXIS] = 1024 base-cell *diameters* per axis and wraps. Colonies stay a
 * small fraction of the torus until they grow huge, at which point they wrap — a wanted
 * feature the original open-plane Box2D world couldn't offer.
 */
object CytoUnits {
    /** Base-cell diameters across the torus per axis. */
    const val CELLS_PER_AXIS = 1024

    private const val SCALE = 1f / CELLS_PER_AXIS // logical-radius-unit -> normalised

    /** Logical length (in cell-radius units) -> engine [Frac]. radius 1.0 -> Frac(1,1024). */
    fun len(logical: Float): Frac = Frac((logical * SCALE * Int.MAX_VALUE).toLong())

    /** Logical coordinate -> torus [Coord] (wraps via Int two's-complement for huge values). */
    fun coord(logical: Float): Coord = Coord((logical.toDouble() * SCALE * Int.MAX_VALUE).toLong().toInt())

    fun coord2(x: Float, y: Float): Coord2 = Coord2(coord(x), coord(y))

    /** Engine [Frac] length -> logical (inverse of [len]). */
    fun toLogical(frac: Frac): Float = frac.toFloat() * CELLS_PER_AXIS

    /** Engine [Coord] position -> logical. */
    fun toLogical(coord: Coord): Float = coord.toFloat() * CELLS_PER_AXIS
}
