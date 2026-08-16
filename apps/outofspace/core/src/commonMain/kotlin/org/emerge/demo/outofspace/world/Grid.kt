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
    val tiles: Array<TileIndex> get() = Array(width*height) { TileIndex(it) }

    fun tile(x: Int, y: Int): TileIndex = TileIndex(y * width + x)
    fun xOf(tile: TileIndex): Int = tile.index % width
    fun yOf(tile: TileIndex): Int = tile.index / width

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Index of the neighbour of [tile] in [dir], or [TileIndex.NONE] if that would leave the grid. */
    fun neighbour(tile: TileIndex, dir: Direction): TileIndex {
        val x = xOf(tile) + dir.dx
        val y = yOf(tile) + dir.dy
        return if (inBounds(x, y)) tile(x, y) else TileIndex.NONE
    }

    fun isEdge(tile: TileIndex) : Boolean {
        // TODO: derive exposure from grid index directly for efficiency
        for (dir in Direction.ALL) {
            if (neighbour(tile, dir) == TileIndex.NONE) {
                return true
            }
        }
        return false
    }
}
