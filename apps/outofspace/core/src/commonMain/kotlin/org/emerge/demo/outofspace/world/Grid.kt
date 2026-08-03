package org.emerge.demo.outofspace.world

/**
 * The vessel's tile lattice: fixed size, square cells, row-major.
 *
 * Fixed rather than growable because the atmosphere solver in Phase 4 is far simpler over fixed
 * bounds, and because "a generous bound with the hull inside it" gets the expansion fantasy without
 * the machinery. The hull will be drawn *within* the grid rather than being its edge.
 */
data class Grid(val width: Int, val height: Int) {
    val size: Int get() = width * height

    fun index(x: Int, y: Int): Int = y * width + x
    fun xOf(index: Int): Int = index % width
    fun yOf(index: Int): Int = index / width

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Index of the neighbour of [index] in [dir], or -1 if that would leave the grid. */
    fun neighbour(index: Int, dir: Direction): Int {
        val x = xOf(index) + dir.dx
        val y = yOf(index) + dir.dy
        return if (inBounds(x, y)) index(x, y) else -1
    }

    fun isEdge(tile: Int) : Boolean {
        // TODO: derive exposure from grid index directly for efficiency
        for (dir in Direction.ALL) {
            if (neighbour(tile, dir) < 0) {
                return true
            }
        }
        return false
    }
}
