package org.emerge.demo.outofspace.world

/**
 * Every conduit layer, one grid of segments each — the thing [Conduit] has always described and the
 * storage has never actually been.
 *
 * ### What was wrong with one list
 *
 * There was a single `rails: List<Segment?>`, one slot per tile, holding whatever conduit had been
 * laid there. [Conduit]'s own documentation says four networks share one tile grid and that routing
 * is a puzzle about *each* network rather than one fight for floor space; with one slot per tile it
 * was a fight for floor space. `layConduit` even carried the comment "a pipe drawn across a rail is a
 * crossing, not a junction" — and then returned early on the conduit mismatch, so the crossing was
 * not a junction because it was nothing at all. Dragging a pipe over track silently laid no pipe.
 *
 * This is the same mistake the body model corrected for heat, and it is worth naming twice: **a tile
 * is not a thing.** One temperature per tile averaged a rail, a pipe and a furnace shell into a lump;
 * one segment per tile lets them evict each other. Both come from indexing by position when the
 * position is shared.
 *
 * ### Why all four now rather than a pipe layer beside the rails
 *
 * The narrower change — add a second list for pipes, leave power and signal until something needs
 * them — builds nothing speculative and was the cheaper diff. It also leaves the shape of the problem
 * unfixed, so the third layer pays the same cost again and the fourth pays it a third time. The
 * layers are already enumerated by [Conduit]; keying by it is less code than two parallel fields and
 * removes the question of where the next one goes.
 *
 * Power and signal transport still do not exist. This gives them somewhere to live, not behaviour —
 * an empty layer costs one list of nulls and no ticks.
 *
 * ### The rail layer is still special, and that is fine
 *
 * Packets, [FlowField], gauges, bridges and motion are all rail concepts and read
 * `conduits[Conduit.Rail]` through [VesselState.rails]. They are not being generalised on
 * speculation: a pipe does not carry a packet, and the fluid layer that will read
 * `conduits[Conduit.Pipe]` wants an aperture field rather than a flow field. What spans layers is
 * exactly what physically spans them — the thermal contact graph and the save — and both walk [all].
 */
class Conduits private constructor(private val layers: Array<List<Segment?>>) {

    /** One layer, tile by tile. Always [tileCount] long, with nulls where nothing is laid. */
    operator fun get(conduit: Conduit): List<Segment?> = layers[conduit.ordinal]

    fun at(conduit: Conduit, tile: Int): Segment? = layers[conduit.ordinal][tile]

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
    inline fun all(action: (Conduit, Int, Segment) -> Unit) {
        for (conduit in Conduit.entries) {
            val layer = this[conduit]
            for (tile in layer.indices) action(conduit, tile, layer[tile] ?: continue)
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
