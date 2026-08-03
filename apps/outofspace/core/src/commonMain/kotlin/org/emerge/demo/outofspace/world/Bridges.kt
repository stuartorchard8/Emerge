package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.world.MachineKind.Bridge

/**
 * A three-tile span that takes material in on one side and puts it out on the other,
 * leaving everything between its two connections clear.
 *
 * It is how two runs of the same conduit cross. And it needs no special case anywhere in the network
 * code, because it is not one: it occupies **nothing on any layer**, so a run passing beneath it is
 * unconnected for the ordinary reason — the two share no port. "Hopping over" is what that looks
 * like from the outside; there is no hop in the model.
 *
 * ### It is three tiles of track, and it behaves like three tiles of track
 *
 * A bridge carries [entry], [middle] and [exit]: one slot per tile it spans, shifted one place along
 * per conduit step by [advanced], in step with everything else on the layer. A packet is picked up
 * off the track at the input port, crosses the tile being hopped, reaches the far end and is put
 * down on the track there — three steps, which is exactly what the same three tiles of ordinary rail
 * would have cost.
 *
 * It held **one** packet for a while, and that was the wrong shape twice over. It made a bridge a
 * bottleneck rather than a detour — three tiles of throughput squeezed into one slot — and it made
 * the span a place where material vanished for a tick and reappeared, which is precisely the
 * wormhole reading the latency was there to avoid. Three slots cost the same latency the track costs
 * and are honest about where the material is, which is what lets the renderer just draw it.
 *
 * Its ports sit at its **own two ends**. They spent a while flanking the span instead, because
 * segments used to join by mere adjacency and track at a bridge's end would have sat next to the run
 * it was meant to be hopping over — merging the two regardless of ports. Explicit links removed that
 * reason, and the ports came home.
 *
 * Those two ports are the only thing constraining where it can go: no two ports of the same conduit
 * may share a tile, or which of them a segment feeds would be ambiguous.
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
) : Directed {
    override val kind: MachineKind get() = MachineKind.Bridge
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)

    /** Everything aboard, input end first. */
    val carried: List<Packet> get() = listOfNotNull(entry, middle, exit)

    val mass: Long get() = (entry?.mass ?: 0L) + (middle?.mass ?: 0L) + (exit?.mass ?: 0L)

    /**
     * Everything shifted one place toward the output end, as far as there is room.
     *
     * Walked from the **far end back**, so a full bridge empties its exit onto the track and shuffles
     * along in the same step rather than stalling for two — the same rule, and for the same reason,
     * as [org.emerge.demo.outofspace.world.FlowField.order] on the track itself.
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
