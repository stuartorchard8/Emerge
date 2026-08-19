package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture

/**
 * What a sink will take, and how much of it there is left to take.
 *
 * Until now the transport layer answered "may this enter?" with a `when` at the door: a ghost asked
 * [buildableFrom] and everything else said yes. That worked while exactly one kind of sink was
 * fussy. It stops working the moment a sink needs to say *how much* it wants, because a quantity is
 * not a thing you can ask a lump — it is a thing a sink has to state about itself.
 *
 * So a sink states it, and the door reads the statement. Two shapes, and the difference between them
 * is not fussiness but **whether the appetite ever ends**:
 *
 *  - [ANYTHING] — takes any matter, for ever. Every machine is this. A storage fills up and a
 *    processor's buffer backs up, but those are *momentary*: drain them and they take more. Over its
 *    life a machine will accept an unbounded amount, which is what makes it useless as a thing to
 *    ration a network by.
 *  - [forBill] — takes only what it can be built from, and only until it is built. A construction
 *    site is the one sink in the game with a **final** total. That is what makes it the sink worth
 *    metering, and it is why this type exists at all.
 *
 * ⚠️ **Momentary fullness is deliberately not modelled here.** "The tank is full right now" is a
 * question for the delivery path, which already answers it and already backs the belt up correctly.
 * Conflating it with "this sink will never want more" would make every tank on the network look
 * finite and every network look nearly satisfied.
 *
 * Read on the hot path — [admits] is asked of every candidate direction of every loaded tile on
 * every step — so it must not allocate.
 */
class Acceptance private constructor(
    /** What may enter, as a bill of materials; null takes anything. */
    private val bill: Mixture?,
    /**
     * Mass still wanted before this sink is done for good, or [UNLIMITED].
     *
     * Not "room right now". See the class note.
     */
    val wanted: Long,
) {
    /** True when this sink's appetite has no end — every machine, and nothing else. */
    val isUnlimited: Boolean get() = wanted == UNLIMITED

    /** True when this sink is finite and has been satisfied. */
    val isSatisfied: Boolean get() = !isUnlimited && wanted <= 0L

    /**
     * Whether [mixture] is something this sink will take.
     *
     * ⛔ **The anti-exploit lives here.** A construction site is a free length of track until it is
     * paid for, so material must never pass *through* one without being usable — see
     * [buildableFrom]. The question is asked of what wants to enter, not of what the sink would
     * like to keep.
     */
    fun admits(mixture: Mixture): Boolean {
        if (isSatisfied) return false
        val want = bill ?: return true
        return buildableFrom(want, mixture)
    }

    override fun toString(): String =
        if (isUnlimited) "Acceptance(anything)" else "Acceptance(${wanted}g of $bill)"

    companion object {
        /** An appetite with no end. Not a large number — a different kind of number. */
        const val UNLIMITED: Long = Long.MAX_VALUE

        /** Takes any matter, for ever: every machine on the vessel. */
        val ANYTHING: Acceptance = Acceptance(null, UNLIMITED)

        /**
         * Takes what [bill] can be built from, and [shortfall] more of it.
         *
         * [shortfall] is what the site is *still* short by, not what it costs — a half-built rail
         * wants half a rail.
         */
        fun forBill(bill: Mixture, shortfall: Long): Acceptance = Acceptance(bill, shortfall)
    }
}

