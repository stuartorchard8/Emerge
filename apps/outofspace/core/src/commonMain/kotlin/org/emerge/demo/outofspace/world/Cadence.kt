package org.emerge.demo.outofspace.world

/**
 * When a pass wrote a fact, and how long that fact stands before the same pass writes it again.
 *
 * **This is presentation, and only presentation** — the same standing as [Motion], which carries
 * one of these. It exists so that a view can draw a fact part-way to its replacement without
 * knowing anything at all about the schedule that produced it.
 *
 * The renderer used to work the same thing out for itself, from the global tick clock and an
 * imported period: `(alpha % RAIL_PERIOD) / RAIL_PERIOD`. That expression is three assumptions in
 * a trench coat — that the pass fires on tick zero of its period, that the clock's own wrap lines
 * up with that period, and that the view is entitled to know the period in the first place. The
 * first of those stopped being true the day the subsystems were given staggered offsets, and the
 * result was packets that snapped a fifth of a tile forward on arrival and then teleported a whole
 * tile backwards two-thirds of the way through the slide.
 *
 * ⛔ **The fix is not a better formula, it is a different direction of travel.** Nothing downstream
 * infers when a pass ran; the pass says so. A view asks [progress] and gets a number, and stays
 * right through any offset, any period, a period that does not divide the tick rate, a changed
 * tick rate, and a pass that fires irregularly.
 */
data class Cadence(
    /**
     * The **reducer** tick the fact was written on — `state.tick` as the pass saw it, which is one
     * less than the `tick` of the state the pass went on to produce.
     *
     * That off-by-one is not an accident to be tidied away, it is the thing that makes this line up
     * with the clock: [org.emerge.demo.outofspace.OutofspaceController.simTime] is the reducer tick
     * of the last completed step plus the fraction of a tick since, so stamping the reducer's own
     * tick puts [progress] at exactly zero on the first frame drawn after the pass ran.
     */
    val writtenAtTick: Long,
    /**
     * Ticks until the pass runs again — its period. Zero means "nothing to animate".
     *
     * The nominal period rather than the measured gap, for a pass that fires on a fixed one: it is
     * what the pass *will* do next, and measuring the last gap instead makes the one cycle after
     * any change stretch or snap. A pass that fires irregularly should stamp the measured gap since
     * its own previous write, which is wrong for one cycle and right afterwards.
     */
    val spanTicks: Int,
) {
    /**
     * How far the fact has got towards being replaced: 0 the instant it was written, 1 by the time
     * the pass writes again. [simTime] is in fractional ticks — see `OutofspaceController.simTime`.
     *
     * ⚠️ **The clamp is load-bearing.** A frame that arrives late, or a tick dropped by the
     * spiral-of-death guard, leaves this past the end of its span; clamped, the animation settles
     * where it was going and waits. Unclamped it would sail past the destination and be yanked
     * back, which is the same rubber-band the staggered offsets caused and which this exists to
     * stop happening again.
     */
    fun progress(simTime: Double): Float =
        if (spanTicks <= 0) 1f
        else ((simTime - writtenAtTick) / spanTicks).coerceIn(0.0, 1.0).toFloat()

    companion object {
        /**
         * Nothing to animate: a world that has never ticked, one freshly loaded from a save, or a
         * capture that must show where things *are*. [progress] is 1 for any time whatsoever.
         */
        val SETTLED = Cadence(0L, 0)
    }
}
