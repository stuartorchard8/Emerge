package org.emerge.demo.outofspace.world

/**
 * Conduit layers: one List<Segment?> per [Conduit] (rail/pipe/power/signal).
 * One slot per tile was wrong (fight for floor space; pipe over track silently failed).
 * Rail layer still special (packets, FlowField, gauges, bridges = rail concepts).
 * Pipe layer: aperture field (not FlowField). Power/signal layers reserved (empty = null list).
 */
class Conduits private constructor(private val layers: Array<List<Segment?>>) {

    /** One layer, tile by tile. Always [tileCount] long, with nulls where nothing is laid. */
    operator fun get(conduit: Conduit): List<Segment?> = layers[conduit.ordinal]

    fun at(conduit: Conduit, tile: TileIndex): Segment? = layers[conduit.ordinal][tile.index]

    val tileCount: Int get() = layers[0].size

    /** This, with one layer replaced. The others share their lists — segments are immutable. */
    fun with(conduit: Conduit, layer: List<Segment?>): Conduits {
        require(layer.size == tileCount) { "layer is ${layer.size}, grid holds $tileCount" }
        val next = layers.copyOf()
        next[conduit.ordinal] = layer
        return Conduits(next)
    }

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
        this === other || (other is Conduits && layers.contentDeepEquals(other.layers))

    override fun hashCode(): Int = layers.contentDeepHashCode()

    override fun toString(): String {
        val laid = Conduit.entries.map { c -> c to this[c].count { it != null } }.filter { it.second > 0 }
        return "Conduits(${laid.joinToString { "${it.first.label}=${it.second}" }})"
    }

    companion object {
        /** Nothing laid anywhere. */
        fun empty(tileCount: Int): Conduits {
            val blank = List<Segment?>(tileCount) { null }
            return Conduits(Array(Conduit.entries.size) { blank })
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
    }
}
