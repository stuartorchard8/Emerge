package org.emerge.demo.outofspace.world

/**
 * Which consumers can use which standing material — the one thing [FlowGraph] asks about *matter*.
 *
 * ⛔ **This is a deliberate, and deliberately narrow, hole in the graph's material-blindness.** The
 * graph says nothing about whether the machine at the far end has room or takes what is offered;
 * those are questions about *now*, answered when a packet is offered, and keeping them out is the
 * whole point. This is not one of them. It is needed for exactly one question — whether a lump
 * standing on producer-less track justifies a consumer in taking an edge — and a lump justifies
 * nobody who cannot eat it.
 *
 * Without it the justification is blind and reads absurdly: 100kg of **titanium** standing in a
 * corridor told a run of **iron** rail ghosts that they had something to gain by reversing that
 * corridor, so they took it, and the lump behind them could not follow. Each lump had to be absorbed
 * before the next was released — a queue delivering one at a time, for no reason a player could see.
 * Traced in Stu's save, 2026-08-20.
 *
 * Consumers are grouped into **classes** rather than asked one at a time, because the answer is
 * folded into a count per subtree of the bridge forest and one count per class is the whole cost.
 * A vessel has a handful of distinct bills, so it is a handful of [IntArray]s over a decomposition
 * that is computed once either way.
 *
 * [BLIND] restores the old behaviour exactly and is the default: with one class that admits
 * everything, every per-class count equals the union and nothing can tell the difference.
 */
interface Appetites {
    /** How many classes there are; [classOf] returns an index below this. */
    val classes: Int

    /** Which class the consumer seeded at [sink] belongs to. */
    fun classOf(sink: TileIndex): Int

    /** Whether class [cls] can use the material standing on [lump]. */
    fun admits(cls: Int, lump: TileIndex): Boolean

    companion object {
        /** One class, admitting everything: the graph as it was before any of this. */
        val BLIND: Appetites = object : Appetites {
            override val classes: Int get() = 1
            override fun classOf(sink: TileIndex): Int = 0
            override fun admits(cls: Int, lump: TileIndex): Boolean = true
        }
    }
}

/**
 * Where material is allowed to travel, tile by tile.
 *
 * The graph is a set of **permitted moves**: for each tile, the directions a packet sitting on it
 * may leave in. Nothing else. It says nothing about whether the machine at the far end has room,
 * what form it accepts, or whether the tile ahead is occupied this tick — those are questions about
 * *now*, answered when a packet is actually offered, and keeping them out of here is the whole
 * point. A run's shape changes when track or buildings change; it does not change because a smelter
 * filled up.
 *
 * That separation is what the old distance-to-nearest-sink model could not express. It had to demote
 * a full consumer to keep traffic moving past it, which meant machine state leaked into the topology
 * and every rule about direction had to be restated in terms of fullness.
 *
 * Build via [FlowGraph.build] — there is no public constructor to enforce this.
 */
