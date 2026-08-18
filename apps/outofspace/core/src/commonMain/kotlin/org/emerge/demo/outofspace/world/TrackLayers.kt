package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Species

/**
 * What the conduit networks are **made of**: one [StuffLayer] per [Conduit], holding the metal of
 * every length of track, pipe and wire aboard, and the heat that metal carries.
 *
 * One layer per conduit type rather than one shared layer, because the layers genuinely share tiles
 * — a rail and a pipe crossing the same tile are both real and neither is in the other's way, so a
 * single layer would have to merge two bills of materials at one address and could not say which
 * network a gram belonged to. Merging them would buy memory and a tighter floor-space constraint;
 * that is a design change, not this migration.
 *
 * Owned by [Conduits] rather than sat beside it in [VesselState], which is the difference from
 * [BufferLayer]: a segment's *existence* and a segment's *matter* are the same fact at the same
 * address, so `Conduits` reconciles them itself and there is no way to hand the world one without
 * the other. That is the same footgun [VesselState.buffers] answers by being required, closed a
 * different way.
 */
class TrackLayers private constructor(private val layers: Array<StuffLayer>) {

    operator fun get(conduit: Conduit): StuffLayer = layers[conduit.ordinal]

    val tileCount: Int get() = layers[0].tileCount

    /**
     * Lay one tile of [conduit]: the metal goes in, and the heat it holds is derived from that metal
     * at room temperature.
     *
     * Returns what it now holds, because the caller has a ledger to satisfy — a freshly laid
     * segment's heat *arrived* from off-world rather than being conjured, and [Work.built] books it.
     * Returning it rather than having the caller ask [Conduit.ambientPerTile] keeps the number the
     * ledger sees and the number the world holds the same one.
     */
    fun lay(conduit: Conduit, tile: TileIndex): Long {
        val stuff = layers[conduit.ordinal]
        require(!stuff.occupies(tile)) { "$conduit already holds stuff at $tile" }
        val bill = conduitBillOfMaterials(conduit)
        for (s in Species.ALL) stuff[tile, s] = bill[s]
        val energy = stuff.heatCapacityAt(tile) * Temperature.AMBIENT_KELVIN
        stuff.setEnergy(tile, energy)
        return energy
    }

    /** Take one tile of [conduit] away, answering the energy that went with it — see [lay]. */
    fun clear(conduit: Conduit, tile: TileIndex): Long {
        val stuff = layers[conduit.ordinal]
        if (!stuff.occupies(tile)) return 0L
        val energy = stuff.energyAt(tile)
        stuff.release(tile)
        return energy
    }

    /**
     * Whether the tile holds every gram of [conduit]'s bill of materials — the opposite of a ghost.
     *
     * ⚠️ **Per species, and never against a total.** A ghost admits a few percent of whatever came
     * with the material it was fed, so a tile can carry more mass than its bill while still being
     * short of the one thing it is made of. A total-mass test would call that finished and hand the
     * player a free length of track.
     *
     * The single place the question is answered, so the routing, the ledger and the renderer cannot
     * drift into three different opinions about which tiles are ghosts.
     */
    fun holdsFullBill(conduit: Conduit, tile: TileIndex): Boolean {
        val stuff = layers[conduit.ordinal]
        return holdsFullBill(conduitBillOfMaterials(conduit)) { stuff[tile, it] }
    }

    /**
     * How built this tile is, in parts per thousand — 0 for a bare ghost, 1000 for finished track.
     *
     * The **minimum** of the per-species ratios, not the total mass over the total bill, so that it
     * reaches 1000 exactly when [holdsFullBill] turns true. A total would run ahead of it: junk
     * counts toward the mass and toward nothing else, so a tile could draw as finished while still
     * short of the one thing it is made of, and a player would be told a lie about why their track
     * is not working.
     *
     * For readouts and the renderer. The sim asks [holdsFullBill], which is the same question
     * without the arithmetic.
     */
    fun builtPermille(conduit: Conduit, tile: TileIndex): Int {
        val stuff = layers[conduit.ordinal]
        return builtPermille(conduitBillOfMaterials(conduit)) { stuff[tile, it] }
    }

    fun occupies(conduit: Conduit, tile: TileIndex): Boolean = layers[conduit.ordinal].occupies(tile)

    fun massAt(conduit: Conduit, tile: TileIndex): Long = layers[conduit.ordinal].massAt(tile)

    fun heatCapacityAt(conduit: Conduit, tile: TileIndex): Long =
        layers[conduit.ordinal].heatCapacityAt(tile)

    fun energyAt(conduit: Conduit, tile: TileIndex): Long = layers[conduit.ordinal].energyAt(tile)

    fun setEnergy(conduit: Conduit, tile: TileIndex, energy: Long) =
        layers[conduit.ordinal].setEnergy(tile, energy)

    val totalEnergy: Long get() {
        var sum = 0L
        for (l in layers) sum += l.totalEnergy
        return sum
    }

    val totalMass: Long get() {
        var sum = 0L
        for (l in layers) sum += l.totalMass
        return sum
    }

    fun copyOf(): TrackLayers = TrackLayers(Array(layers.size) { layers[it].copyOf() })

    override fun equals(other: Any?): Boolean =
        this === other || (other is TrackLayers && layers.contentEquals(other.layers))

    override fun hashCode(): Int = layers.contentHashCode()

    companion object {
        fun empty(tileCount: Int): TrackLayers =
            TrackLayers(Array(Conduit.entries.size) { StuffLayer.empty(tileCount) })
    }
}
