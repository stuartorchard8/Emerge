package org.emerge.demo.outofspace.world

/**
 * Every tile's [Structure], derived rather than authored.
 *
 * The player never paints "floor". They build **hull**, and the inside is whatever the hull encloses:
 * a flood fill inward from the grid's edge marks everything space can reach, and what it cannot reach
 * is interior. That gives the right answer to "what is outside the hull" for free, and it makes a
 * breach mean exactly what it should — knock out one hull tile and the fill pours in, so the room
 * *becomes* outside, with no separate concept of a leak.
 *
 * Derived every tick. A flood fill over a grid this size is a rounding error next to the rest of the
 * tick, and the alternative — caching it and invalidating on edits — is a class of bug for no gain.
 */
class StructureMap(private val kinds: ByteArray) {

    operator fun get(index: Int): Structure = Structure.entries[kinds[index].toInt()]

    /** Solid: air can neither sit in this tile nor cross it. Walls and machines both. */
    fun isImpermeable(index: Int): Boolean =
        kinds[index].toInt() == Structure.Hull.ordinal || kinds[index].toInt() == Structure.Machine.ordinal

    fun isPermeable(index: Int): Boolean = !isImpermeable(index)

    fun isContained(index: Int): Boolean = kinds[index].toInt() != Structure.Vacuum.ordinal

    val interiorCount: Int get() = kinds.count { it.toInt() == Structure.Interior.ordinal }

    override fun equals(other: Any?): Boolean =
        this === other || (other is StructureMap && kinds.contentEquals(other.kinds))

    override fun hashCode(): Int = kinds.contentHashCode()

    companion object {
        /**
         * Flood-fills space in from every edge tile, stopping at anything solid. Anything not
         * reached and not solid is interior.
         *
         * Every deck machine blocks, over its whole footprint — a smelter is a solid object, and a
         * tile of solid object is not somewhere air can be. Conduits are not in this list at all:
         * rails and bridges live on their own layers and share a tile with the deck beneath them, so
         * a belt running through a room does not divide it.
         */
        fun derive(grid: Grid, machines: List<Machine?>): StructureMap {
            val kinds = ByteArray(grid.size) { Structure.Interior.ordinal.toByte() }
            for (i in machines.indices) {
                val m = machines[i] ?: continue
                val kind = if (m is Hull) Structure.Hull else Structure.Machine
                for (t in coveredTiles(grid, i, m.kind.size)) kinds[t] = kind.ordinal.toByte()
            }

            // Breadth-first from the border. An explicit stack rather than recursion: a 48x28 grid is
            // 1344 deep in the worst case and this also runs on JS.
            val stack = ArrayDeque<Int>()
            fun seed(index: Int) {
                if (kinds[index].toInt() == Structure.Interior.ordinal) {
                    kinds[index] = Structure.Vacuum.ordinal.toByte()
                    stack.addLast(index)
                }
            }
            for (x in 0 until grid.width) {
                seed(grid.index(x, 0))
                seed(grid.index(x, grid.height - 1))
            }
            for (y in 0 until grid.height) {
                seed(grid.index(0, y))
                seed(grid.index(grid.width - 1, y))
            }
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                for (dir in Direction.ALL) {
                    val next = grid.neighbour(at, dir)
                    if (next >= 0) seed(next)
                }
            }
            return StructureMap(kinds)
        }
    }
}