class FlowGraph internal constructor(
    /** Per tile, a bit per [Direction.ordinal]: may a packet here leave that way? */
    private val allowed: ByteArray,
    private val _tiles: Set<TileIndex>,
    private val _sinks: Set<TileIndex>,
    private val _order: List<TileIndex>,
    private val _feeders: Map<TileIndex, List<TileIndex>>,
    private val grid: Grid,
) {
    val tiles: Set<TileIndex> get() = _tiles

    val sinks: Set<TileIndex> get() = _sinks

    /**
     * Every tile that takes part in the flow, downstream first.
     *
     * A tile always appears after every tile it can send material to, which is what lets
     * [advanceSegments] empty a packed run in a single pass: each tile is vacated before the one
     * behind it is asked to move.
     */
    val order: List<TileIndex> get() = _order

    /** True when [tile] has somewhere to send material, or is itself a destination. */
    fun isFed(tile: TileIndex): Boolean =
        (tile in _tiles) && (allowed.getOrElse(tile.index) { 0 }.toInt() != 0 || tile in _sinks)

    /**
     * How many ways material on [tile] may leave it — 0, or 1 for an ordinary length of run.
     *
     * Cheap enough for the hot path, which [successorDirections] is not: it exists so the transport
     * layer can ask "does this lump have a *choice*", and a lump with no choice must never be
     * rationed. See [Whitelist.permits].
     */
    fun outDegree(tile: TileIndex): Int {
        if (tile !in _tiles) return 0
        var bits = allowed[tile.index].toInt() and 0xF
        var n = 0
        while (bits != 0) { n += bits and 1; bits = bits shr 1 }
        return n
    }

    fun allows(tile: TileIndex, dir: Direction): Boolean =
        tile in _tiles && (allowed[tile.index].toInt() and (1 shl dir.ordinal)) != 0

    /**
     * The directions [tile] may send material, ordered by the index of the tile they lead to.
     *
     * The ordering is arbitrary but fixed, and it is a deliberate choice not to use [Direction]'s own
     * declaration order: every other tie-break in the transport layer is by ascending tile index, and
     * one less arbitrary ordering to remember is worth the constant below.
     */
    fun successorDirections(tile: TileIndex): List<Direction> =
        if (tile !in _tiles) emptyList() else BY_TILE.filter { allows(tile, it) }

    /** The tiles [tile] may send material to, ascending. */
    fun successorTiles(tile: TileIndex): List<TileIndex> =
        successorDirections(tile).map { grid.neighbour(tile, it) }

    /**
     * The tiles that may send material *to* [tile], ascending — a merge, where more than one.
     *
     * The mirror of [successorTiles], and needed for the same reason a fork needs its cursor: two
     * runs joining have to take turns, or the one that happens to sort first starves the other
     * outright. Ascending order is arbitrary but fixed, so the turn-taking is reproducible.
     */
    fun feeders(tile: TileIndex): List<TileIndex> = _feeders[tile] ?: emptyList()

    fun neighbour(tile: TileIndex, dir: Direction): TileIndex = grid.neighbour(tile, dir)

    companion object {
        /** Directions in ascending destination-tile order: up, left, right, down. */
        private val BY_TILE = listOf(Direction.Up, Direction.Left, Direction.Right, Direction.Down)

        /**
         * Work out which way material may travel over a network of track.
         *
         * The walk runs **outward from the sinks**, upstream, and each step grants the tile it
         * reaches permission to move back the way the walk came. So permission only ever exists along
         * a route that reaches a consumer, and a branch with nothing on the end of it is never
         * entered — nobody has to write a rule saying so.
         *
         * [sources] are where material enters. They matter at a junction, and only there. Under a
         * pure shortest-path rule a fork whose branches are unequal is not a fork at all: the walk
         * arrives at the junction from the short branch first and the long one never gets its turn,
         * so a line splitting to a vent two tiles away and a tank three tiles away puts everything
         * down the vent. Knowing where material came *in* is what tells the junction that both ways
         * are forward.
         *
         * **An edge carries material one way only.** Tiles start completely disconnected and the
         * walk connects them one direction at a time; a pair of tiles never ends up with permission
         * both ways. A fork is several one-way edges leaving the same tile, which is a different
         * thing and still allowed.
         *
         * That makes the order the walk claims edges in matter, and claiming them greedily is wrong.
         * A consumer walking outward looks for a producer in *both* directions, and the direction
         * with no producer down it still gets claimed — so a line of six tanks fed from one end has
         * its first tank claim the edge toward the second, and when that tank fills, everything
         * behind it jams rather than carrying on down the line. The claim was never justified: there
         * is no producer that way.
         *
         * Hence **leading**. An edge is leading when it is part of a route that actually starts at a
         * source. A source marks the edges pointing out of it leading, and a leading tile passes the
         * mark along its own outgoing edges, so the mark runs downstream from every producer. Two
         * rules follow, and between them they let a sink claim what it needs without breaking what a
         * producer depends on:
         *
         *  - landing on a leading tile does the leading step *instead of* looking forward, so a
         *    justified route is never re-litigated by a walk that arrives later;
         *  - a tile looking forward may take back its own outgoing edge, because an unjustified
         *    claim is exactly what wants overriding — but a leading tile is never looked at that
         *    way, so a justified edge cannot be taken.
         *
         * [carrying] reports what is standing on a tile, and it matters on **producer-less track and
         * only there**: with no source in a component nothing can be justified, so the rules above
         * fall silent and the last consumer traversed takes the line. A lump on such track is the
         * only material that will ever be on it, so it is what a revocation is measured against —
         * see the note at the head of the body. It is not a source: it justifies nothing else, and
         * confers no [leading].
         *
         * Note what is deliberately *not* protected. A walk stepping **past** a source can claim the
         * edge beyond it pointing back into the source, which is backwards; the consumer out there
         * has to be able to take that edge back or it would starve on a network that plainly ought
         * to feed it. Only leading protects, and only a producer confers it.
         *
         * Sinks are traversed one at a time, each draining the queue completely before the next
         * starts. That is not an optimisation detail — the marks laid down by one traversal are what
         * the next one is forbidden to disturb.
         */
        fun build(
            tileSet: Set<TileIndex>,
            sources: Set<TileIndex>,
            sinks: Set<TileIndex>,
            linked: (TileIndex, Direction) -> Boolean,
            grid: Grid,
            carrying: (TileIndex) -> Boolean = { false },
            appetites: Appetites = Appetites.BLIND,
        ): FlowGraph {
            if (tileSet.isEmpty()) return empty()

            val allowed = ByteArray(grid.size)

            // Which side of each edge a producer lies on. Computed once: it is a fact about the
            // shape of the track, and every traversal asks it the same way.
            var sourceSide = SourceSide.of(tileSet, sources, linked, grid)

            // ── Matter already standing on track no producer reaches ─────────
            //
            // ⛔ **On such track, a lump IS where material enters** — it is the only thing that can
            // be. With no producer anywhere in the component, nothing justifies anything, every
            // revocation below is permitted, and the last consumer traversed simply takes the line.
            // A player who lays a stub, drops titanium on it and draws a run of iron rail beside it
            // watches the rails claim the corridor and the titanium sit still for ever, three tiles
            // from the extractor site that wants it. Found in Stu's save.
            //
            // ⚠️ **[carrying] answers the justification question and nothing else.** A loaded tile
            // is deliberately NOT added to [sources]: a producer's outgoing edges are unrevocable
            // and confer [leading], and granting a packet either would make the shape of a run a
            // function of where its lumps happen to be standing this tick — the one thing this class
            // promises it is not. All a lump does is let a walk say "there is something that way",
            // which is exactly what a revocation has to be able to say.
            //
            // ⚠️ **Only where no producer reaches.** Feeding load into [SourceSide] can only ever
            // make [SourceSide.beyond] answer true more often, which *permits* revocations rather
            // than refusing them — and the two guards below exist to refuse exactly the ones a
            // loaded belt would otherwise justify. On fed track the answer is unchanged, bit for
            // bit.
            //
            // ⚠️ **A lump justifies roads past it, never the road out from under it** — the standing
            // set goes in as [SourceSide.weak] as well, and [SourceSide.anyOther] is what the guards
            // below read. See the note there; without it a tapped belt splits at the lump.
            val standing = tileSet.filterTo(mutableSetOf()) { carrying(it) && !sourceSide.anyAtAll(it) }
            // ⛔ **One object decides both the rows and the row a walk reads**, or a walk asks for
            // a class the counts were never built with. Where nothing is standing there is nothing
            // to weigh against an appetite — a real producer counts for every class — so the
            // distinction collapses, and it has to collapse in *both* places at once.
            val appetite = if (standing.isEmpty()) Appetites.BLIND else appetites
            if (standing.isNotEmpty()) {
                sourceSide = SourceSide.of(tileSet, sources, linked, grid, standing, appetite)
            }

            // On a route out of a producer. Survives across traversals — that is the whole point.
            val leading = BooleanArray(grid.size)

            // Traversed in tile order, not set order, and one at a time. Where two sinks contend for
            // the same stretch of track the order is part of the result, and a [Set]'s order is not
            // something a replay can rely on.
            for (sink in sinks.sortedBy { it.index }) {
                if (sink in tileSet) {
                    // ⛔ **The class the walk is seeded with, carried the whole way.** What may be
                    // taken back is a question about what *this* consumer has to gain, and the
                    // consumer is the sink the walk started at — not the tile it has reached.
                    val cls = appetite.classOf(sink)
                    traverse(sink, cls, allowed, leading, tileSet, sources, sinks, sourceSide, linked, grid)
                }
            }

            return FlowGraph(
                allowed,
                tileSet,
                sinks.filterTo(mutableSetOf()) { it in tileSet },
                walkOrder(allowed, tileSet, sinks, grid),
                feedersOf(allowed, tileSet, grid),
                grid,
            )
        }

        /**
         * One consumer's traversal, run to exhaustion.
         *
         * The queue mixes two kinds of visit and the tile's own marks decide which it gets. Both
         * guards below are per-traversal: a mark laid down here has to be re-examinable by the next
         * consumer, or a tile that becomes leading late would never pass the mark on.
         */
        private fun traverse(
            seed: TileIndex,
            cls: Int,
            allowed: ByteArray,
            leading: BooleanArray,
            tileSet: Set<TileIndex>,
            sources: Set<TileIndex>,
            sinks: Set<TileIndex>,
            sourceSide: SourceSide,
            linked: (TileIndex, Direction) -> Boolean,
            grid: Grid,
        ) {
            // Steps already taken, keyed by tile and the direction it was reached from. A tile
            // reached again from the same side has nothing new to offer; reached from a *different*
            // side it is genuinely new work. This is also what makes the traversal terminate.
            val walked = BooleanArray(grid.size * 5)
            val propagated = BooleanArray(grid.size)

            val queue = ArrayDeque<Int>()
            queue.addLast(encode(seed, null))

            while (queue.isNotEmpty()) {
                val step = queue.removeFirst()
                val at = TileIndex(step / 5)
                val cameFrom = Direction.ALL.getOrNull(step % 5)

                // Landing on a leading tile does this *instead of* looking forward. A justified
                // route is not re-litigated by a walk that happens to arrive later.
                if (leading[at.index]) {
                    if (propagated[at.index]) continue
                    propagated[at.index] = true
                    for (dir in Direction.ALL) {
                        if (!bit(allowed, at, dir)) continue
                        val next = grid.neighbour(at, dir)
                        if (next == TileIndex.NONE || next !in tileSet || leading[next.index]) continue
                        leading[next.index] = true
                        queue.addLast(encode(next, dir.opposite))
                    }
                    continue
                }

                if (walked[step]) continue
                walked[step] = true

                for (dir in Direction.ALL) {
                    if (dir == cameFrom) continue
                    if (!linked(at, dir)) continue
                    val next = grid.neighbour(at, dir)
                    if (next == TileIndex.NONE || next !in tileSet) continue

                    // ⛔ **A consumer with no producer behind it has nothing to send.** Giving one an
                    // outgoing edge cannot move material that was never going to arrive, and the
                    // edge is not free: it is the same edge, pointing the other way, that the
                    // consumer needs to be fed by. A ghost rail hanging off a loaded belt, told it
                    // may send its iron back down the spur it was waiting for. Found in Stu's save.
                    if (next in sinks && sourceSide.anyOther(at) && !sourceSide.beyond(at, dir, cls)) continue

                    // Already pointing at us: nothing to claim, and nothing new to say to it.
                    if (bit(allowed, next, dir.opposite)) continue

                    // Pointing the other way. Taking it back is the correction the whole mechanism
                    // exists for — this tile is not leading, so its claim to that edge was never
                    // justified by a producer and a consumer that needs the edge may have it.
                    //
                    // ⛔ **Unless this tile IS a producer.** Its outgoing edges are justified by what
                    // it is, and reversing one points material *into* the tile that was feeding the
                    // network. [leading] protects a producer's successors and never the producer
                    // itself — which is exactly the tile whose claims were never in doubt — so
                    // without this a second consumer asking later takes the first one's road away.
                    // A storage feeding a ghost above it and a bridge to its right kept the bridge
                    // and starved the ghost for good. Found in Stu's save.
                    if (bit(allowed, at, dir)) {
                        if (at in sources) continue
                        // ⛔ **Taking an edge back has to be justified by a producer too.** Hunting
                        // upstream where no producer lies cannot find anything, so all such a step
                        // can do is undo the claim of a consumer that got there first — and the walk
                        // that did it gains nothing whatever.
                        //
                        // A spur of ghost rail hanging off a loaded belt, its feed edge reversed by a
                        // sink further along the line that could never have been fed through it.
                        // Found in Stu's save, twice, at two different depths — which is why this is
                        // stated about the whole branch beyond the edge and not about the tile on
                        // the end of it.
                        //
                        // Note the asymmetry with granting, which is still free: an *un*contested
                        // edge into a branch with no producer is how material stranded on a stub
                        // drains back out, and nothing is taken from anyone to make it.
                        //
                        // ⚠️ **Asked only of a network that HAS a producer.** With none anywhere,
                        // nothing justifies anything and every direction is as good as every other,
                        // so the old greedy answer stands: the last consumer traversed takes the
                        // line. That is the better answer there, and the one the belt tests reason
                        // about — a line that commits is a through-route, where one that splits down
                        // the middle sends half its traffic back at a machine that already said no.
                        if (sourceSide.anyOther(at) && !sourceSide.beyond(at, dir, cls)) continue
                        revoke(allowed, at, dir)
                    }
                    grant(allowed, next, dir.opposite)

                    queue.addLast(encode(next, dir.opposite))
                }

                // A producer is where leading starts: every edge out of it heads somewhere material
                // can actually go.
                if (at in sources) {
                    for (dir in Direction.ALL) {
                        if (!bit(allowed, at, dir)) continue
                        val next = grid.neighbour(at, dir)
                        if (next == TileIndex.NONE || next !in tileSet || leading[next.index]) continue
                        leading[next.index] = true
                        queue.addLast(encode(next, dir.opposite))
                    }
                }
            }
        }

        /** A walk step: a tile plus the direction it was reached from (4 = a sink, reached from nowhere). */
        private fun encode(tile: TileIndex, cameFrom: Direction?): Int = tile.index * 5 + (cameFrom?.ordinal ?: 4)

        private fun bit(allowed: ByteArray, tile: TileIndex, dir: Direction): Boolean =
            (allowed[tile.index].toInt() and (1 shl dir.ordinal)) != 0

        private fun revoke(allowed: ByteArray, tile: TileIndex, dir: Direction) {
            allowed[tile.index] = (allowed[tile.index].toInt() and (1 shl dir.ordinal).inv()).toByte()
        }

        private fun grant(allowed: ByteArray, tile: TileIndex, dir: Direction) {
            allowed[tile.index] = (allowed[tile.index].toInt() or (1 shl dir.ordinal)).toByte()
        }

        /**
         * Tiles in the order [advanceSegments] must walk them: a tile always after everything it can
         * send to. That is what lets a packed run empty in a single pass — each tile is vacated
         * before the one behind it is asked to move.
         *
         * This is a topological order over the permitted edges, and it has to be. Layering by hops
         * from the nearest sink looks equivalent and is not: a consumer partway along a line that
         * carries on to a second consumer is zero hops from itself, so it sorts to the very front —
         * ahead of the tiles it feeds. Material it refuses can then only move on the ticks when the
         * tile ahead happened to already be empty, which halves the throughput of the whole run
         * behind it.
         *
         * A loop has no topological order at all, and that is not a failure — its tiles come last,
         * in tile order, and `arrived` is what keeps a packet on a cycle to one tile per advance.
         */
        private fun walkOrder(allowed: ByteArray, tileSet: Set<TileIndex>, sinks: Set<TileIndex>, grid: Grid): List<TileIndex> {
            val remaining = HashMap<TileIndex, Int>()
            val feeders = HashMap<TileIndex, MutableList<TileIndex>>()
            for (tile in tileSet) {
                var outgoing = 0
                for (dir in Direction.ALL) {
                    if (!bit(allowed, tile, dir)) continue
                    val next = grid.neighbour(tile, dir)
                    if (next == TileIndex.NONE || next !in tileSet) continue
                    outgoing++
                    feeders.getOrPut(next) { mutableListOf() }.add(tile)
                }
                remaining[tile] = outgoing
            }

            // Taken in waves, each wave sorted, so the result is a total order that does not depend
            // on sort stability or on the iteration order of a set.
            val order = ArrayList<TileIndex>(tileSet.size)
            val placed = HashSet<TileIndex>()
            var wave = tileSet.filter { remaining.getValue(it) == 0 }.sortedBy { it.index }
            while (wave.isNotEmpty()) {
                val next = mutableListOf<TileIndex>()
                for (tile in wave) {
                    if (!placed.add(tile)) continue
                    order.add(tile)
                    for (feeder in feeders[tile].orEmpty()) {
                        val left = remaining.getValue(feeder) - 1
                        remaining[feeder] = left
                        if (left == 0) next.add(feeder)
                    }
                }
                wave = next.sortedBy { it.index }
            }
            // Whatever is left is on a cycle.
            if (order.size < tileSet.size) order.addAll(tileSet.filter { it !in placed }.sortedBy { it.index })

            // Only tiles that take part: somewhere to send material, or somewhere to be consumed.
            return order.filter { allowed[it.index].toInt() != 0 || it in sinks }
        }

        /** Inverts the permission bits: for each tile, who may send material to it. */
        private fun feedersOf(allowed: ByteArray, tileSet: Set<TileIndex>, grid: Grid): Map<TileIndex, List<TileIndex>> {
            val feeders = HashMap<TileIndex, MutableList<TileIndex>>()
            for (tile in tileSet.sortedBy { it.index }) {
                for (dir in Direction.ALL) {
                    if (!bit(allowed, tile, dir)) continue
                    val next = grid.neighbour(tile, dir)
                    if (next == TileIndex.NONE || next !in tileSet) continue
                    feeders.getOrPut(next) { mutableListOf() }.add(tile)
                }
            }
            return feeders
        }

        /**
         * Which side of an edge a producer lies on — the question a revocation has to answer.
         *
         * An edge already claimed may only be taken back by a walk that has a producer behind it
         * that way. Answering that per step means "is a source reachable from `next` without coming
         * back through `at`", which is a question about **bridges**: cutting a non-bridge edge
         * disconnects nothing, so the answer is simply whether the connected component holds a
         * producer at all; cutting a bridge splits the network in two, and the answer is whether the
         * far half holds one.
         *
         * So the whole thing is computed once per build — Tarjan's bridges, the 2-edge-connected
         * components they separate, and a producer count per subtree of the resulting forest.
         */
        private class SourceSide(
            private val comp: IntArray,
            private val parent: IntArray,
            /**
             * Producers per subtree and per whole tree, **one row per class** — see [Appetites].
             *
             * ⚠️ The last row is the **union**: every real producer plus every standing lump,
             * whatever anyone thinks of it. The topology underneath is one decomposition shared by
             * all the rows, which is what makes a class nearly free.
             */
            private val subtree: Array<IntArray>,
            private val treeTotal: Array<IntArray>,
            private val root: IntArray,
            private val weak: BooleanArray,
            private val grid: Grid,
        ) {
            /** The union row: everything that could be a producer to somebody. */
            private val all: Int get() = subtree.size - 1

            /** Does the network [at] sits on have any producer at all? */
            fun anyAtAll(at: TileIndex): Boolean {
                val a = comp[at.index]
                return a >= 0 && treeTotal[all][root[a]] > 0
            }

            /**
             * The same question, discounting a lump standing on [at] itself.
             *
             * ⛔ **A lump cannot justify a road out of the tile it is standing on.** [anyAtAll] is a
             * precondition rather than an answer: it says justification is a meaningful question on
             * this network at all, and every guard that reads it goes on to ask [beyond], which is
             * about what lies past the edge. Material under one's feet lies past no edge in any
             * direction, so where it is the *only* producer there is nothing to weigh and the honest
             * answer is the free-for-all — which is what bare track has always done.
             *
             * ⛔ **Only a standing lump is discounted, never a real producer.** A source is a source
             * whichever tile the walk is standing on, and a dead-end sink next to one must still be
             * refused an outgoing edge pointing back into it — see [FlowGraph.build]'s note on what
             * a walk may claim past a producer, and the cul-de-sac it used to rob.
             *
             * Without this a tapped belt splits: the walk from the tank could no longer take back
             * the claim the full processor behind the lump had made, the lump found itself at a fork
             * with a road back to a machine that had already said no, took it, and stopped there for
             * good. The same failure that retired the nearest-consumer tie-break.
             */
            fun anyOther(at: TileIndex): Boolean {
                val a = comp[at.index]
                if (a < 0) return false
                return treeTotal[all][root[a]] - (if (weak[at.index]) 1 else 0) > 0
            }

            /**
             * Is a producer **class [cls] can use** reachable from [at]'s neighbour in [dir], other
             * than back through [at]?
             *
             * ⛔ **Per class, because this is what a walk has to gain by taking an edge**, and a
             * consumer gains nothing from material it will not accept. Real producers count for
             * every class — a source is a source, and what comes off it is not this question. Only
             * standing lumps are weighed against an appetite. See [Appetites].
             */
            fun beyond(at: TileIndex, dir: Direction, cls: Int): Boolean {
                val next = grid.neighbour(at, dir)
                if (next == TileIndex.NONE) return false
                val a = comp[at.index]
                val b = comp[next.index]
                if (a < 0 || b < 0) return false
                val sub = subtree[cls]
                val total = treeTotal[cls]
                // Not a bridge: cutting it leaves both ends able to reach everything they could
                // before, so the only question left is whether this network has a producer at all.
                if (a == b) return total[root[a]] > 0
                return if (parent[b] == a) sub[b] > 0 else total[root[a]] - sub[a] > 0
            }

            companion object {
                fun of(
                    tileSet: Set<TileIndex>,
                    sources: Set<TileIndex>,
                    linked: (TileIndex, Direction) -> Boolean,
                    grid: Grid,
                    weak: Set<TileIndex> = emptySet(),
                    appetites: Appetites = Appetites.BLIND,
                ): SourceSide {
                    val n = grid.size
                    fun neighbours(t: TileIndex): List<Direction> =
                        Direction.ALL.filter {
                            val x = grid.neighbour(t, it)
                            linked(t, it) && x != TileIndex.NONE && x in tileSet
                        }

                    // ── Bridges, by an iterative Tarjan. Iterative because a run of track is as
                    // long as the player cares to draw it, and a recursive walk over one would be a
                    // stack overflow nobody could see coming from the shape of their factory.
                    val disc = IntArray(n) { -1 }
                    val low = IntArray(n)
                    val bridges = HashSet<Long>()
                    fun key(a: TileIndex, b: TileIndex): Long {
                        val lo = minOf(a.index, b.index).toLong()
                        val hi = maxOf(a.index, b.index).toLong()
                        return lo * n + hi
                    }
                    var timer = 0
                    for (start in tileSet.sortedBy { it.index }) {
                        if (disc[start.index] >= 0) continue
                        // tile, the tile it was entered from, and how far through its neighbours.
                        val stack = ArrayDeque<IntArray>()
                        disc[start.index] = timer; low[start.index] = timer; timer++
                        stack.addLast(intArrayOf(start.index, -1, 0))
                        while (stack.isNotEmpty()) {
                            val frame = stack.last()
                            val at = TileIndex(frame[0])
                            val dirs = neighbours(at)
                            if (frame[2] < dirs.size) {
                                val dir = dirs[frame[2]]
                                frame[2]++
                                val next = grid.neighbour(at, dir)
                                if (next.index == frame[1]) continue
                                if (disc[next.index] >= 0) {
                                    if (disc[next.index] < low[at.index]) low[at.index] = disc[next.index]
                                } else {
                                    disc[next.index] = timer; low[next.index] = timer; timer++
                                    stack.addLast(intArrayOf(next.index, at.index, 0))
                                }
                            } else {
                                stack.removeLast()
                                val from = frame[1]
                                if (from >= 0) {
                                    if (low[at.index] < low[from]) low[from] = low[at.index]
                                    if (low[at.index] > disc[from]) bridges.add(key(TileIndex(from), at))
                                }
                            }
                        }
                    }

                    // ── The 2-edge-connected components: everything reachable without crossing one.
                    val comp = IntArray(n) { -1 }
                    var comps = 0
                    for (start in tileSet.sortedBy { it.index }) {
                        if (comp[start.index] >= 0) continue
                        val id = comps++
                        val queue = ArrayDeque<TileIndex>()
                        comp[start.index] = id
                        queue.addLast(start)
                        while (queue.isNotEmpty()) {
                            val at = queue.removeFirst()
                            for (dir in neighbours(at)) {
                                val next = grid.neighbour(at, dir)
                                if (comp[next.index] >= 0 || key(at, next) in bridges) continue
                                comp[next.index] = id
                                queue.addLast(next)
                            }
                        }
                    }

                    // ── The forest those components form, one node per component and one edge per
                    // bridge, with a producer count per subtree.
                    val adjacency = Array(comps) { mutableListOf<Int>() }
                    for (at in tileSet) {
                        for (dir in neighbours(at)) {
                            val next = grid.neighbour(at, dir)
                            if (key(at, next) !in bridges) continue
                            adjacency[comp[at.index]].add(comp[next.index])
                        }
                    }
                    // One row per class, plus the union on the end. A real producer is counted in
                    // every row: it is a source whatever anyone wants, and what comes off it is not
                    // the question here. A standing lump is counted only where it can be used.
                    val rows = appetites.classes + 1
                    val union = rows - 1
                    val own = Array(rows) { IntArray(comps) }
                    for (source in sources) {
                        if (source !in tileSet) continue
                        val c = comp[source.index]
                        for (r in 0 until rows) own[r][c]++
                    }
                    for (lump in weak) {
                        if (lump !in tileSet) continue
                        val c = comp[lump.index]
                        own[union][c]++
                        for (r in 0 until union) if (appetites.admits(r, lump)) own[r][c]++
                    }

                    val parent = IntArray(comps) { -1 }
                    val root = IntArray(comps) { -1 }
                    val subtree = Array(rows) { IntArray(comps) }
                    val treeTotal = Array(rows) { IntArray(comps) }
                    for (seed in 0 until comps) {
                        if (root[seed] >= 0) continue
                        // Down first, then back up the same list in reverse: a child is always
                        // summed before the parent that wants its total.
                        val order = mutableListOf(seed)
                        root[seed] = seed
                        var head = 0
                        while (head < order.size) {
                            val at = order[head++]
                            for (next in adjacency[at]) {
                                if (root[next] >= 0) continue
                                root[next] = seed
                                parent[next] = at
                                order.add(next)
                            }
                        }
                        for (r in 0 until rows) {
                            val sub = subtree[r]
                            for (at in order) sub[at] = own[r][at]
                            for (i in order.indices.reversed()) {
                                val at = order[i]
                                val up = parent[at]
                                if (up >= 0) sub[up] += sub[at]
                            }
                            val total = sub[seed]
                            for (at in order) treeTotal[r][at] = total
                        }
                    }

                    val standing = BooleanArray(n)
                    for (tile in weak) if (tile in tileSet) standing[tile.index] = true
                    return SourceSide(comp, parent, subtree, treeTotal, root, standing, grid)
                }
            }
        }

        private fun empty(): FlowGraph =
            FlowGraph(ByteArray(0), emptySet(), emptySet(), emptyList(), emptyMap(), Grid(0, 0))
    }
}

