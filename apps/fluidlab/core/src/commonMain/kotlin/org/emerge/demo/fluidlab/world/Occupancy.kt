package org.emerge.demo.fluidlab.world

/**
 * Which tiles belong to which machine.
 *
 * `machines` holds each machine once, at its centre — storing a copy on every covered tile would
 * mean nine or twenty-five things that have to agree about one furnace's contents, and they would
 * eventually not. This is the index that makes the single copy usable: tile → the index its machine
 * is stored at, or `-1` for open deck.
 *
 * Derived every tick alongside [StructureMap], and for the same reason: a cache with an invalidation
 * rule is a bug waiting for an edit case nobody thought of.
 */
class Occupancy(private val originOf: IntArray) {

    /** The index the machine covering this tile is stored at, or -1 if the tile is free. */
    operator fun get(tile: Int): Int = if (tile in originOf.indices) originOf[tile] else -1

    fun isFree(tile: Int): Boolean = get(tile) < 0

    /** True when this tile is where its machine actually lives, rather than a tile it merely covers. */
    fun isOrigin(tile: Int): Boolean = get(tile) == tile

    override fun equals(other: Any?): Boolean =
        this === other || (other is Occupancy && originOf.contentEquals(other.originOf))

    override fun hashCode(): Int = originOf.contentHashCode()

    companion object {
        fun derive(grid: Grid, machines: List<Machine?>): Occupancy {
            val originOf = IntArray(grid.size) { -1 }
            for (i in machines.indices) {
                val m = machines[i] ?: continue
                for (tile in coveredTiles(grid, i, m.kind.size)) originOf[tile] = i
            }
            return Occupancy(originOf)
        }
    }
}

/**
 * Every tile a machine of [size] centred on [centre] covers, clipped to the grid.
 *
 * Row-major order, which is arbitrary but fixed — the only property anything downstream relies on.
 */
fun coveredTiles(grid: Grid, centre: Int, size: Int): List<Int> {
    if (size <= 1) return listOf(centre)
    val reach = size / 2
    val cx = grid.xOf(centre)
    val cy = grid.yOf(centre)
    val out = ArrayList<Int>(size * size)
    for (dy in -reach..reach) {
        for (dx in -reach..reach) {
            val x = cx + dx
            val y = cy + dy
            if (grid.inBounds(x, y)) out.add(grid.index(x, y))
        }
    }
    return out
}

/** True when a machine of [size] centred here would fit entirely on the grid. */
fun footprintFits(grid: Grid, centre: Int, size: Int): Boolean {
    val reach = size / 2
    val cx = grid.xOf(centre)
    val cy = grid.yOf(centre)
    return grid.inBounds(cx - reach, cy - reach) && grid.inBounds(cx + reach, cy + reach)
}
