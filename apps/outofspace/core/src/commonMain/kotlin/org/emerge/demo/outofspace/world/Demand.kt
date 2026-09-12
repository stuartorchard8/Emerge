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
 *  - [ANYTHING] — takes any matter, for ever. Every *working* machine is this. A concentrator's
 *    buffer backs up, but that is *momentary*: drain it and it takes more. Over its life a machine
 *    will accept an unbounded amount, which is what makes it useless as a thing to ration a network
 *    by.
 *  - [forBill] — takes only what it can be built from, and only until it is built. A construction
 *    site is the one sink in the game with a **final** total. That is what makes it the sink worth
 *    metering, and it is why this type exists at all.
 *  - [filtered] — takes one species at a stated purity. A locked warehouse.
 *  - [upTo] — takes any matter, and only so much of it. An unlocked store.
 *
 * ⛔ **Fussy and endless are two independent questions, and [filtered] is what proves it.** Until it
 * existed the two always agreed — everything unlimited took anything, everything finite was picky —
 * so [isUnlimited] was quietly doing duty as "unfussy" in the walk below, and a locked warehouse
 * read as taking everything. Ask [takesAnything] for the fussiness question and [isUnlimited] for
 * the quantity one; they are never the same question again.
 *
 * ⚠️ **A working machine's momentary fullness is still not modelled here**, and that half of the old
 * rule stands: "the furnace's hopper is full right now" is a question for the delivery path, which
 * already answers it and already backs the belt up correctly, and folding it in would make every
 * network look nearly satisfied a tick at a time.
 *
 * ⛔ **A store is the exception, because for a store it is not momentary.** A warehouse is where
 * material *stops*; nothing downstream drains it but the player, so "full" is a state it sits in
 * rather than passes through. Left endless it told every source on the network to pour for ever,
 * and the corridors leading to it filled solid with material it would refuse at the door — which is
 * precisely the failure this whole file exists to prevent, and it was exempting the one sink most
 * likely to hit it. So a store states [upTo] its remaining room and is rationed like anything else.
 * Stu, 2026-09-10.
 *
 * ⚠️ **[wanted] is therefore no longer "before it is done for good" for every sink** — a store's is
 * "before it is full", and it goes back up when the store drains. Nothing downstream cared: the
 * whitelist is rebuilt from the world every rail step, so every number in it was already only true
 * for the step that read it. What it does mean is that [Whitelist.promised], which is keyed by
 * identity and dies with the whitelist, must never be handed a store acceptance that outlives a
 * step. See [upTo].
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
     * One species at a stated purity, or null. A locked warehouse and nothing else.
     *
     * Held apart from [bill] rather than folded into it because a threshold is not a recipe — see
     * [SpeciesFilter], which explains why reusing the bill machinery would misreport the player's
     * own number back at them.
     */
    val filter: SpeciesFilter?,
    /**
     * Every species this sink can **use**, by ordinal, or null for no such statement.
     *
     * ⛔ **Set membership, which is neither a recipe nor a threshold.** [bill] asks whether a lump
     * matches proportions — a construction site's question — and [filter] asks for one species at a
     * purity, which is a locked warehouse's. A cell asks a third thing: *is everything in here
     * something I can work with*, in any proportions. Water and the salt dissolved in it, and no
     * rock. Neither of the other two can say that: a bill would demand a fixed brine strength, and
     * `SpeciesFilter(Water, pure = false)` would admit water with gravel in it.
     *
     * ⚠️ **A `BooleanArray` because [admits] is on the hot path and must not allocate** — the class
     * note says so. Built once, at construction.
     */
    val only: BooleanArray?,
    /**
     * Whether this sink stands **in the road** — refusing passage to what it cannot use, not merely
     * declining to take it.
     *
     * ⛔ **True for unpaid track and nothing else.** A ghost *rail* must refuse what it cannot be
     * built from, or a player routes their whole network over a free length of track they have not
     * paid for; that is the anti-exploit the ghost design rests on. A ghost *machine* has no such
     * claim — the track under it is finished and paid for, the machine is inert and permeable, and a
     * lump crossing it takes nothing that is not already there. The same rule was being applied to
     * two different things, and a storage 90% built stopped a run of iron dead. Found in Stu's save.
     *
     * A site that does not stop traffic still pulls what it can use and still refuses what it cannot
     * at its own door. It simply is not a wall.
     */
    val stopsTraffic: Boolean,
    /**
     * Mass still wanted before this sink stops wanting, or [UNLIMITED].
     *
     * ⚠️ **What "stops wanting" means is the sink's business, not this field's.** A construction
     * site is done for good; a store is merely full and will want more when it drains. Both are one
     * number here because the whitelist that reads it lives for a single rail step and is rebuilt
     * from the world — see the class note.
     */
    val wanted: Long,
    /**
     * Whether [wanted] is how much this sink **ordered**, rather than how much it is *permitted* —
     * and so whether its own door still takes a lump that arrived beyond it.
     *
     * ⛔ **The two readings of [wanted] were already written down, one paragraph apart, and nothing
     * acted on the difference.** *"What 'stops wanting' means is the sink's business, not this
     * field's. A construction site is done for good; a store is merely full."* A sell permission and
     * a build bill are **allowances**: matter past them is matter the player did not agree to, so the
     * door must refuse it, and it passes on to whatever lies beyond. A hopper's target is an
     * **order**: the tank behind it is bigger than the number, so a lump that turns up anyway fits,
     * and refusing it does not save it.
     *
     * ⭐ **Which is what `sinkAdmits` always said it did and did not.** *"Kind, never quantity … a
     * lump standing at a mouth is already committed: refusing it for being surplus does not save it,
     * it only strands it one tile earlier."* True of the road into the mouth and false of the mouth,
     * because [admits] refuses a satisfied sink outright — so an ordered-to-target hopper stranded
     * its own surplus on the tile outside.
     *
     * ⛔ **And it is not a rule to apply everywhere**, which is why this is a flag and not a change
     * to [admits]. A docking port whose permission is spent must go on letting cargo cross its mouth
     * toward a tank beyond it; made to swallow the surplus it would sell what nobody allowed — the
     * `dock.txt` failure, arrived at from the far side. Default false, so a sink has to say.
     *
     * ⚠️ **A store sets it and nothing changes**, because a store's appetite is its room: satisfied
     * and physically full are the same state for it, and `acceptInto` refuses at the brim anyway.
     * The one sink where the two come apart is a locked kiln's reagent hopper, which orders to the
     * recipe's ratio and keeps a tank four times that.
     */
    val doorTakesSurplus: Boolean,
) {
    /** True when this sink's appetite has no end — every working machine. */
    val isUnlimited: Boolean get() = wanted == UNLIMITED

    /**
     * True when this sink refuses nothing — the fast path the whole network rests on.
     *
     * ⚠️ **Not the same as [isUnlimited]**, and the distinction is load-bearing: a locked warehouse
     * is endless *and* picky, so a tile that can reach one may not be marked as taking anything.
     *
     * ⛔ **[only] has to be named here, and for a while it was not.** It was added for the
     * electrolytic cell after this predicate was written, and a predicate that lists the ways a sink
     * can be fussy is exactly the kind that rots when a fourth way is added — every `onlyOf` sink
     * read as unfussy, so [Whitelist.of] marked its tile "welcome anywhere" and the network routed a
     * belt of gravel at a cell that would refuse every gram of it at the door. That is the belt
     * filling solid against a mouth that will never take what is on it, which is the failure the
     * whole demand design exists to prevent — and it was live for the electrolyzer and the cell
     * until an ejector, whose *empty* list refuses everything, made it impossible to miss.
     */
    val takesAnything: Boolean get() = bill == null && filter == null && only == null

    /**
     * True when this sink is finite and wants nothing more — a built site, or a full store.
     *
     * ⚠️ **"For now" for a store**, which reads as the same thing everywhere this is asked because
     * everywhere this is asked is inside one rail step.
     */
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
        val allowed = only
        if (allowed != null) {
            // Nothing is not a delivery — [buildableFrom]'s first line, for its reason.
            if (mixture.total <= 0L) return false
            for (s in Species.ALL) if (mixture[s] > 0L && !allowed[s.ordinal]) return false
        }
        // ⛔ **[only] and [filter] COMPOSE, and [only] used to return here instead.** A sink may be
        // fussy in more than one way at once, and the store shortlist is what proved it: "one of
        // these five species, and only if it is pure" is two statements about one lump, and there is
        // nowhere else to put the second. Stated as two acceptances it would say the opposite of
        // what it means — a tile's acceptances are OR'd at the door, so "one of these five" beside
        // "anything pure" admits pure gravel. See [shortlisted].
        //
        // ⚠️ The electrolytic cell, which [onlyOf] was written for, states no [filter] at all and so
        // cannot tell the difference. Nothing about it changed.
        filter?.let { return it.admits(mixture) }
        if (allowed != null) return true
        val want = bill ?: return true
        return buildableFrom(want, mixture)
    }

    /**
     * Whether this sink's **own door** takes [mixture] — [admits], less the quantity question where
     * the sink has said its number is an order rather than an allowance.
     *
     * ⛔ **Asked by `sinkAdmits` and by `eatenBy`, and by exactly those two.** They are the pair that
     * answer "what does this door actually do with a lump standing on it", and `doorAcceptances` is
     * explicit that they must not form two opinions — *"a lump is eaten by whichever door admits it
     * first, and a second opinion about what a door admits would be a second answer to 'where did
     * that packet go'"*. Everything else asking [admits] is asking a *demand* question — what to
     * send, what to apportion, what a ghost will let past — where a satisfied sink really is shut.
     */
    fun admitsAtDoor(mixture: Mixture): Boolean {
        if (!doorTakesSurplus || !isSatisfied) return admits(mixture)
        // Satisfied, but only against a number it ordered to. The kind question stands on its own:
        // a filter, a species mask and a bill all answer it without reference to how much is left.
        val allowed = only
        if (allowed != null) {
            if (mixture.total <= 0L) return false
            for (s in Species.ALL) if (mixture[s] > 0L && !allowed[s.ordinal]) return false
        }
        filter?.let { return it.admits(mixture) }
        if (allowed != null) return true
        val want = bill ?: return true
        return buildableFrom(want, mixture)
    }

    override fun toString(): String =
        when {
            // ⚠️ The quantity is named whenever there is one, filter or no filter. A locked store
            // is metered now, and a reading that showed only its lock looked identical whether it
            // had twenty tonnes of room or none.
            filter != null -> {
                val kind = "${when (filter.pure) { true -> "pure "; false -> "mixed "; null -> "" }}${filter.species?.name ?: "anything"}"
                if (isUnlimited) "Acceptance($kind)" else "Acceptance(${wanted}g of $kind)"
            }
            isUnlimited -> "Acceptance(anything)"
            bill == null && only == null -> "Acceptance(${wanted}g of anything)"
            else -> "Acceptance(${wanted}g of $bill)"
        }

    companion object {
        /** An appetite with no end. Not a large number — a different kind of number. */
        const val UNLIMITED: Long = Long.MAX_VALUE

        /** Takes any matter, for ever: every working machine on the vessel. */
        val ANYTHING: Acceptance =
            Acceptance(null, null, null, stopsTraffic = false, wanted = UNLIMITED, doorTakesSurplus = false)

        /**
         * Takes any matter, but only [wanted] more grams of it: **a store with room left in it.**
         *
         * ⛔ **Unfussy and finite, which is the pairing that did not exist before.** [ANYTHING] is
         * unfussy and endless, [forBill] is fussy and finite, [filtered] is fussy and endless — the
         * fourth corner is an unlocked storage, which will take absolutely anything and then stop.
         * [Whitelist.of] already reads the two questions separately (`takesAnything && isUnlimited`
         * is one branch, not two names for one thing), so this needed a factory and nothing else.
         *
         * ⚠️ **A fresh instance per store per rail step, and it has to be.** [Whitelist.promised] is
         * keyed by identity and a store's room is a different number every step, so sharing one of
         * these between two silos would have them promise against each other's tank.
         */
        fun upTo(wanted: Long): Acceptance =
            Acceptance(null, null, null, stopsTraffic = false, wanted = wanted, doorTakesSurplus = false)

        /**
         * Takes lumps made **entirely of** [species], in any proportions — and refuses a lump with
         * one gram of anything else in it.
         *
         * ⭐ **The electrolytic cell's shape, and the reason this exists.** A cell runs on water *and
         * whatever is dissolved in it*: brine conducts, pure water does not, and both are things it
         * should be handed. Neither of the other two factories can say that — see [only].
         */
        fun onlyOf(species: Set<Species>, wanted: Long = UNLIMITED): Acceptance {
            val mask = BooleanArray(Species.COUNT)
            for (s in species) mask[s.ordinal] = true
            return Acceptance(null, null, mask, stopsTraffic = false, wanted = wanted, doorTakesSurplus = false)
        }

        /**
         * Takes [filter]'s species in [SpeciesFilter.pure] condition, and [wanted] more grams of it:
         * a locked store.
         *
         * ⚠️ **Both dials, and they are independent.** The lock says *what* and the tank says *how
         * much*; a warehouse locked to iron with two tonnes of room wants two tonnes of iron. Stating
         * that as two acceptances would not say it — a tile's acceptances are OR'd at the door, so
         * "iron, for ever" beside "anything, up to the brim" admits gravel.
         *
         * ⛔ **Never a plug.** A warehouse is a building on finished, paid-for track; refusing what
         * it cannot use at its own door is all it is entitled to do. Made to stand in the road it
         * would be a wall the player can build across their own network with no ghost in sight —
         * the exact exploit [stopsTraffic] exists to prevent, inverted.
         */
        fun filtered(
            filter: SpeciesFilter,
            wanted: Long = UNLIMITED,
            /** See [Acceptance.doorTakesSurplus] — a hopper ordering to a ratio says true. */
            doorTakesSurplus: Boolean = false,
        ): Acceptance = Acceptance(null, filter, null, stopsTraffic = false, wanted = wanted, doorTakesSurplus)

        /**
         * Takes any **one** of [species], at [pure], and [wanted] more grams of it: a store that has
         * yet to decide which of a shortlist it will hold.
         *
         * ⛔ **One acceptance and not one per species, which is the whole reason this exists.** The
         * obvious spelling is a [filtered] apiece and it is wrong twice over. A tile's acceptances
         * are OR'd, so five of them are five *independent* appetites: [Whitelist.promised] is keyed
         * per acceptance, so five different species could each have a packet let go for them in one
         * step, and four of the five would be stranded the moment the tank made up its mind — which
         * is exactly the failure the one-packet cap exists to prevent, walked back in through the
         * door the fix left open. As one acceptance the promise is spent once.
         *
         * ⚠️ **Both halves are asked**, which is why [admits] no longer returns early on [only]. The
         * membership test says *which* species and the [SpeciesFilter] says *how pure*; a shortlist
         * of five under a `pure = true` lock means five species and no blends, including no blend of
         * two shortlisted species — which a bare [onlyOf] would happily admit and a store would then
         * mix into something it can never ship again.
         */
        fun shortlisted(species: Set<Species>, pure: Boolean?, wanted: Long = UNLIMITED): Acceptance {
            val mask = BooleanArray(Species.COUNT)
            for (s in species) mask[s.ordinal] = true
            return Acceptance(
                null, SpeciesFilter(null, pure), mask, stopsTraffic = false, wanted = wanted,
                doorTakesSurplus = false,
            )
        }

        /**
         * Takes what [bill] can be built from, and [shortBy] more grams of it.
         *
         * ⛔ **[shortBy] is a mass, not a per-species shortfall**, because that is what a site is
         * actually short of — see [holdsFullBill]. A half-built rail wants half a rail's worth of
         * matter, and what that matter is made of was settled at the door. **How much of it is
         * already on its way is not asked here** — that is a fact about a *route*, not about the
         * site, and it lives in [Whitelist].
         */
        fun forBill(bill: Mixture, shortBy: Long, stopsTraffic: Boolean = true): Acceptance =
            Acceptance(bill, null, null, stopsTraffic, shortBy, doorTakesSurplus = false)
    }
}

