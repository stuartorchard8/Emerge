package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.scaledRatio

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
    /**
     * What may enter, as a bill of materials; null takes anything.
     *
     * Public because the network has to weigh a lump against it *away from the sink* — see
     * [Whitelist], which counts how much of what a site is short of is already standing between a
     * given tile and that site.
     */
    val bill: Mixture?,
    /**
     * What this sink is still short of, **species by species**; null for an appetite with no end.
     *
     * ⚠️ Per species, because a total cannot answer how much of a given *blend* closes the gap. A
     * hull short of 100g of steel is not short of 100g of anything that passes for steel: fed a
     * blend running a shade lean on carbon it takes the iron it needs and stays short of carbon for
     * ever, converging on its bill and never reaching it. Same trap as the alloy top-up, one layer
     * up. See [room].
     */
    val shortOf: Mixture?,
    /**
     * Mass still wanted before this sink is done for good, or [UNLIMITED].
     *
     * Not "room right now". See the class note.
     */
    val wanted: Long,
) {
    /** True when this sink's appetite has no end — every machine, and nothing else. */
    val isUnlimited: Boolean get() = wanted == UNLIMITED

    /** True when this sink is finite and will never take anything again. */
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

    /**
     * How much of [mixture] is worth sending — enough to close every gap, and not a gram more.
     *
     * ⚠️ **Read off the blend, not off the total.** Send a site exactly what it is short of by mass
     * and any species the blend runs lean on stays lean: the gap shrinks by a constant fraction each
     * delivery and the job never finishes. So the answer is the largest amount any one species
     * demands, and the species that are over-represented in the blend simply arrive early.
     *
     * ⚠️ **Rounded up, but only when the division is inexact.** Rounding down leaves the job a
     * fraction of a gram short and it never ends — the alloy trap again, one layer up. Adding a
     * gram unconditionally is just as bad the other way: on a blend that divides exactly, which is
     * every single-species job on the vessel, it leaves a one-gram lump standing on the belt, and a
     * one-gram lump plugs a run exactly as well as a full packet does. Measured, both of them.
     */
    fun room(mixture: Mixture): Long {
        val gaps = shortOf ?: return UNLIMITED
        val total = mixture.total
        if (total <= 0L) return 0L
        var most = 0L
        for (sp in Species.ALL) {
            val gap = gaps[sp]
            if (gap <= 0L) continue
            val share = mixture[sp]
            // Nothing of this species in the blend: no amount of it will do, and the door has
            // already refused the delivery on those grounds. Ask for the whole gap and let it.
            if (share <= 0L) return UNLIMITED
            // ceil(gap × total ÷ share), asked without ever forming the product.
            val exact = scaledRatio(gap, share, total)
            val enough = if (scaledRatio(exact, total, share) >= gap) exact else exact + 1L
            most = maxOf(most, enough)
        }
        return most
    }

    override fun toString(): String =
        if (isUnlimited) "Acceptance(anything)" else "Acceptance(${wanted}g of $bill)"

    companion object {
        /** An appetite with no end. Not a large number — a different kind of number. */
        const val UNLIMITED: Long = Long.MAX_VALUE

        /** Takes any matter, for ever: every machine on the vessel. */
        val ANYTHING: Acceptance = Acceptance(null, null, UNLIMITED)

        /**
         * Takes what [bill] can be built from, and [shortOf] more of it.
         *
         * [shortOf] is what the site is *still* short by, per species, not what it costs — a
         * half-built rail wants half a rail. **How much of it is already on its way is not asked
         * here** — that is a fact about a *route*, not about the site, and it lives in [Whitelist].
         */
        fun forBill(bill: Mixture, shortOf: Mixture): Acceptance =
            Acceptance(bill, shortOf, shortOf.total)
    }
}

/**
 * One reachable appetite, as seen **from one tile** — a demand, and what already stands between here
 * and it.
 *
 * An [Acceptance] is a fact about a sink: what it takes and how much more it wants. That is not
 * enough to decide anything from a distance, because two tiles looking at the same site are not in
 * the same position: one has half a run of iron between it and the site, the other has none. So the
 * quantity half of demand is recorded **per tile**, here, rather than on the sink.
 *
 * ⚠️ **This is why the count cannot live on the sink.** A site short by a gram with a tonne rolling
 * toward it must still *take* what arrives, so the site's own door reads [Acceptance.wanted] and
 * nothing else. Try to fold "what is already coming" into that number and the lump already on the
 * belt is forbidden to advance toward the very site it is counted against — a deadlock, and one the
 * whole suite went red proving. Recorded per tile it is a different number at every tile, and at the
 * tile a lump is moving *into* it does not count that lump.
 */
