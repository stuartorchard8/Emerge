package org.emerge.demo.outofspace.world

/**
 * Conduit layers: one List<Segment?> per [Conduit] (rail/pipe/power/signal).
 * One slot per tile was wrong (fight for floor space; pipe over track silently failed).
 * Rail layer still special (packets, FlowField, gauges, bridges = rail concepts).
 * Pipe layer: aperture field (not FlowField). Power/signal layers reserved (empty = null list).
 */
class Conduits private constructor(
    private val layers: Array<List<Segment?>>,
    /**
     * The metal every laid segment is made of, and the heat it holds — see [TrackLayers].
     *
     * Held here rather than beside `conduits` in [VesselState] so that it cannot be forgotten: a
     * segment's existence and a segment's matter are one fact at one address, and [with] reconciles
     * them in the only operation that can change either.
     */
    val tracks: TrackLayers,
) {

    /** One layer, tile by tile. Always [tileCount] long, with nulls where nothing is laid. */
    operator fun get(conduit: Conduit): List<Segment?> = layers[conduit.ordinal]

    fun at(conduit: Conduit, tile: TileIndex): Segment? = layers[conduit.ordinal][tile.index]

    val tileCount: Int get() = layers[0].size

    /** This, with one layer replaced. The others share their lists — segments are immutable. */
    fun with(conduit: Conduit, layer: List<Segment?>): Conduits {
        require(layer.size == tileCount) { "layer is ${layer.size}, grid holds $tileCount" }
        val next = layers.copyOf()
        next[conduit.ordinal] = layer
        return Conduits(next, tracks.copyOf()).reconciled()
    }

    /**
     * This, with every laid tile holding a segment's worth of metal and every bare tile holding
     * none.
     *
     * Stated as "make the matter agree with the track" rather than as a diff of what changed,
     * because the two callers know different things: [with] knows the layer it replaced, the tick
     * reducer knows only the lists it has been mutating all tick. A rule that needs no history is
     * one both can apply, and applying it twice does nothing — a surviving tile is already in the
     * state it wants, so its heat is never touched and a hot run of pipe stays hot.
     */
    private fun reconciled(): Conduits {
        for (conduit in Conduit.entries) {
            val layer = layers[conduit.ordinal]
            for (i in layer.indices) {
                val tile = TileIndex(i)
                val laid = layer[i] != null
                if (laid && !tracks.occupies(conduit, tile)) tracks.lay(conduit, tile)
                else if (!laid && tracks.occupies(conduit, tile)) tracks.clear(conduit, tile)
            }
        }
        return this
    }

    /** What one tile of one network is holding — its heat, and the metal holding it. */
    fun energyAt(conduit: Conduit, tile: TileIndex): Long = tracks.energyAt(conduit, tile)

    fun massAt(conduit: Conduit, tile: TileIndex): Long = tracks.massAt(conduit, tile)

    fun heatCapacityAt(conduit: Conduit, tile: TileIndex): Long = tracks.heatCapacityAt(conduit, tile)

    /**
     * This, with one tile of one network holding [energy] instead of what it held.
     *
     * A copy rather than a write, because [tracks] is mutable and a [VesselState] copy shares it: an
     * in-place set would reach back into every snapshot anyone still holds of the world before the
     * change. The tick reducer works on its own copy and writes in place; everything else says this.
     */
    fun heated(conduit: Conduit, tile: TileIndex, energy: Long): Conduits {
        val next = tracks.copyOf()
        next.setEnergy(conduit, tile, energy)
        return Conduits(layers.copyOf(), next)
    }

    /** Total thermal energy in every length of every network — the ledger's term for the fittings. */
    val totalEnergy: Long get() = tracks.totalEnergy

    /**
     * Every segment actually laid, with the layer and tile it is on.
     *
     * The traversal order is by layer and then by tile, and it is fixed rather than incidental: the
     * thermal ledger sums over this, and a sum whose order can change is a sum that can disagree with
     * itself across a save.
     */
    inline fun all(action: (Conduit, TileIndex, Segment) -> Unit) {
        for (conduit in Conduit.entries) {
            val layer = this[conduit]
            for (tile in layer.indices) action(conduit, TileIndex(tile), layer[tile] ?: continue)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Conduits && layers.contentDeepEquals(other.layers) && tracks == other.tracks)

    override fun hashCode(): Int = layers.contentDeepHashCode()

    override fun toString(): String {
        val laid = Conduit.entries.map { c -> c to this[c].count { it != null } }.filter { it.second > 0 }
        return "Conduits(${laid.joinToString { "${it.first.label}=${it.second}" }})"
    }

    companion object {
        /** Nothing laid anywhere. */
        fun empty(tileCount: Int): Conduits {
            val blank = List<Segment?>(tileCount) { null }
            return Conduits(Array(Conduit.entries.size) { blank }, TrackLayers.empty(tileCount))
        }

        /**
         * A world with only track in it — which is every world that existed before this class did,
         * and every test that has an opinion about rails and none about anything else.
         */
        fun ofRails(rails: List<Segment?>): Conduits =
            empty(rails.size).with(Conduit.Rail, rails)

        /** Built layer by layer, for the save and for tests that lay more than one network. */
        fun of(tileCount: Int, vararg layers: Pair<Conduit, List<Segment?>>): Conduits {
            var out = empty(tileCount)
            for ((conduit, layer) in layers) out = out.with(conduit, layer)
            return out
        }

        /**
         * The networks as the tick reducer has them: one mutable list per conduit and the matter
         * layers it has been laying into and clearing out of all tick.
         *
         * [reconciled] runs anyway, so a segment the reducer created without telling the matter
         * layer still ends up made of something — but it ends up made of something *at ambient*,
         * which is why the reducer lays explicitly where it can and books the heat as arriving.
         */
        fun of(layers: Array<out List<Segment?>>, tracks: TrackLayers): Conduits =
            Conduits(Array(Conduit.entries.size) { layers[it] }, tracks).reconciled()
    }
}