/**
 * A construction site standing **in the way**, and what it is still owed.
 *
 * ⛔ A ghost refuses everything it cannot be built from, so an unbuilt iron rail is a **plug** in the
 * line for titanium: send the titanium and it comes to rest against the ghost, the iron behind it
 * can never get through, the rails never finish, the plug never dissolves. Nothing downstream is at
 * fault and no amount of counting *quantity* sees it — it is an ordering failure.
 *
 * So a route running past a hungry site carries what that site is owed, and is refused until the
 * debt is paid: enough material the *site* can use already standing between here and it. Then the
 * plug is guaranteed to have dissolved before anything sent now arrives, because nothing overtakes
 * on a rail.
 *
 * ⛔ **A site is not a plug for the thing it is made of**, and this is asked of [bill] at the door
 * rather than folded into a number, because it is a question about the *material* being sent. A
 * ghost fed what it is short of takes what it needs and lets the remainder ride on — that is the
 * whole design. Charging the debt against iron as well as titanium made a column of nine ghosts
 * drain the column beside it one rail at a time, since only the nearest one's demand was ever
 * visible. Stu's case, and it is the difference between rebuilding a network being playable and not.
 *
 * One entry per distinct bill: what pays a rail's debt down is not what pays a firebrick furnace's.
 */
