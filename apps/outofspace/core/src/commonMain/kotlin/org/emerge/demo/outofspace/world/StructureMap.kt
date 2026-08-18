package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Machine

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
    fun isImpermeable(tile: TileIndex): Boolean =
        kinds[tile.index].toInt() == Structure.Hull.ordinal || kinds[tile.index].toInt() == Structure.Machine.ordinal

    fun isPermeable(tile: TileIndex): Boolean = !isImpermeable(tile)

    fun isContained(tile: TileIndex): Boolean = kinds[tile.index].toInt() != Structure.Vacuum.ordinal

    val interiorCount: Int get() = kinds.count { it.toInt() == Structure.Interior.ordinal }

    override fun equals(other: Any?): Boolean =
        this === other || (other is StructureMap && kinds.contentEquals(other.kinds))

    override fun hashCode(): Int = kinds.contentHashCode()

    companion object {
        /**
         * Flood-fills space in from every edge tile, stopping at anything solid. Anything not
         * reached and not solid is interior.
         *
         * Almost every deck machine blocks, over its whole footprint — a smelter is a solid object,
         * and a tile of solid object is not somewhere air can be. Conduits are not in this list at
         * all: rails and bridges live on their own layers and share a tile with the deck beneath
         * them, so a belt running through a room does not divide it.
         *
         * The exception is a [org.emerge.demo.outofspace.world.machine.MachineKind.isPermeable] one, which is a plate and not a block: it is
         * skipped entirely, so the tile it stands on is whatever the flood fill would have made it.
         * Nothing downstream needs a case for it — air, heat and rock contact all read this map, and
         * all three then treat the tile as the empty floor it is.
         *
         * [openness] names the tiles that are open *this tick* despite being solid things — today,
         * [org.emerge.demo.outofspace.world.machine.Airlock]s that are being signalled. They are skipped exactly as a permeable plate is, so
         * the fill pours through an open door and the room beyond it correctly becomes outside. Omit
         * it and every door is shut, which is the right answer for a world being loaded or built.
         * See [org.emerge.demo.outofspace.world.airlockOpenness].
         */
        fun derive(grid: Grid, machines: List<Machine?>, deck: DeckArray, openness: IntArray? = null): StructureMap {
            val kinds = ByteArray(grid.size) { Structure.Interior.ordinal.toByte() }
            for (tile in grid.tiles) {
                val m = machines[tile.index] ?: continue
                if (m.kind.isPermeable) continue
                if ((openness?.get(tile.index) ?: 0) > 0) continue
                val kind = Structure.Machine
                for (t in coveredTiles(grid, tile, m.kind.size)) kinds[t.index] = kind.ordinal.toByte()
            }
            for (tile in grid.tiles) {
                val m = deck[tile] ?: continue
                if (m.kind.isPermeable) continue
                if ((openness?.get(tile.index) ?: 0) > 0) continue
                val kind = if (m is Hull || m is Airlock) Structure.Hull else Structure.Machine
                for (t in m.tiles(grid)) kinds[t.index] = kind.ordinal.toByte()
            }

            // Breadth-first from the border. An explicit stack rather than recursion: a 48x28 grid is
            // 1344 deep in the worst case and this also runs on JS.
            val stack = ArrayDeque<TileIndex>()
            fun seed(tile: TileIndex) {
                if (kinds[tile.index].toInt() == Structure.Interior.ordinal) {
                    kinds[tile.index] = Structure.Vacuum.ordinal.toByte()
                    stack.addLast(tile)
                }
            }
            for (x in 0 until grid.width) {
                seed(grid.tile(x, 0))
                seed(grid.tile(x, grid.height - 1))
            }
            for (y in 0 until grid.height) {
                seed(grid.tile(0, y))
                seed(grid.tile(grid.width - 1, y))
            }
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                for (dir in Direction.ALL) {
                    val next = grid.neighbour(at, dir)
                    if (next != TileIndex.NONE) seed(next)
                }
            }
            return StructureMap(kinds)
        }
    }
}