class Demand(
    /** The sink this is a route to. */
    val acceptance: Acceptance,
    /**
     * How much of what [acceptance] is short of is already standing between this tile and it —
     * **not** counting this tile.
     *
     * A source three tiles back and a source thirty tiles back are looking at different numbers, and
     * that is the point: the near one sees the loaded run in front of it and holds off, the far one
     * sees nothing and pours.
     */
    val covered: Long,
    /**
     * What a **construction site in the way** still has to be given before anything may be sent
     * along this route.
     *
     * ⛔ The ordering rule. A ghost refuses everything it cannot be built from, so an unbuilt rail is
     * a **plug** in the line for every other species: send titanium down a run with an unfinished
     * iron rail on it and the titanium comes to rest against the ghost, the iron behind it can never
     * get through, and the run is jammed for good. Nothing downstream is at fault and no amount of
     * counting *quantity* sees it — it is an ordering failure.
     *
     * So a demand propagating upstream **past** a hungry ghost picks up that ghost's shortfall as a
     * debt, and is permitted only once the debt is paid: enough material the *ghost* can use is
     * already standing between here and it. Then the plug is guaranteed to have dissolved before
     * anything sent now arrives, because nothing overtakes on a rail.
     *
     * ⚠️ The ghost's **own** demand never carries the debt, which is what stops this deadlocking:
     * iron keeps flowing to the plug and dissolves it.
     */
    val debt: Long,
    /** What the nearest blocking ghost is built from — what pays the [debt] down. Null when clear. */
    val blockerBill: Mixture?,
) {
    /** Whether [mixture] sent from this tile along this route has somewhere to be, and is wanted. */
    fun wants(mixture: Mixture): Boolean {
        if (debt > 0L) return false
        if (!acceptance.isUnlimited && acceptance.wanted <= covered) return false
        return acceptance.admits(mixture)
    }

    override fun toString(): String = "Demand($acceptance, covered=$covered, debt=$debt)"
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
 * ## Three questions, not one
 *
 * A tile permits a lump when some route out of it answers yes to all three — see [Demand]:
 *
 *  1. **Kind** — something out there can use this sort of matter at all.
 *  2. **Quantity** — it is not already covered by what is standing between here and it. This is
 *     what stops a tank emptying itself onto a run for a job that needed one packet.
 *  3. **Order** — no unfinished construction site in the way is still short of what it needs. A
 *     ghost is a plug for everything it cannot be built from, and material sent past one before it
 *     dissolves comes to rest against it and jams the run for good.
 *
 * All three are read **at the tile being entered**, never at the tile being left, which is what
 * keeps them from blocking the very traffic they are counting: a lump moving into a tile is not part
 * of what that tile can see ahead of it.
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
    /** Per tile: the routes out of it that are worth anything. Null where there are none. */
    private val routes: Array<MutableList<Demand>?>,
    /**
     * Per tile: whether something reachable from it will take anything, for ever, with nothing in
     * the way — the fast path, and the answer on any vessel with no construction going on.
     */
    private val unlimited: BooleanArray,
) {
    /** True when anything at all may leave [tile] — the common case, and free to ask. */
    fun permitsAnything(tile: TileIndex): Boolean =
        tile.index in unlimited.indices && unlimited[tile.index]

    /** True when nothing downstream of [tile] wants anything: a dead end. */
    fun permitsNothing(tile: TileIndex): Boolean =
        !permitsAnything(tile) && routes.getOrNull(tile.index).isNullOrEmpty()

    /**
     * Whether [mixture] standing on [tile] has somewhere to go that can use it, wants more of it,
     * and can be reached without jamming against a construction site on the way.
     *
     * ⚠️ Asked of every candidate direction of every loaded tile on every step, so the unlimited
     * case is answered before the mixture is so much as looked at.
     */
    fun permits(tile: TileIndex, mixture: Mixture): Boolean {
        if (permitsAnything(tile)) return true
        val here = routes.getOrNull(tile.index) ?: return false
        for (d in here) if (d.wants(mixture)) return true
        return false
    }

    /**
     * The largest amount of [mixture] worth letting go of at [tile], or [Acceptance.UNLIMITED].
     *
     * ⚠️ **Because a packet is a lump and a bill is not a round number.** A source that may emit at
     * all used to emit a *whole* packet, so a run built to fill two rails of 130g apiece out of
     * 100g packets put 300g on the belt and left 39g of iron standing at the far end with nothing
     * that wants it. Harmless where an unlimited sink waits beyond — and a deadlock where one is
     * still being built, because the residue sits in front of the material that would build it.
     *
     * So the answer is a quantity, and the source takes the smaller of it and what fits.
     */
    fun room(tile: TileIndex, mixture: Mixture): Long {
        if (permitsAnything(tile)) return Acceptance.UNLIMITED
        val here = routes.getOrNull(tile.index) ?: return 0L
        var most = 0L
        for (d in here) {
            if (!d.wants(mixture)) continue
            if (d.acceptance.isUnlimited) return Acceptance.UNLIMITED
            most = maxOf(most, d.acceptance.room(mixture) - d.covered)
        }
        return most
    }

    companion object {
        /** Permits nothing anywhere: a world whose flow has not been worked out yet. */
        fun empty(): Whitelist = Whitelist(arrayOfNulls(0), BooleanArray(0))

        /**
         * Walk the flow downstream-first, carrying each tile's appetite back to whoever feeds it.
         *
         * [acceptanceAt] states what a tile consumes on its own account; a tile that is a sink and
         * says nothing is a machine, and a machine takes anything for ever.
         *
         * [loadOn] reports how much of what stands on a tile could serve a given bill — all of its
         * mass when the bill is null. It is asked once per tile per route, so a caller that has to
         * look a lump up off a layer should look it up once and answer from that.
         */
        fun of(
            flow: FlowGraph,
            tileCount: Int,
            acceptanceAt: (TileIndex) -> List<Acceptance>?,
            loadOn: (TileIndex, Mixture?) -> Long,
        ): Whitelist {
            val routes = arrayOfNulls<MutableList<Demand>>(tileCount)
            val unlimited = BooleanArray(tileCount)

            for (tile in flow.order) {
                val i = tile.index
                var any = false
                var here: MutableList<Demand>? = null

                // What stands on this tile is between whoever feeds it and everything beyond, so
                // every count a feeder inherits from here includes it. Asked lazily: on a quiet run
                // most tiles are empty and most tiles have no route worth the question.
                val own = acceptanceAt(tile)
                // A construction site standing here is a plug in the line for everything it cannot
                // be built from. The **hungriest** one speaks for the tile: where a ghost machine
                // stands on ghost track there are two, and the debt has to cover the larger.
                var plug: Acceptance? = null
                if (own == null && tile in flow.sinks) any = true
                if (own != null) {
                    for (a in own) {
                        if (a.isUnlimited) { any = true; continue }
                        // ⚠️ **Not `filter`** — the ones worth carrying upstream are the ones still
                        // WANTING something. A satisfied acceptance answers `false` to everything,
                        // so keeping those instead silently stops finite demand propagating at all
                        // and no source ever feeds a construction site again. Eighteen tests say so.
                        if (a.isSatisfied) continue
                        // Nothing stands between a site and itself, and a site never plugs its own
                        // supply — both numbers are zero on its own tile, and that is what keeps
                        // material flowing to the plug that dissolves it.
                        here = (here ?: mutableListOf()).also {
                            it.add(Demand(a, loadOn(tile, a.bill), 0L, null))
                        }
                        if (plug == null || a.wanted > plug.wanted) plug = a
                    }
                }

                for (next in flow.successorTiles(tile)) {
                    val j = next.index
                    // What stands on the successor is between this tile and everything beyond it.
                    if (unlimited[j]) {
                        if (plug == null) any = true
                        else {
                            val list = here ?: mutableListOf<Demand>().also { here = it }
                            if (list.none { it.acceptance === Acceptance.ANYTHING }) {
                                list.add(
                                    Demand(
                                        Acceptance.ANYTHING, 0L,
                                        plug.wanted - loadOn(tile, plug.bill), plug.bill,
                                    ),
                                )
                            }
                        }
                    }
                    val theirs = routes[j] ?: continue
                    for (d in theirs) {
                        val covered =
                            if (d.acceptance.isUnlimited) 0L else d.covered + loadOn(tile, d.acceptance.bill)
                        // A fresh plug at this tile adds its shortfall on top of whatever is owed
                        // further down, and only then is what stands here counted against the total.
                        //
                        // ⚠️ **In that order, and the tile's load taken off exactly once.** Where a
                        // plug stands on a tile that already owed something — two ghost rails in a
                        // row, which is what a freshly drawn run *is* — subtracting the load once
                        // per debt credits the same packet to both of them. Two rails needing a
                        // packet each read as paid for by one, the titanium set off, and the run
                        // jammed with a rail at 530 permille and the iron for it stranded behind.
                        var debt = d.debt
                        var bill = d.blockerBill
                        if (plug != null) {
                            debt = maxOf(debt, 0L) + plug.wanted
                            bill = plug.bill
                        }
                        if (bill != null) debt -= loadOn(tile, bill)
                        val route = Demand(d.acceptance, covered, debt, bill)
                        val list = here ?: mutableListOf<Demand>().also { here = it }
                        // One entry per sink, and the **most permissive** one wins: where two
                        // routes lead to the same place, "there is a way this is still wanted" is
                        // the answer, and taking it costs nothing — turning down the branch that is
                        // covered or plugged is refused at that branch's own tile a step later.
                        // Without this a network of diamonds grows a list per route rather than per
                        // sink, and the walk stops being linear.
                        val at = list.indexOfFirst { it.acceptance === route.acceptance }
                        when {
                            at < 0 -> list.add(route)
                            route.debt < list[at].debt ||
                                (route.debt == list[at].debt && route.covered < list[at].covered) ->
                                list[at] = route
                        }
                    }
                }

                unlimited[i] = any
                // Nothing downstream is fussy *and* nothing downstream is boundless: the list is the
                // only thing left worth keeping, and only while the unlimited flag is not set.
                routes[i] = if (any) null else here
            }
            return Whitelist(routes, unlimited)
        }
    }
}
