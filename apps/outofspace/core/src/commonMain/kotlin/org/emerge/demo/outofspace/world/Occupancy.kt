package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckArray

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
class Occupancy(private val originOf: TileArray) {

    /** The index the machine covering this tile is stored at, or -1 if the tile is free. */
    operator fun get(tile: TileIndex): TileIndex = if (tile.index in originOf.data.indices) originOf[tile] else TileIndex.NONE

    fun isFree(tile: TileIndex): Boolean = get(tile) == TileIndex.NONE

    /** True when this tile is where its machine actually lives, rather than a tile it merely covers. */
    fun isOrigin(tile: TileIndex): Boolean = get(tile) == tile

    override fun equals(other: Any?): Boolean =
        this === other || (other is Occupancy && originOf.contentEquals(other.originOf))

    override fun hashCode(): Int = originOf.contentHashCode()

    companion object {
        fun derive(grid: Grid, deck: DeckArray): Occupancy {
            val originOf = TileArray(grid.size) { TileIndex.NONE }
            for (i in 0 until deck.size) {
                val tile = TileIndex(i)
                val m = deck[tile] ?: continue
                // ⚠️ To the machine's **anchor**, not to each tile itself. Identical while every
                // deck machine was one tile across, and wrong the moment one is not: the origin
                // index is how any tile of a footprint finds the machine standing on it, and a
                // tile that points at itself finds nothing.
                //
                // ⚠️ And by walking [DeckMachine.tiles], never by squaring a half-width off the
                // anchor: a bridge's footprint is a line and a thruster's is not even centred on
                // the tile it is stored at. This file used to export that square as a helper; it
                // is deleted rather than deprecated, because a wrong footprint does not read as a
                // wrong footprint — it reads as a rotation turning onto an occupied tile.
                for (part in m.tiles(grid)) originOf[part] = m.center
            }
            return Occupancy(originOf)
        }
    }
}
