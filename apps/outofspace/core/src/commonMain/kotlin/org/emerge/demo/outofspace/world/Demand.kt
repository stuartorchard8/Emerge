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
