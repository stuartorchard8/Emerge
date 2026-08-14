package org.emerge.demo.outofspace.world

/**
 * Where everything on the conduit layers came from during the tick that just ran.
 *
 * **This is presentation, and only presentation.** Nothing in the sim reads it, nothing branches on
 * it, and it is not written to a save — a freshly loaded world simply has no motion for one tick and
 * then has it again. It exists because a packet stepping a whole tile every quarter of a second
 * looks like teleporting, and the fix needs one fact the renderer cannot recover on its own: which
 * tile a packet was on before.
 *
 * That fact genuinely is unrecoverable downstream. Given two consecutive snapshots, a packet on a
 * tile with three joined neighbours might have come from any of them, or from a machine's port, or
 * have sat still while the tile it came from was refilled behind it. The mover knows; the observer
 * is guessing. So the mover writes it down.
 *
 * Everything here is per **rail** tile plus a short list of things that left the layer entirely.
 * Bridges are kept separately because a bridge is three slots at one tile rather than a tile.
 */
class Motion(
    /** Per tile: how whatever sits there now arrived. One of the `FROM_` codes below. */
    private val arrivals: ByteArray,
    /**
     * Per tile: the mass of that packet at the start of the tick, wherever it was standing.
     *
     * Separate from the arrival code because size and position are separate animations, and the
     * cases that need this one are exactly the awkward ones: a packet half-drawn into a machine, or
     * topped up from a machine's output, or squashed into by the packet behind it. All three change
     * a lump's mass without moving it, and all three look like a pop without this.
     */
    private val previousMass: LongArray,
    /** Per bridge tile, a bitmask of which slots are newly filled — see the `SLOT_` bits. */
    private val bridgeSlots: Map<Int, Int>,
    /** Things that left the layer this tick, drawn shrinking away as they go. */
    val departures: List<Departure>,
) {
    /** Whether [tile]'s packet arrived from a neighbour, and if so which way it was travelling. */
    fun arrivedFrom(tile: Int): Direction? {
        if (tile < 0 || tile >= arrivals.size) return null
        val code = arrivals[tile].toInt()
        return if (code in 1..Direction.ALL.size) Direction.ALL[code - 1] else null
    }

    /** True when [tile]'s packet was put there by a machine's output port, and should grow in. */
    fun appearedAt(tile: Int): Boolean =
        tile in arrivals.indices && arrivals[tile].toInt() == FROM_PORT

    /** The mass [tile]'s packet had at the start of the tick. */
    fun previousMassAt(tile: Int): Long =
        if (tile in previousMass.indices) previousMass[tile] else 0L

    fun bridgeSlotIsNew(tile: Int, slot: Int): Boolean =
        (bridgeSlots[tile] ?: 0) and (1 shl slot) != 0

    override fun equals(other: Any?): Boolean =
        this === other || (other is Motion &&
            arrivals.contentEquals(other.arrivals) &&
            previousMass.contentEquals(other.previousMass) &&
            bridgeSlots == other.bridgeSlots &&
            departures == other.departures)

    override fun hashCode(): Int = arrivals.contentHashCode()

    companion object {
        /** Sitting where it already was. This is also what a world that has never ticked reports. */
        const val STILL: Int = 0

        /** Handed over by a machine's output port, or set down off a bridge: grows from nothing. */
        const val FROM_PORT: Int = 5

        /**
         * Slot bits for [bridgeSlotIsNew], matching [org.emerge.demo.outofspace.world.machine.Bridge]'s entry/middle/exit order.
         *
         * Only the middle and the exit are ever set. A bridge's ports sit at ±1 from its centre,
         * which is exactly where the entry and exit slots are drawn, so stepping onto a bridge is a
         * change of layer at one tile and there is no movement to draw. The two shifts *along* the
         * span are real travel, and those are these.
         */
        const val SLOT_ENTRY = 0
        const val SLOT_MIDDLE = 1
        const val SLOT_EXIT = 2

        /** A world with nothing moving — what a freshly loaded or freshly built vessel has. */
        val NONE = Motion(ByteArray(0), LongArray(0), emptyMap(), emptyList())
    }
}
