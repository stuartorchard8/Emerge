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
