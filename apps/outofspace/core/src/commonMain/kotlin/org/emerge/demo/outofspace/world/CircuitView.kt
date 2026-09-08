package org.emerge.demo.outofspace.world

/**
 * **What the circuit looked like this tick**, flattened to the grid so the view can read it without
 * knowing what a node is.
 *
 * `PLAN_power_network.md` increment 3b. The solve works over *bodies* — several to a tile, renumbered
 * every tick as the world is edited — and none of that is a thing a renderer should have to hold an
 * opinion about. So the answer is flattened once, here, into two arrays indexed by tile.
 *
 * ⭐ **Two readings, because a short is silent in either one alone.** [component] says what is joined
 * to what and works on a **dead** ship; [current] says what is actually moving and works on a live
 * one. A player whose machine does nothing needs the first to see that two things they believed were
 * separate are one circuit, and the second to see that all of their current is going round a casing
 * instead of through the element.
 */
class CircuitView(
    /** Which circuit each tile's conductor belongs to, or -1 where nothing conducts. */
    val component: IntArray,
    /**
     * Current leaving each tile in each direction, indexed `tile * 4 + dir.ordinal`, signed.
     *
     * ⚠️ **Summed across layers.** A tile can hold a rail and a cable at once and they can be in
     * different circuits; the overlay is a whole-ship view and draws one flow per face. What it
     * cannot show, the inspector can.
     */
    val current: LongArray,
    /** The largest magnitude anywhere, so the view can normalise without a second pass. */
    val peak: Long,
) {
    companion object {
        fun empty(tiles: Int): CircuitView = CircuitView(IntArray(tiles) { -1 }, LongArray(tiles * 4), 0L)
    }
}
