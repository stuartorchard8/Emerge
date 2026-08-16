package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.logistics.Packet

/**
 * Collects a tick's worth of [Motion] as the tick happens.
 *
 * Mutable and short-lived: one is built at the top of a tick from the state the tick started with,
 * written to by the ejection and conduit passes, and frozen into a [Motion] at the end. The sim
 * never reads back from it.
 */
class MotionLog(rails: List<Segment?>) {
    private val arrivals = ByteArray(rails.size)
    private val previousMass = LongArray(rails.size) { rails[it]?.held?.mass ?: 0L }
    private val bridgeSlots = HashMap<TileIndex, Int>()
    private val departures = ArrayList<Departure>()
    private val ontoBridge = HashSet<TileIndex>()

    /** A machine's output port set a packet down on empty track: it should grow in rather than blink. */
    fun placedByPort(tile: TileIndex) {
        arrivals[tile.index] = Motion.FROM_PORT.toByte()
        previousMass[tile.index] = 0L
    }

    /**
     * A packet stepped from [from] to [to].
     *
     * The vacated tile is reset as well as the filled one. It is usually refilled later in the same
     * pass by whatever was behind it — the walk goes most-downstream first — and if it is not, then
     * nothing is there, and it must not still be claiming a mass.
     */
    fun moved(from: TileIndex, to: TileIndex, direction: Direction) {
        arrivals[to.index] = (direction.ordinal + 1).toByte()
        previousMass[to.index] = previousMass[from.index]
        arrivals[from.index] = Motion.STILL.toByte()
        previousMass[from.index] = 0L
    }

    /**
     * A packet was taken off the track by a machine's input port, and is drawn shrinking into it.
     *
     * Silently ignored for a tile that [handedToBridge] has already claimed. A bridge's input port
     * sits on the same tile as its entry slot, so a packet "taken" there has not left anywhere — it
     * has changed layer without changing place, and shrinking it away would delete a lump that is
     * still sitting in plain sight.
     */
    fun takenFromRail(tile: TileIndex, packet: Packet) {
        arrivals[tile.index] = Motion.STILL.toByte()
        previousMass[tile.index] = 0L
        if (tile in ontoBridge) return
        departures.add(Departure(tile, packet))
    }

    /** This tile's packet stepped up onto a bridge, which is not a disappearance. See above. */
    fun handedToBridge(tile: TileIndex) {
        ontoBridge.add(tile)
    }

    /** A bridge slot that is occupied now and was not a moment ago. */
    fun bridgeSlotFilled(tile: TileIndex, slot: Int) {
        bridgeSlots[tile] = (bridgeSlots[tile] ?: 0) or (1 shl slot)
    }

    fun freeze(): Motion = Motion(arrivals, previousMass, bridgeSlots.toMap(), departures.toList())
}