class Block(
    /** What the site in the way is built from — and so what it will let past. */
    val bill: Mixture,
    /** Mass it is still short by, less whatever it can use standing between here and it. */
    val owed: Long,
)

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
     * This route's **share** of what is already standing between this tile and its sink.
     *
     * A share and not a tally, because a lump in a corridor that feeds several sinks will be eaten
     * exactly once and has to be counted exactly once. Charging its whole mass to every sink it
     * could reach shut the source off with the job barely started — nine ghosts wanting a rail
     * apiece read as fed by one packet. Charging it to none of them, which is what taking the
     * largest tally instead of the sum amounted to, went wrong the other way: material sitting on a
     * branch only one sink can draw from was credited against a sink that could never receive it,
     * and the one that could was fed in dribs.
     *
     * So each lump is divided among the sinks it can serve, in proportion to what each of them
     * still wants, and every gram on the network is counted once and against the sinks that will
     * actually get it. See [Whitelist.of].
     *
     * A source three tiles back and a source thirty tiles back are looking at different numbers, and
     * that is the point: the near one sees the loaded run in front of it and holds off, the far one
     * sees nothing and pours.
     */
    val covered: Long,
    /** Construction sites in the way, by what they are built from. Null when the road is clear. */
    val blocks: List<Block>?,
) {
    /**
     * Whether this route is usable for [mixture] at all — the *kind* and *order* questions.
     *
     * ⚠️ Deliberately **not** the quantity question. How much more is worth sending is a sum over
     * every sink on the route and cannot be answered one route at a time — see [Whitelist.permits].
     */
    fun wants(mixture: Mixture): Boolean {
        val inTheWay = blocks
        if (inTheWay != null) {
            for (b in inTheWay) if (b.owed > 0L && !buildableFrom(b.bill, mixture)) return false
        }
        return acceptance.admits(mixture)
    }

    override fun toString(): String = "Demand($acceptance, covered=$covered, blocks=${blocks?.size ?: 0})"
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
 * A tile permits a lump when the routes out of it answer all three:
 *
 *  1. **Kind** — something out there can use this sort of matter at all.
 *  2. **Order** — no construction site in the way still refuses it. See [Block].
 *  3. **Quantity** — something reachable still wants more than is already on its way to it.
 *
 * ⚠️ **Quantity is a sum of *remainders*, one per sink.** Nine ghosts wanting a rail apiece want
 * nine rails, so the wants add up; but the packet standing in the corridor they share can only ever
 * be eaten once, so it must not be charged to all nine. Both halves are load-bearing and neither
 * survives on its own — see [Demand.covered], which is where the sharing is worked out, and
 * [room], which adds up what is left.
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
/** One walk's answers, per tile — see [Whitelist.of], which runs more than one on a looped network. */
internal class Reach(
    val routes: Array<MutableList<Demand>?>,
    val unlimited: BooleanArray,
)

class Whitelist private constructor(
    /** Per tile: the routes out of it that are worth anything. Null where there are none. */
    private val routes: Array<MutableList<Demand>?>,
    /**
     * Per tile: whether something reachable from it will take anything, for ever, with nothing in
     * the way — the fast path, and the answer on any vessel with no construction going on.
     */
    private val unlimited: BooleanArray,
    /** Every span, near end to far end — [permitsPast] needs the pairing. */
    private val hops: Map<TileIndex, TileIndex>,
    /**
     * For a span that can reach round to its own mouth, the network **with that mouth deleted**.
     *
     * Empty on anything without a ring on it. See [Whitelist.of] and [permitsPast].
     */
    private val past: Map<TileIndex, Reach>,
    /**
     * Per sink: **everything standing anywhere on the network** that is apportioned to it.
     *
     * ⛔ **[Demand.covered] is the same material counted per ROUTE, and that is what could not see
     * a sibling.** A route's figure is the load between one tile and the sink, which is exactly the
     * right number for a lump deciding whether to advance — it excludes the lump itself — and the
     * wrong one for a source deciding whether to let go. Two tanks on branches of their own that
     * meet near a sink are each looking down a corridor the other's packet is not in, so each reads
     * a clear road and commits the whole appetite. [promised] closed that for one step and died with
     * the whitelist; the hole reopened on the next, and every step after. Measured: a sink with two
     * packets of room took two branches' worth from two feeders and three branches' worth from
     * three, and the surplus stood in the corridor for good — a full store is a dead end, and the
     * network has no reverse gear. `DemandBranchTest`, from Stu's `over_fill.txt`.
     *
     * ⚠️ **Summed off exactly the apportionment [Demand.covered] already uses**, in the same walk —
     * [chargeStandingLoad] works out each sink's share of each lump once and now writes it to both
     * places. So the two numbers cannot form different opinions about who a lump belongs to; they
     * differ only in *where* it is counted from.
     *
     * ⛔ **Never read by anything a moving lump asks.** See [roomToCross] and [permits], which stay
     * on the route figure for the reason [Demand] gives: count a lump globally and the very lump a
     * source has just put down is forbidden to advance toward the sink it was let go for.
     */
    private val inFlight: Map<Acceptance, Long>,
) {
    /**
     * Whether a span at [mouth] could usefully carry [mixture] across — **the road past it**, which
     * is the only question a span's door has.
     *
     * ⛔ **And "past" has to mean past.** Read off the far end's own routes it includes the road
     * *behind* the mouth: on a loop every consumer the mouth feeds is reachable from the far end by
     * going round and back in through the mouth, so the span reads them as somewhere it can deliver
     * and swallows material bound for them — on every lap, for ever. Stu's `cycle` save. Where the
     * far end can reach round like that, this reads a walk made with the mouth deleted instead; see
     * [Whitelist.of].
     *
     * ⚠️ **Kind, never quantity.** A lump standing at a mouth is already committed, and refusing it
     * for being surplus strands it a tile earlier rather than saving it — the same `rationed = false`
     * the door asks everywhere else.
     */
    fun permitsPast(mouth: TileIndex, mixture: Mixture): Boolean {
        val far = hops[mouth] ?: return true
        val reach = past[mouth] ?: return permits(far, mixture, rationed = false)
        if (reach.unlimited.getOrElse(far.index) { false }) return true
        val here = reach.routes.getOrNull(far.index) ?: return false
        for (d in here) if (d.wants(mixture)) return true
        return false
    }
    /**
     * What sources have already let go of **during the life of this whitelist**, per sink.
     *
     * ⛔ **The hole [Demand.covered] cannot see, because it is not there yet.** `covered` is what
     * stands on the network, read once when the walk was made; every source consulting [room]
     * afterwards is therefore looking at the same picture, and none of them can see what the ones
     * before it have just put down. One sink short of a single wire, seven wires marked for
     * deconstruction on the corridor that reaches it, and all seven read "14.9kg wanted" in the same
     * pass and shed 14.9kg apiece. Stu's save, 2026-09-03: column `(11,9..15)` emptying itself into
     * one wire site at `(12,8)`.
     *
     * So a source that lets go **says so**, and the next one to ask is looking at a smaller number.
     * The promise dies with the whitelist — by the next rail step the material is standing on the
     * track and `covered` counts it, which is the same fact arriving by its usual road, so nothing
     * is charged twice.
     *
     * ⚠️ **Which source wins is the order the pass walks in**, and that is the point rather than a
     * flaw: the alternative to somebody going first is everybody going at once, which is the bug.
     * Ascending tile order means the near end of a marked run comes apart before the far end, which
     * is also the order a player would expect to watch it go.
     *
     * ⛔ **And for one pass that sentence was simply untrue.** `scrapDeconstructing` walks tiles, so
     * it held there; `pushOut` walked a `HashMap` keyed by [TileIndex], whose hash is the index, so
     * it iterated in bucket order and a port at 518 went before one at 500. See
     * `OutofspaceSim.portsByTile`, which now sorts — a claim in a comment is not a guarantee, and
     * this one was load-bearing for every race between two sources.
     *
     * ⚠️ **A promise can be wrong, and it costs a step.** Nothing reserves a route, so a lump
     * promised to one site may be eaten by another on the way; the sink that was counted on then
     * reads short next step and the source that held back pours after all. Self-correcting, because
     * the whole picture is rebuilt from the world every rail step.
     */
    private val promised = HashMap<Acceptance, Long>()

    /**
     * Every sink at a tile, which is what [room] weighs unless a caller says otherwise.
     *
     * ⛔ **Compared by identity in [room]**, so that the fast path can be taken for the callers that
     * did not pass anything and skipped for the one that did. A fresh lambda per call would defeat
     * that silently — hence a named constant rather than a default expression.
     */
    private val ALL_SINKS: (Acceptance) -> Boolean = { true }

    /** True when anything at all may leave [tile] — the common case, and free to ask. */
    fun permitsAnything(tile: TileIndex): Boolean =
        tile.index in unlimited.indices && unlimited[tile.index]

    /**
     * Whether [mixture] leaving [tile] could have been let go of for **this particular** [sink].
     *
     * ⛔ **The one question a source cannot answer for itself.** [room] and [permits] both fold every
     * sink at a tile into a single number, which is right for deciding *how much* to send and
     * useless for the caller who needs to know *who* it went to. A store that has yet to decide what
     * it holds has to be told the moment something is committed to it — see
     * `Storage.speciesUndecided` — and "committed" means exactly this: there was a route from here
     * to that sink, and it wanted what went past.
     *
     * ⚠️ **Identity, not equality.** Two sinks stating the same appetite are two sinks, and
     * [Acceptance] is compared by reference everywhere in this file for that reason — [promised] is
     * keyed by it. Asking for one and being told about its twin would lock the wrong tank.
     *
     * ⚠️ **[permitsAnything] is deliberately not consulted.** That fast path answers "will *someone*
     * take this", and someone is not this sink. A corridor that also reaches a furnace answers yes
     * to it while the tank at the far end has already been satisfied.
     */
    fun leadsTo(tile: TileIndex, sink: Acceptance, mixture: Mixture): Boolean {
        val here = routes.getOrNull(tile.index) ?: return false
        for (d in here) if (d.acceptance === sink && d.wants(mixture)) return true
        return false
    }

    /** True when nothing downstream of [tile] wants anything: a dead end. */
    fun permitsNothing(tile: TileIndex): Boolean =
        !permitsAnything(tile) && routes.getOrNull(tile.index).isNullOrEmpty()

    /**
     * Whether [mixture] standing on [tile] has somewhere to go that can use it, wants more of it,
     * and can be reached without coming to rest against a construction site on the way.
     *
     * ⚠️ Asked of every candidate direction of every loaded tile on every step, so the unlimited
     * case is answered before the mixture is so much as looked at. What is left costs a walk of the
     * bill per route, and only on a vessel with construction going on: a network with a tank or a
     * vent reachable is answered by [permitsAnything] before any of this is read.
     *
     * ⛔ **Every quantity here is a mass of matter, and there is nothing to convert.** A site's
     * appetite, what stands on the track, and what a source is about to let go of are the same kind
     * of number, because composition is settled at the door and never enters the arithmetic — see
     * [holdsFullBill]. When the shortfall was measured in bill species and the traffic in matter,
     * the two disagreed by exactly the junk the door let through, and a route read as satisfied
     * while it was still owed.
     *
     * ⛔ **[rationed] is false for a lump with nowhere else to go, and that is not an optimisation.**
     * The quantity question means "is it worth *committing* more of this", and a lump already on the
     * belt in a corridor with one way out is committed: refusing it does not save the material, it
     * only stops it arriving. When what is in flight exactly covers what is left to build — the
     * ordinary end of any transfer, since the sums match — every lump but the leading one is held,
     * the leading one alone advances and is eaten, and the run delivers single file. That is the few
     * ticks of dead air at the end of a column transfer.
     *
     * Rationing belongs where a lump has a **choice**: at a fork it is the difference between the
     * branch that still needs feeding and the one already covered, which is the whole reason the
     * count moved onto the tile. Everywhere else the answer is simply yes.
     */
    fun permits(tile: TileIndex, mixture: Mixture, rationed: Boolean = true): Boolean {
        if (permitsAnything(tile)) return true
        val here = routes.getOrNull(tile.index) ?: return false
        var found = false
        var owed = 0L
        for (d in here) {
            if (!d.wants(mixture)) continue
            if (d.acceptance.isUnlimited) return true
            found = true
            if (!rationed) return true
            // ⛔ **[promised] is deliberately not read here, and [room] is where it is.** This is the
            // question a *moving* lump asks, and material already on the track is already committed:
            // subtract what has been promised and the very lump a source has just put down is
            // forbidden to advance toward the site it was let go for — the deadlock [Demand] warns
            // about, reached from the other end. Promises ration what is *let go of*, never what is
            // already in the corridor.
            //
            // ⚠️ **Per sink, clamped at nought, and then added up.** A sink with more on its way
            // than it can use is done — it does not lend its surplus to the sink beside it, which
            // is what a single subtraction across the whole tile would have it do.
            val remaining = d.acceptance.wanted - d.covered
            if (remaining > 0L) owed = saturated(owed, remaining)
        }
        return found && owed > 0L
    }

    /**
     * The largest amount of [mixture] worth letting go of at [tile], or [Acceptance.UNLIMITED].
     *
     * ⚠️ **Because a packet is a lump and a bill is not a round number.** A source that may emit at
     * all used to emit a *whole* packet, so a run built to fill two rails of 130g apiece out of
     * 100g packets put 300g on the belt and left 39g standing at the far end with nothing that
     * wants it. Harmless where an unlimited sink waits beyond — and a deadlock where one is still
     * being built, because the residue sits in front of the material that would build it.
     *
     * So the answer is a quantity, and the source takes the smaller of it and what fits.
     */
    fun room(tile: TileIndex, mixture: Mixture, serves: (Acceptance) -> Boolean = ALL_SINKS): Long =
        roomBy(tile, mixture, serves, ::remaining)

    /**
     * The largest slice of [mixture] **already on the track** worth letting cross into [tile].
     *
     * ⛔ **Not [room], and the difference is which lumps are counted.** That one answers a source
     * about to create traffic and weighs everything already spoken for; this one answers traffic
     * that exists, and must weigh only what lies between [tile] and the sink — see [routeRemaining],
     * where the deadlock is written down. The two were one method until sibling branches needed a
     * global tally, and a moving lump reading that tally is counted against itself.
     *
     * ⚠️ **This is [permits]' rationed half as a quantity rather than a verdict**, which is what its
     * caller has always needed them to be: the fork that sends what a route can use and turns the
     * rest round cannot form two opinions about how much that is.
     */
    fun roomToCross(tile: TileIndex, mixture: Mixture): Long =
        roomBy(tile, mixture, ALL_SINKS, ::routeRemaining)

    // ⚠️ **Not `inline`**, though the identity check below invites it: an inlined function parameter
    // cannot be compared by reference, and `serves === ALL_SINKS` is the whole of the fast path.
    // Called once per source per step rather than per tile per direction, so the two bound
    // references it allocates are nothing beside the walk that filled [routes].
    private fun roomBy(
        tile: TileIndex,
        mixture: Mixture,
        serves: (Acceptance) -> Boolean,
        short: (Demand) -> Long,
    ): Long {
        // ⚠️ **The fast path is skipped when the caller is fussy about sinks**, because
        // [permitsAnything] answers for the tile rather than for any particular appetite — and the
        // whole point of [serves] is that one of them is not this source's to answer.
        if (serves === ALL_SINKS && permitsAnything(tile)) return Acceptance.UNLIMITED
        val here = routes.getOrNull(tile.index) ?: return 0L
        var owed = 0L
        for (d in here) {
            if (!d.wants(mixture)) continue
            if (!serves(d.acceptance)) continue
            if (d.acceptance.isUnlimited) return Acceptance.UNLIMITED
            val remaining = short(d)
            if (remaining > 0L) owed = saturated(owed, remaining)
        }
        return owed
    }

    /**
     * Books [mass] of [mixture] out of [tile] against the sinks it was let go for — see [promised].
     *
     * ⛔ **Called by whoever let go, and only after it has landed.** A source that asked [room] and
     * then found the tile occupied has promised nothing; booking the intention rather than the
     * deposit would hold the next source back on behalf of material that never left.
     *
     * Divided among the sinks in proportion to what each still wants, which is [chargeStandingLoad]'s
     * rule for a lump already on the track and has to be: the two are the same material a step
     * apart, and a lump is eaten exactly once however many sinks could have eaten it.
     *
     * ⚠️ **The shares add up to [mass] exactly.** A gram of demand that nobody is charged for is a
     * gram some source will send after this one and nothing will eat.
     */
    fun promise(tile: TileIndex, mixture: Mixture, mass: Long) {
        if (mass <= 0L || permitsAnything(tile)) return
        val here = routes.getOrNull(tile.index) ?: return
        var wantedHere = 0L
        for (d in here) {
            if (!d.wants(mixture)) continue
            // Nothing to ration. An endless sink is why [room] answered [Acceptance.UNLIMITED] in
            // the first place, and a promise against a number that never runs down means nothing.
            if (d.acceptance.isUnlimited) return
            val remaining = remaining(d)
            if (remaining > 0L) wantedHere = saturated(wantedHere, remaining)
        }
        if (wantedHere <= 0L) return

        // ⛔ **Apportioned off a running total, never a share at a time.** The same arithmetic
        // [org.emerge.demo.outofspace.chem.Mixture.take] uses and for the same reason: shares taken
        // one at a time each truncate on their own, so they sum to less than what left the source
        // and the grams nobody was charged for are grams the next source will send after them.
        var seen = 0L
        var given = 0L
        var last: Acceptance? = null
        for (d in here) {
            if (!d.wants(mixture)) continue
            val remaining = remaining(d)
            if (remaining <= 0L) continue
            seen = saturated(seen, remaining)
            val upTo = scaledRatio(minOf(seen, wantedHere), wantedHere, mass)
            val share = upTo - given
            if (share <= 0L) continue
            promised[d.acceptance] = promisedTo(d.acceptance) + share
            given = upTo
            last = d.acceptance
        }
        // Whatever the truncation left over goes to the last sink charged, so that what was
        // promised is exactly what was let go of.
        val remainder = last ?: return
        if (given < mass) promised[remainder] = promisedTo(remainder) + (mass - given)
    }

    /**
     * Carries [previous]'s promises onto this whitelist.
     *
     * The graph is rebuilt mid-step when something has finished coming apart and left the network.
     * What was promised before that is still promised: the material is on the track either way, and
     * the sink it was let go for is the same sink.
     */
    fun carryPromisesFrom(previous: Whitelist) {
        promised.putAll(previous.promised)
    }

    private fun promisedTo(sink: Acceptance): Long = promised[sink] ?: 0L

    /**
     * What [d]'s sink is still short of **for a source about to let go**: what it wants, less
     * everything already coming — what stands anywhere on the network ([inFlight]) and what has been
     * let go for it since the walk ([promised]).
     *
     * ⛔ **[inFlight] and not [Demand.covered], which is the whole of the sibling-branch fix.** The
     * route figure answers "what is between me and it", and a source on one branch is not on the
     * other; the global figure answers "what is already spoken for", which is the question a source
     * is actually asking. See [inFlight], and [routeRemaining] for the question a *moving* lump asks.
     *
     * ⛔ **Never asked of an endless sink**, whose [Acceptance.wanted] is not a quantity.
     */
    private fun remaining(d: Demand): Long =
        d.acceptance.wanted - inFlightTo(d.acceptance) - promisedTo(d.acceptance)

    /**
     * What [d]'s sink is still short of **as seen from one tile** — [Demand.covered]'s own reading.
     *
     * ⛔ **This is the number a lump already on the track must be weighed against, and it must stay
     * per-route.** Everything on the network is apportioned to its sinks in [inFlight], the lump
     * asking included; weigh it against that and it is counted against itself, reads as surplus, and
     * is refused the move toward the very sink it was let go for. [Demand] warns about that deadlock
     * from the other end and the whole suite went red proving it once already.
     *
     * ⚠️ **[promised] is not read here either**, for [permits]' reason: promises ration what is *let
     * go of*, never what is already in the corridor. That makes this and the rationed half of
     * [permits] one computation, which is what [roomToCross]'s caller has always claimed they were.
     */
    private fun routeRemaining(d: Demand): Long = d.acceptance.wanted - d.covered

    private fun inFlightTo(sink: Acceptance): Long = inFlight[sink] ?: 0L

    companion object {
        /** Permits nothing anywhere: a world whose flow has not been worked out yet. */
        fun empty(): Whitelist =
            Whitelist(arrayOfNulls(0), BooleanArray(0), emptyMap(), emptyMap(), emptyMap())

        private fun saturated(a: Long, b: Long): Long {
            val sum = a + b
            return if (sum < a) Long.MAX_VALUE / 2 else sum
        }

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
            usableBy: (TileIndex, Acceptance) -> Long = { t, a -> loadOn(t, a.bill) },
        ): Whitelist {
            // ⛔ **[excluded] is a tile deleted from the network for the length of one walk**, which
            // is how "what could this span reach if it were not in the way of itself" is asked — see
            // [reflectingSpans]. Null for the walk everything else reads.
            //
            // ⛔ **[tally] is supplied for the MAIN walk and for nothing else.** A reflecting span's
            // extra walk is this same network asked a hypothetical — "what would the far end reach
            // if this mouth were not in its own way" — over the very same standing lumps, so a tally
            // shared with it would count every one of them again per span. Null means "walk, but do
            // not tot up". See [Whitelist.inFlight].
            fun walk(excluded: TileIndex?, tally: HashMap<Acceptance, Long>?): Reach {
                val routes = arrayOfNulls<MutableList<Demand>>(tileCount)
                val unlimited = BooleanArray(tileCount)

                // One tile's answer, worked out from its successors' — and nothing else. Everything it
                // reads is either tile-local or already in [routes]/[unlimited], and everything it
                // writes is its own slot, so asking it twice gives the same answer twice. That is what
                // lets a cycle be iterated below.
                //
                // [charge] is the one part that is **not** idempotent across a lap: what stands on the
                // track is divided among the sinks that can eat it, and going round a loop twice would
                // charge the same lump twice. So the fixed point is reached without it and it is applied
                // on one final lap. Returns whether anything about this tile moved.
                fun visit(tile: TileIndex, charge: Boolean): Boolean {
                    val i = tile.index
                    var any = false
                    var here: MutableList<Demand>? = null

                    val own = acceptanceAt(tile)
                    // A construction site standing here is in the way of everything beyond it. The
                    // **hungriest** speaks for the tile: where a ghost machine stands on ghost track
                    // there are two, and what is owed has to cover the larger.
                    //
                    // ⛔ **Found before anything is added, because a plug is in the way of its
                    // neighbours on its OWN tile.** A ghost machine stands at the tile it is fed at, and
                    // that tile may be unpaid track — Stu's save, 2026-08-22: a Concentrator site at
                    // (16,28) over a ghost rail, with a Concentrator deconstructing at (19,28) three tiles
                    // to the right. The site's titanium appetite propagated up the corridor with a clear
                    // road, because "a site is never in its own way" was read as a fact about the
                    // *tile*; the door then refused the titanium at the ghost rail (which admits iron
                    // and nothing else) and 300kg of casing came apart into a corridor it could never
                    // leave. Being on the same tile as the plug is being **behind** it: the material has
                    // still got to cross that door.
                    var plug: Acceptance? = null
                    if (own != null) {
                        for (a in own) {
                            if (a.isSatisfied || !a.stopsTraffic) continue
                            if (plug == null || a.wanted > plug.wanted) plug = a
                        }
                    }
                    // What the plug is owed, once, for every appetite here that is not the plug itself.
                    val plugged = if (plug == null) null else carried(null, plug, tile, loadOn)

                    // ⛔ **Unless it is the near end of a span.** "A sink that says nothing is a
                    // machine, and a machine takes anything for ever" is the right reading of every
                    // other input port and the wrong one of a bridge: a bridge is not where material
                    // ends up, it is a place material passes through, and what it should ask for is
                    // whatever lies on the other side. Read as a machine it told every corridor
                    // leading to it that its load was wanted whatever stood beyond — so a span was the
                    // one place on the network a source would pour into for no reason, and the
                    // corridor past it filled with lumps nothing could eat. See [FlowGraph.hopTo].
                    val hop = flow.hopTo(tile)
                    if (own == null && hop == null && tile in flow.sinks) any = true
                    if (own != null) {
                        for (a in own) {
                            // A site is never in its own way — no blocks for the plug's *own* demand,
                            // which is what keeps material flowing to the site that dissolves the
                            // obstruction. What *stands* here is charged below, along with every other
                            // appetite this tile can see: a lump on a ghost feeds that ghost first, but
                            // it is the same lump the ghosts beyond are waiting for and it cannot be
                            // promised to them all.
                            val blocks = if (a === plug) null else plugged
                            // ⛔ **[Acceptance.takesAnything], not [Acceptance.isUnlimited]** — the flag
                            // means "everything beyond here is welcome anywhere", which is a statement
                            // about fussiness and not about quantity. Reading the endless one instead
                            // let a locked warehouse set it, and the tile then answered `permitsAnything`
                            // to the very lumps the lock exists to keep out.
                            if (a.takesAnything && a.isUnlimited) {
                                // … and "welcome anywhere" is exactly what a plug on this tile denies.
                                if (blocks == null) any = true
                                else here = (here ?: mutableListOf()).also { it.add(Demand(a, 0L, blocks)) }
                                continue
                            }
                            // ⚠️ **Not `filter`** — the ones worth carrying upstream are the ones still
                            // WANTING something. A satisfied acceptance answers `false` to everything,
                            // so keeping those instead silently stops finite demand propagating at all
                            // and no source ever feeds a construction site again. Eighteen tests say so.
                            if (a.isSatisfied) continue
                            here = (here ?: mutableListOf()).also { it.add(Demand(a, 0L, blocks)) }
                        }
                    }

                    // Everything this tile can send to: its neighbours, and — where it is the near
                    // end of a span — the far end of that span. The two are inherited from in exactly
                    // the same way, because they are the same fact: material leaving here arrives
                    // there, and what is wanted there is what is worth sending here.
                    val onward = if (hop == null) flow.successorTiles(tile) else flow.successorTiles(tile) + hop
                    for (next in onward) {
                        // Deleted for this walk: nothing may be inherited through it, which is the whole
                        // of what "with the mouth taken out" means.
                        if (next == excluded) continue
                        val j = next.index
                        if (unlimited[j]) {
                            val blocks = carried(null, plug, tile, loadOn)
                            if (blocks == null) any = true
                            else {
                                val list = here ?: mutableListOf<Demand>().also { here = it }
                                if (list.none { it.acceptance === Acceptance.ANYTHING }) {
                                    list.add(Demand(Acceptance.ANYTHING, 0L, blocks))
                                }
                            }
                        }
                        val theirs = routes[j] ?: continue
                        for (d in theirs) {
                            // ⚠️ **Inherited unchanged.** What stands on *this* tile is charged once,
                            // after every appetite reachable from here is known — see below.
                            val covered = if (d.acceptance.isUnlimited) 0L else d.covered
                            val route = Demand(d.acceptance, covered, carried(d.blocks, plug, tile, loadOn))
                            val list = here ?: mutableListOf<Demand>().also { here = it }
                            // One entry per sink, and the **most permissive** one wins: where two routes
                            // lead to the same place, "there is a way this is still wanted" is the
                            // answer, and taking it costs nothing — turning down the branch that is
                            // covered or obstructed is refused at that branch's own tile a step later.
                            // Without this a network of diamonds grows a list per route rather than per
                            // sink, and the walk stops being linear.
                            val at = list.indexOfFirst { it.acceptance === route.acceptance }
                            when {
                                at < 0 -> list.add(route)
                                better(route, list[at]) -> list[at] = route
                            }
                        }
                    }

                    // ── What stands on this tile, divided among the sinks that could eat it ──
                    //
                    // ⛔ **Once, and only among the sinks it can actually reach.** A lump is eaten by
                    // exactly one sink, so charging its whole mass to every route through this tile
                    // over-counts and starves the network, while charging it to none of them — which is
                    // what reading the largest tally instead of the sum amounted to — under-counts and
                    // feeds the far sinks a gram at a time.
                    //
                    // Each sink's share is its share of what is still wanted here, so a corridor feeding
                    // a site that needs 300g and one that needs 700g splits a packet 30:70. A sink the
                    // lump cannot be used by takes none of it and is not in the division at all — that
                    // is [loadOn] answering nought for a bill this lump does not suit.
                    if (charge) here?.let { list -> chargeStandingLoad(list, tile, loadOn, usableBy, tally) }

                    // Nothing downstream is fussy *and* nothing downstream is boundless: the list is the
                    // only thing left worth keeping, and only while the unlimited flag is not set.
                    val settled = if (any) null else here
                    val moved = unlimited[i] != any || !same(routes[i], settled)
                    unlimited[i] = any
                    routes[i] = settled
                    return moved
                }

                // ── The walk ────────────────────────────────────────────────────
                //
                // Straight through [FlowGraph.order], which is a tile at a time — except where the graph
                // hands back a **cycle**, which has to be solved whole. See [FlowGraph.loops].
                val order = flow.order
                val loops = flow.loops
                var at = 0
                while (at < order.size) {
                    val tile = order[at]
                    val loop = loops[tile]
                    if (loop == null) {
                        if (tile != excluded) visit(tile, charge = true)
                        at++
                        continue
                    }
                    // ⛔ **A loop is a fixed point, not a pass.** Appetite only ever grows here — a
                    // route is added or replaced by a more permissive one, never taken away — so
                    // repeating the lap converges, and it converges in two laps on a simple ring:
                    // Tarjan hands the members back with one seam in them, so the first lap carries
                    // everything but the edge across the seam and the second carries that.
                    //
                    // ⚠️ **Bounded by the size of the loop**, which is the worst case a nest of them
                    // can need (one hop of progress per lap), and is the guarantee that this
                    // terminates even if some future rule makes the growth non-monotone.
                    var laps = 0
                    while (laps < loop.size) {
                        var moved = false
                        for (t in loop) if (t != excluded && visit(t, charge = false)) moved = true
                        laps++
                        if (!moved) break
                    }
                    // ⚠️ **Then exactly one lap that charges**, which is the same single accounting a
                    // straight run gets: each lump on the loop is divided once among the sinks that can
                    // eat it. A tile early in the lap cannot see the charges of one late in it — on a
                    // ring there is no "early", so some seam has to wear that — and the error is one
                    // lap's worth of standing load, never a multiple of it.
                    for (t in loop) if (t != excluded) visit(t, charge = true)
                    at += loop.size
                }
                return Reach(routes, unlimited)
            }

            val inFlight = HashMap<Acceptance, Long>()
            val base = walk(excluded = null, tally = inFlight)
            // ⛔ **One more walk per span that can reach round to its own mouth**, and none at all
            // otherwise — see [reflectingSpans], which is empty on every network with no ring on it.
            val past = HashMap<TileIndex, Reach>()
            for (mouth in reflectingSpans(flow)) past[mouth] = walk(excluded = mouth, tally = null)
            return Whitelist(base.routes, base.unlimited, flow.hops, past, inFlight)
        }

        /**
         * The spans that would otherwise offer their own mouth an appetite reached only by coming
         * back through it.
         *
         * ⛔ **Appetite travels backwards across a span, and on a loop that sentence turns on
         * itself.** Everything downstream of the mouth is also, eventually, downstream of the far
         * end — by going the whole way round and in through the mouth again — so the far end reads
         * as a place the mouth's own consumers can be reached from, as though crossing were a way of
         * getting to them. It is the exact opposite: crossing is what lifts the packet off the tile
         * it needed to stay on, and it happens again on every lap.
         *
         * ⚠️ **The door acts on this**, which is why it costs material rather than tidiness.
         * `sinkAdmits` asks the road *past* the span — the right question — and was handed an answer
         * that included the road *behind* it. Stu's `cycle` save, diagnosed by him: a tonne of iron
         * bound for the silo whose input is at (10,15), on a spur hanging off (10,13), which is also
         * the mouth of `Bridge@(9,13)`. The span took the iron every lap, for ever.
         *
         * ⛔ **Stated of a span and of nothing else.** The same reflection happens at an ordinary
         * fork and costs nothing there: a packet has a *choice*, and [FlowCursors] gives the spur its
         * turn, so a loop is latency. A span is asked first and pre-empts — there is no turn.
         *
         * ⚠️ **The test is exact rather than a heuristic.** Deleting the mouth can only change what
         * the far end reaches if the far end can reach the mouth; the mouth reaches the far end by
         * the span itself, so the two can reach each other exactly when they share a component —
         * and every non-trivial one of those is in [FlowGraph.loops] already.
         */
        private fun reflectingSpans(flow: FlowGraph): List<TileIndex> {
            if (flow.loops.isEmpty()) return emptyList()
            val loopOf = HashMap<TileIndex, TileIndex>()
            for ((key, members) in flow.loops) for (t in members) loopOf[t] = key
            return flow.hops.entries
                .filter { (mouth, far) -> loopOf[mouth] != null && loopOf[mouth] == loopOf[far] }
                .map { it.key }
                .sortedBy { it.index }
        }

        /**
         * Whether two of [of]'s route lists say the same thing — the fixed-point test, and nothing
         * more general than that.
         *
         * ⚠️ **Sinks are compared by identity**, as everywhere else in this file, and the lists are
         * built in a deterministic order from the same successors, so a positional walk is enough:
         * two lists that agree entry for entry agree.
         */
        private fun same(a: List<Demand>?, b: List<Demand>?): Boolean {
            if (a == null || b == null) return a == null && b == null
            if (a.size != b.size) return false
            for (k in a.indices) {
                val x = a[k]
                val y = b[k]
                if (x.acceptance !== y.acceptance) return false
                if (x.covered != y.covered) return false
                if (owed(x.blocks) != owed(y.blocks)) return false
            }
            return true
        }

        /**
         * Charge what stands on [tile] against the demands [here] can see, in proportion to what
         * each still wants.
         *
         * ⚠️ **The shares add up to the load exactly.** Integer division leaves a few grams over,
         * and a few grams of demand that nobody is charged for is a few grams a source will send
         * and nothing will eat — a residue, which is the failure this whole area exists to avoid.
         * So the remainder goes to the hungriest sink rather than being dropped.
         *
         * ⚠️ A sink may be charged **more than it wants**, and that is correct: material already on
         * its way to a sink that has enough is not thereby available to any other. The surplus is
         * simply surplus, and [room] clamps each sink's remainder at nought rather than letting one
         * sink's excess cancel another's need.
         *
         * ⛔ **Only among the sinks this matter can actually reach, which is the same question
         * [Demand.wants] asks and not merely the bill.** A route with a [Block] still owed something
         * this load cannot pay is a route this load will never travel — the door refuses it at the
         * plug — so charging it a share credits the material against a sink that can never receive
         * it, and the sink that *can* reads as covered by a fraction of what is really coming.
         *
         * Stu's save, 2026-09-03: a wire coming apart at `(11,11)`, one wire site at `(12,8)` on
         * finished track wanting exactly one tile's worth of copper, and three more wire sites at
         * `(13..15,8)` each standing on an unpaid **titanium** rail. The blocked three were counted
         * into the division, so the 14.9kg already in the corridor was charged a quarter each and
         * `(12,8)` read as covered by 3.7kg of it. The marked wire went on pouring, `(12,8)` was
         * finished by the lump that was already coming, and the whole of the second wire — another
         * 14.9kg — came to rest in the corridor with nothing on the network able to take it. The
         * residue this pass exists to prevent, reached through the one route it did not weigh.
         */
        private fun chargeStandingLoad(
            here: MutableList<Demand>,
            tile: TileIndex,
            loadOn: (TileIndex, Mixture?) -> Long,
            usableBy: (TileIndex, Acceptance) -> Long,
            /**
             * Per sink, the same shares totted up across the whole network — [Whitelist.inFlight].
             *
             * ⚠️ **The same `share`, written twice on purpose.** Who a standing lump belongs to is
             * one question with one answer; the route figure and the global one differ in where they
             * are read from and must never differ in how the lump was divided.
             */
            tally: HashMap<Acceptance, Long>?,
        ) {
            var wantedHere = 0L
            var hungriest = -1
            var most = -1L
            for (k in here.indices) {
                val d = here[k]
                if (d.acceptance.isUnlimited) continue
                if (usableBy(tile, d.acceptance) <= 0L) continue
                if (!reachedBy(d, tile, loadOn)) continue
                val remaining = d.acceptance.wanted - d.covered
                if (remaining <= 0L) continue
                wantedHere += remaining
                if (remaining > most) { most = remaining; hungriest = k }
            }
            if (wantedHere <= 0L || hungriest < 0) return

            var given = 0L
            for (k in here.indices) {
                val d = here[k]
                if (d.acceptance.isUnlimited) continue
                val load = usableBy(tile, d.acceptance)
                if (load <= 0L) continue
                if (!reachedBy(d, tile, loadOn)) continue
                val remaining = d.acceptance.wanted - d.covered
                if (remaining <= 0L) continue
                // This sink's share of the matter standing here, floored; the rounding is settled
                // below so that nothing is charged twice and nothing goes uncharged.
                val share = scaledRatio(remaining, wantedHere, load)
                here[k] = Demand(d.acceptance, d.covered + share, d.blocks)
                if (tally != null) tally[d.acceptance] = (tally[d.acceptance] ?: 0L) + share
                given += share
            }
            val load = usableBy(tile, here[hungriest].acceptance)
            if (given < load) {
                val d = here[hungriest]
                here[hungriest] = Demand(d.acceptance, d.covered + (load - given), d.blocks)
                if (tally != null) {
                    tally[d.acceptance] = (tally[d.acceptance] ?: 0L) + (load - given)
                }
            }
        }

        /**
         * Whether what stands on [tile] can get as far as [d]'s sink — [Demand.wants]' order
         * question, asked of the load rather than of a lump offered at a door.
         *
         * A [Block] is already paid down by this tile's load where that load can serve it, so what
         * is left owed here is what this material cannot pay. Anything still owed is a site that
         * will refuse it passage, and a sink behind one is not in the division.
         */
        private fun reachedBy(
            d: Demand,
            tile: TileIndex,
            loadOn: (TileIndex, Mixture?) -> Long,
        ): Boolean {
            val blocks = d.blocks ?: return true
            for (b in blocks) if (b.owed > 0L && loadOn(tile, b.bill) <= 0L) return false
            return true
        }

        private fun owed(blocks: List<Block>?): Long {
            var total = 0L
            if (blocks != null) for (b in blocks) total += b.owed
            return total
        }

        private fun better(a: Demand, b: Demand): Boolean {
            val oa = owed(a.blocks)
            val ob = owed(b.blocks)
            return oa < ob || (oa == ob && a.covered < b.covered)
        }

        /**
         * What is owed along a route, one tile further upstream.
         *
         * A [plug] standing on this tile adds its own shortfall, and then **everything owed is paid
         * down by what stands here that its own bill can use** — each bill's load taken off exactly
         * once.
         *
         * ⚠️ **Once.** Where a plug stands on a tile that already owed something for the same bill —
         * two ghost rails in a row, which is what a freshly drawn run *is* — subtracting the load
         * once per debt credits the same packet to both of them. Two rails needing a packet apiece
         * read as paid for by one, the titanium set off, and the run jammed.
         */
        private fun carried(
            inherited: List<Block>?,
            plug: Acceptance?,
            tile: TileIndex,
            loadOn: (TileIndex, Mixture?) -> Long,
        ): List<Block>? {
            val plugBill = plug?.bill
            if (inherited == null && plugBill == null) return null
            val owedBy = ArrayList<Pair<Mixture, Long>>(2)
            if (inherited != null) for (b in inherited) owedBy.add(b.bill to b.owed)
            if (plug != null && plugBill != null) {
                val at = owedBy.indexOfFirst { it.first === plugBill }
                if (at < 0) owedBy.add(plugBill to plug.wanted)
                else owedBy[at] = plugBill to (owedBy[at].second + plug.wanted)
            }
            var out: ArrayList<Block>? = null
            for ((bill, total) in owedBy) {
                val left = total - loadOn(tile, bill)
                if (left <= 0L) continue
                (out ?: ArrayList<Block>(owedBy.size).also { out = it }).add(Block(bill, left))
            }
            return out
        }
    }
}
