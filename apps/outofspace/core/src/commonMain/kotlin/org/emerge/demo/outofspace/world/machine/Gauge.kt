package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * Reads whatever passes along the track under it, and puts that on the wire under it.
 *
 * A **building standing over a run**, not a property of the run. It used to be a flag on
 * [org.emerge.demo.outofspace.world.Segment], which is where every fitting lived before matter had
 * layers of its own; a length of track that was *also* an instrument was the last thing keeping the
 * conduit layers from being nothing but conduit.
 *
 * ⚠️ **It does not lay its own track.** Placing one used to lay rail under itself, because it *was*
 * the rail; now it stands on whatever is threaded beneath it and reads nothing until a run arrives —
 * which is how every other machine with a port already behaves.
 *
 * The readings are on the machine rather than in a layer because they are not matter. Nothing can
 * react with "the last thing through was 80% iron"; it is an observation, and it belongs with the
 * instrument that made it.
 */
data class Gauge(
    override val center: TileIndex,
    val lastDominant: Species? = null,
    /** The dominant species' share of the last thing through, in permille. */
    val lastPurity: Int = 0,
    val lastMass: Long = 0L,
    override val wiring: Wiring = Wiring.RUNNING,
) : DeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Gauge
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    /** This gauge having seen [packet] go past. Reads it; does not consume it. */
    fun reading(packet: Packet?): Gauge {
        if (packet == null) {
            if (lastMass == 0L) return this
            return copy(lastDominant = null, lastPurity = 0, lastMass = 0)
        }
        val dominant = packet.contents.dominant ?: return this
        val mass = packet.mass
        return copy(
            lastDominant = dominant,
            lastPurity = if (mass == 0L) 0 else (packet.contents[dominant] * SignalField.FULL / mass).toInt(),
            lastMass = mass,
        )
    }
}
