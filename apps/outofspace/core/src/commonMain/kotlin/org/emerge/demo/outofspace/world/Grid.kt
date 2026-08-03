package org.emerge.demo.outofspace.world

/**
 * A direction on the tile grid.
 *
 * **+y is down.** The world is side-on, so screen-down and gravity-down are the same direction, and
 * keeping the grid in that orientation means no axis flips between the sim and the renderer. The one
 * place it surprises people is [Up] having `dy = -1`.
 */
enum class Direction(val dx: Int, val dy: Int) {
    Right(1, 0),
    Down(0, 1),
    Left(-1, 0),
    Up(0, -1),
    ;

    val opposite: Direction
        get() = when (this) {
            Right -> Left
            Down -> Up
            Left -> Right
            Up -> Down
        }

    /** Next direction clockwise on screen — what a rotate key does. */
    val clockwise: Direction
        get() = when (this) {
            Right -> Down
            Down -> Left
            Left -> Up
            Up -> Right
        }

    companion object {
        val ALL: List<Direction> = entries.toList()
    }
}

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