/**
 * What may usefully travel *out of* each tile — the whitelist, recorded per edge rather than per
 * door.
 *
 * A sink saying what it will take (see [Acceptance]) only answers the question at the sink's own
 * tile, and that is not the question the network needs answered. A storage three tiles upstream of
 * a construction site should not release iron because *it* is willing to let go; it should release
 * iron because something reachable from its port can use iron. Otherwise the tank empties itself
 * onto a run that has nowhere to put it, the run jams solid, and a rail marked for deconstruction
 * can never hand its metal back because there is a stalled lump standing on it. That is the
 * `ghosts.txt` deadlock, and it is a demand failure rather than a transport one.
 *
 * So the appetite is **propagated upstream**: a tile permits whatever it can consume itself, plus
 * whatever anything downstream of it can consume. One pass does it, because [FlowGraph.order]
 * already guarantees a tile appears after every tile it can send to — so by the time the walk
 * reaches a tile, all its successors are known.
 *
 * ⚠️ **A tile with no entry permits nothing**, and that is the useful case rather than an edge case:
 * a run with no consumer on the end of it never enters [FlowGraph.order] at all, so a source feeding
 * it is asking to fill a dead end and is told no.
 *
 * ⚠️ **Tiles on a cycle are approximated.** A loop has no topological order, so its tiles are walked
 * last in tile order and may be read before a successor on the same loop is known — which can only
 * ever make a tile look *less* hungry than it is. That is the safe direction: the failure is a
 * source that waits a tick, not a network that over-draws and jams.
 */
class Whitelist private constructor(
    /** Per tile: the finite appetites reachable from it. Null where nothing is. */
    private val finite: Array<MutableList<Acceptance>?>,
    /** Per tile: whether something reachable from it will take anything, for ever. */
    private val unlimited: BooleanArray,
) {
    /** True when anything at all may leave [tile] — the common case, and free to ask. */
    fun permitsAnything(tile: TileIndex): Boolean =
        tile.index in unlimited.indices && unlimited[tile.index]

    /** True when nothing downstream of [tile] wants anything: a dead end. */
    fun permitsNothing(tile: TileIndex): Boolean =
        !permitsAnything(tile) && finite.getOrNull(tile.index).isNullOrEmpty()

    /**
     * Whether [mixture] leaving [tile] has somewhere to go that can use it.
     *
     * ⚠️ Asked of every candidate direction of every loaded tile on every step, so the unlimited
     * case is answered before the mixture is so much as looked at.
     */
    fun permits(tile: TileIndex, mixture: Mixture): Boolean {
        if (permitsAnything(tile)) return true
        val wanted = finite.getOrNull(tile.index) ?: return false
        for (a in wanted) if (a.admits(mixture)) return true
        return false
    }

    companion object {
        /** Permits nothing anywhere: a world whose flow has not been worked out yet. */
        fun empty(): Whitelist = Whitelist(arrayOfNulls(0), BooleanArray(0))

        /**
         * Walk the flow downstream-first, carrying each tile's appetite back to whoever feeds it.
         *
         * [acceptanceAt] states what a tile consumes on its own account; a tile that is a sink and
         * says nothing is a machine, and a machine takes anything for ever.
         */
        fun of(flow: FlowGraph, tileCount: Int, acceptanceAt: (TileIndex) -> List<Acceptance>?): Whitelist {
            val finite = arrayOfNulls<MutableList<Acceptance>>(tileCount)
            val unlimited = BooleanArray(tileCount)

            for (tile in flow.order) {
                val i = tile.index
                var any = false
                var here: MutableList<Acceptance>? = null

                val own = acceptanceAt(tile)
                when {
                    own == null && tile in flow.sinks -> any = true
                    own != null && own.any { it.isUnlimited } -> any = true
                    // ⚠️ **Not `filter`** — the ones worth carrying upstream are the ones still
                    // WANTING something. A satisfied acceptance answers `false` to everything, so
                    // keeping those instead silently stops finite demand propagating at all and no
                    // source ever feeds a construction site again. Eighteen tests say so.
                    own != null -> here = own.filterNot { it.isSatisfied }.takeIf { it.isNotEmpty() }?.toMutableList()
                }

                for (next in flow.successorTiles(tile)) {
                    val j = next.index
                    if (unlimited[j]) { any = true; continue }
                    val theirs = finite[j] ?: continue
                    if (here == null) here = mutableListOf()
                    for (a in theirs) if (!here.contains(a)) here.add(a)
                }

                unlimited[i] = any
                // Nothing downstream is fussy *and* nothing downstream is boundless: the list is the
                // only thing left worth keeping, and only while the unlimited flag is not set.
                finite[i] = if (any) null else here
            }
            return Whitelist(finite, unlimited)
        }
    }
}