/**
 * Which way each fork last sent material.
 *
 * Maps a tile to an index into that tile's [FlowGraph.successorDirections]. This is the only mutable
 * state in the transport layer; the graph itself does not change between conduit edits.
 *
 * Only advances the cursor when a branch is actually **used** — a jam on one side must not quietly
 * halve the throughput of the other.
 */
class FlowCursors(
    initial: Map<TileIndex, Int> = emptyMap(),
    merges: Map<TileIndex, Int> = emptyMap(),
) {
    internal val cursors = HashMap(initial)

    /**
     * Which feeder each merge should take from next, keyed by the tile being fed into.
     *
     * A separate map from [cursors] because a tile can be a fork *and* a merge at once — several
     * ways in and several ways out — and the two turns have nothing to do with each other.
     */
    internal val merges = HashMap(merges)

    /** Read-only view of fork cursor state. */
    val forkCursors: Map<TileIndex, Int> get() = cursors

    /** Read-only view of merge cursor state. */
    val mergeCursors: Map<TileIndex, Int> get() = merges

    /**
     * Which of [feeders] may move into [target] this pass, or -1 if none can.
     *
     * [ready] is asked whether a feeder actually has something to hand over right now, so a merge
     * whose turn falls on an empty run passes it straight on rather than idling — the same rule the
     * fork follows, where a blocked branch must not consume its turn.
     */
    fun preferredFeeder(feeders: List<TileIndex>, target: TileIndex, ready: (TileIndex) -> Boolean): TileIndex {
        if (feeders.isEmpty()) return TileIndex.NONE
        if (feeders.size == 1) return if (ready(feeders[0])) feeders[0] else TileIndex.NONE
        val start = merges[target] ?: 0
        for (step in feeders.indices) {
            val pick = feeders[(start + step) % feeders.size]
            if (ready(pick)) return pick
        }
        return TileIndex.NONE
    }

    /** Records that [from] took its turn into [target], so the next turn falls to the one after it. */
    fun mergeUsed(feeders: List<TileIndex>, target: TileIndex, from: TileIndex) {
        if (feeders.size <= 1) return
        val index = feeders.indexOf(from)
        if (index >= 0) merges[target] = (index + 1) % feeders.size
    }

    /**
     * Pick a way out of [tile], preferring one that is free, and alternating between them so a fork
     * splits its throughput rather than favouring a branch. Null when there is nowhere to go.
     */
    fun choose(graph: FlowGraph, tile: TileIndex, isFree: (TileIndex) -> Boolean): Direction? {
        val options = graph.successorDirections(tile)
        if (options.isEmpty()) return null
        if (options.size == 1) {
            return options[0].takeIf { isFree(graph.neighbour(tile, it)) }
        }
        val start = cursors[tile] ?: 0
        for (step in options.indices) {
            val index = (start + step) % options.size
            val pick = options[index]
            if (isFree(graph.neighbour(tile, pick))) {
                // Advance past the branch actually used, not past the one we hoped to use.
                cursors[tile] = (index + 1) % options.size
                return pick
            }
        }
        return null
    }

    /** Snapshot the fork cursor state for persistence across ticks. */
    fun snapshot(): Map<TileIndex, Int> = cursors.toMap()

    /** Snapshot the merge cursor state for persistence across ticks. */
    fun mergeSnapshot(): Map<TileIndex, Int> = merges.toMap()

    // By value, not by identity. This lives in [VesselState], which is compared and digested to
    // check that a replay came out the same — and a mutable object with the default identity
    // toString silently makes any digest containing it unequal to every other run of the same sim.
    override fun equals(other: Any?): Boolean =
        this === other || (other is FlowCursors && cursors == other.cursors && merges == other.merges)

    override fun hashCode(): Int = 31 * cursors.hashCode() + merges.hashCode()

    override fun toString(): String {
        fun show(m: Map<TileIndex, Int>) = m.entries.sortedBy { it.key.index }.joinToString { "${it.key}=${it.value}" }
        return "FlowCursors(forks[${show(cursors)}] merges[${show(merges)}])"
    }

    /** Restore from a previously snapshot state. */
    fun restore(map: Map<TileIndex, Int>, mergeMap: Map<TileIndex, Int> = emptyMap()) {
        cursors.clear()
        cursors.putAll(map)
        merges.clear()
        merges.putAll(mergeMap)
    }
}
