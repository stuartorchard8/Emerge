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
        return Conduits(next, tracks.copyOf()).swept()
    }

    /**
     * This, with no matter left at any tile that carries no segment.
     *
     * ### What this deliberately no longer does
     *
     * It used to make the matter agree with the track in *both* directions — a laid tile with no
     * metal got a full [conduitBillOfMaterials], conjured at ambient. That made a segment's
     * existence and a segment's matter one fact, and it is exactly the identity a **ghost** breaks:
     * ghost track has a representation and no mass, and fills itself from the network. Laying can
     * therefore no longer conjure, and a segment that arrives here matter-free is left that way.
     *
     * The clearing half stays, and stays here rather than at the call sites, because matter at a
     * tile with no segment is not a ghost — it is an orphan no walk can reach and no ledger can
     * see. Applying this twice still does nothing, so both callers can say it without knowing what
     * the other changed.
     *
     * A world that means to state *finished* track says [finished]; a world that means to lay
     * ghosts says nothing.
     */
    private fun swept(): Conduits {
        for (conduit in Conduit.entries) {
            val layer = layers[conduit.ordinal]
            for (i in layer.indices) {
                val tile = TileIndex(i)
                if (layer[i] == null && tracks.occupies(conduit, tile)) tracks.clear(conduit, tile)
            }
        }
        checkExclusion()
        return this
    }

    /**
     * This, with every laid tile holding a full segment's worth of metal at ambient.
     *
     * What a *stated* vessel means: a fixture, a starting ship or a save describes track that is
     * already built, not a drawing of track someone intends to build. The matter it fills in has
     * arrived from off-world, and for the callers that say this the ledger is satisfied by
     * construction — a baseline is taken from the world after it is built.
     *
     * Deliberately **not** what [swept] does. The reducer lays ghosts; only a stated world says this.
     */
    private fun finished(): Conduits {
        for (conduit in Conduit.entries) {
            val layer = layers[conduit.ordinal]
            for (i in layer.indices) {
                val tile = TileIndex(i)
                if (layer[i] != null && !tracks.occupies(conduit, tile)) tracks.lay(conduit, tile)
            }
        }
        return this
    }

    /** Whether the segment at [tile] holds every gram its kind is made of — see [TrackLayers.holdsFullBill]. */
    fun isComplete(conduit: Conduit, tile: TileIndex): Boolean =
        at(conduit, tile) != null && tracks.holdsFullBill(conduit, tile)

    /** True for a segment that is laid but not yet made of everything it needs — see [isComplete]. */
    fun isGhost(conduit: Conduit, tile: TileIndex): Boolean =
        at(conduit, tile) != null && !isComplete(conduit, tile)

    /**
     * ⛔ **A tile carries track or plumbing, never both.**
     *
     * The rule the game is named after: matter transport competes for floor space, and a player who
     * wants a belt and a pipe through the same gap has to solve that instead of stacking them. Wires
     * are deliberately not in it — [Conduit.Power] and [Conduit.Signal] ride under anything, because
     * what fights for space is stuff, not information, and a gauge sharing its tile with a wire is a
     * convenience nobody has to read a second overlay to understand.
     *
     * Checked here rather than only at placement so that the state is *unconstructible*: [with] is
     * the one door into a changed layer, [swept] already walks every tile, and a fixture or a
     * save that expresses a crossing is refused the same way the player's drag tool is. A rule
     * enforced only in the edit path is a rule the rest of the codebase gets to break by accident.
     */
    private fun checkExclusion() {
        val rail = layers[Conduit.Rail.ordinal]
        val pipe = layers[Conduit.Pipe.ordinal]
        for (i in rail.indices) {
            require(rail[i] == null || pipe[i] == null) {
                "tile $i carries both a rail and a pipe; the two compete for the floor"
            }
        }
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
            empty(rails.size).with(Conduit.Rail, rails).finished()

        /** Built layer by layer, for the save and for tests that lay more than one network. */
        fun of(tileCount: Int, vararg layers: Pair<Conduit, List<Segment?>>): Conduits {
            var out = empty(tileCount)
            for ((conduit, layer) in layers) out = out.with(conduit, layer)
            return out.finished()
        }

        /**
         * The networks as the tick reducer has them: one mutable list per conduit and the matter
         * layers it has been laying into and clearing out of all tick.
         *
         * ⚠️ Deliberately **not** [finished]: a segment the reducer created without being given
         * any metal stays matter-free, which is what a ghost is. [swept] only takes orphaned matter
         * off tiles the reducer cleared. The reducer lays explicitly, and only in creative mode.
         */
        fun of(layers: Array<out List<Segment?>>, tracks: TrackLayers): Conduits =
            Conduits(Array(Conduit.entries.size) { layers[it] }, tracks).swept()
    }
}
