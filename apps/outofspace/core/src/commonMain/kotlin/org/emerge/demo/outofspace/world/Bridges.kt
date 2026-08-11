package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.world.MachineKind.Bridge

/**
 * Bridge: 3-tile span for crossing conduit runs. Occupies no layer (no special network cases).
 * Three slots (entry/middle/exit): honest about material position, not a bottleneck.
 * Ports at bridge's own ends (constrains placement: no two same-conduit ports on one tile).
 */
data class Bridge(
    override val facing: Direction,
    val conduit: Conduit = Conduit.Rail,
    /** At the input end, just lifted off the track. */
    val entry: Packet? = null,
    /** Over the tile being hopped — the one place a bridge is genuinely above the deck. */
    val middle: Packet? = null,
    /** At the output end, waiting to be put down on the track there. */
    val exit: Packet? = null,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Bridge),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Bridge
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)

    /** Everything aboard, input end first. */
    val carried: List<Packet> get() = listOfNotNull(entry, middle, exit)

    val mass: Long get() = (entry?.mass ?: 0L) + (middle?.mass ?: 0L) + (exit?.mass ?: 0L)

    /**
     * Everything shifted one place toward the output end, as far as there is room.
     *
     * Walked from the **far end back**, so a full bridge empties its exit onto the track and shuffles
     * along in the same step rather than stalling for two — the same rule, and for the same reason,
     * as [org.emerge.demo.outofspace.world.FlowGraph.order] on the track itself.
     */
    fun advanced(): Bridge {
        var out = this
        if (out.exit == null) out = out.copy(exit = out.middle, middle = null)
        if (out.middle == null) out = out.copy(middle = out.entry, entry = null)
        return out
    }

    companion object {
        /** Ticks between a conduit advancing. At 4 Hz this is 4 tiles a second. */
        const val STEP_TICKS = 1

        /** Tiles a bridge spans, and so how many packets it can have aboard at once. */
        const val SLOTS = 3
    }
}
