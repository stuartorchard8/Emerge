package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.MergeResult
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.logistics.mergeInto

/**
 * Which transport network something belongs to.
 *
 * Four separate networks sharing one tile grid: a rail, a pipe, a power line and a signal line can
 * all cross the same tile without meeting, because they are different layers. That is the thing ONI
 * gets right and the reason it can build dense factories that are still readable — routing is a
 * puzzle about *each* network rather than one fight for floor space.
 *
 * Structure, heat and atmosphere read none of these. They only ever look at the deck.
 */
enum class Conduit(val label: String) {
    Rail("RAIL"),
    Pipe("PIPE"),
    Power("POWER"),
    Signal("SIGNAL"),
}

/**
 * A length of conveyor or pipe: one tile of one conduit layer.
 *
 * **A segment has no direction of its own.** That is the whole difference between this and the belt
 * it replaces. Which way material moves along a run is decided by where the sources and sinks on it
 * are — see [FlowField] — so laying track is laying *topology*, and reversing a line is a matter of
 * moving the machine that feeds it rather than rotating fifty tiles.
 *
 * A segment is also **inert**: it has no wiring and cannot be switched off. It is plumbing. The
 * things that make decisions are the buildings at the ends of it.
 */
data class Segment(
    val conduit: Conduit,
    /**
     * Which neighbours this tile is **joined** to: one bit per [Direction], by ordinal.
     *
     * Two segments sitting next to each other are not connected. They are connected when the player
     * drew a line through both, and only then. That is the difference between track being a *shape*
     * and track being a *graph*, and it decides how the whole game reads:
     *
     *  - Two lines can run side by side, touching, without merging. Without this, every parallel run
     *    needs a tile of clearance and a factory sprawls for no reason anyone can see.
     *  - A bridge can genuinely cross, because the line passing underneath is unlinked to it in the
     *    ordinary way rather than being kept at arm's length. This is what let a bridge's ports move
     *    back to its own two ends, where they belong.
     *  - Adding a tile of track cannot silently rewire a distant part of the factory, which
     *    adjacency-joining did every time a new run brushed an old one.
     *
     * Always symmetric: [linkedTo] is only ever set through [joinedTo], which sets both halves. A
     * one-sided link would be a valve nobody asked for.
     */
    val links: Int = 0,
    /** What is riding on this tile. Partial packets are normal — see [org.emerge.demo.outofspace.logistics.mergeInto]. */
    val held: Packet? = null,
    /**
     * If set, this length of track is a **gauge**: it reports what passes through it on this channel.
     *
     * A gauge is a property of a segment rather than a machine of its own, which is what it always
     * wanted to be — the old analyzer was described in its own documentation as "a belt tile that
     * measures", and making it a building meant it needed ports, which meant it broke a run in two
     * for no reason. As a segment it is simply track that reads.
     *
     * It measures without taking: material passes at full speed, and the reading **persists** after
     * it has gone, so an idle line still says what last went down it.
     */
    val channel: Channel? = null,
    val lastForm: Form? = null,
    val lastDominant: Species? = null,
    /** The dominant species' share of the last thing through, in permille. */
    val lastPurity: Int = 0,
    val lastMass: Long = 0L,
) {
    val isGauge: Boolean get() = channel != null

    /** Whether this tile is joined to its neighbour in [dir]. */
    fun linkedTo(dir: Direction): Boolean = links and (1 shl dir.ordinal) != 0

    /** True for track that joins nothing — a stub, laid but not yet drawn into a line. */
    val isIsolated: Boolean get() = links == 0

    fun joinedTo(dir: Direction): Segment = copy(links = links or (1 shl dir.ordinal))

    fun cutFrom(dir: Direction): Segment = copy(links = links and (1 shl dir.ordinal).inv())

    /** This segment having seen [packet] go past. Reads it; does not consume it. */
    fun reading(packet: Packet): Segment {
        if (channel == null) return this
        val dominant = packet.contents.dominant ?: return this
        val mass = packet.mass
        return copy(
            lastForm = (packet as? SolidPacket)?.form,
            lastDominant = dominant,
            lastPurity = if (mass == 0L) 0 else (packet.contents[dominant] * Signals.FULL / mass).toInt(),
            lastMass = mass,
        )
    }
}
