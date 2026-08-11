package org.emerge.demo.fluidlab.world

import org.emerge.demo.fluidlab.chem.Form
import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.logistics.Capacity
import org.emerge.demo.fluidlab.logistics.MergeResult
import org.emerge.demo.fluidlab.logistics.Packet
import org.emerge.demo.fluidlab.logistics.SolidPacket
import org.emerge.demo.fluidlab.logistics.mergeInto

/** Conduit layer: rail, pipe, power, signal. Four separate networks sharing one tile grid. */
enum class Conduit(val label: String) {
    Rail("RAIL"),
    Pipe("PIPE"),
    Power("POWER"),
    Signal("SIGNAL"),
}

/**
 * One tile of one conduit layer. No inherent direction (sources/sinks decide flow via FlowGraph).
 * Inert/plumbing — no wiring, no on/off state.
 */
data class Segment(
    val conduit: Conduit,
    /** Neighbour join bits (one per Direction). Explicit links ≠ adjacency — two tiles must be explicitly joined. Symmetric only. */
    val links: Int = 0,
    /** What is riding on this tile. Partial packets are normal — see [org.emerge.demo.fluidlab.logistics.mergeInto]. */
    val held: Packet? = null,
    /**
     * Gauge: reads what passes through (full speed, persists after). Segment property, not a building.
     *
     * It used to carry the channel it broadcast on. It now carries only the fact that it *is* one: a
     * gauge is a transmitter like any other, and it puts [lastPurity] on the signal network under its
     * own tile. Layers share tiles, so a gauge on the rail and a wire on the signal layer coexist on
     * one tile with no ceremony at all.
     */
    val isGauge: Boolean = false,
    val lastForm: Form? = null,
    val lastDominant: Species? = null,
    /** The dominant species' share of the last thing through, in permille. */
    val lastPurity: Int = 0,
    val lastMass: Long = 0L,
    /**
     * How much thermal energy this length of track is holding, in the millijoules [Material]
     * documents — see [Machine.joules] for why it lives here rather than in a field beside the
     * layer.
     *
     * The default reads [conduit], which is declared above it: a tile of rail starts as iron at room
     * temperature and a tile of pipe as copper, without anything having to remember to set it.
     */
    /**
     * If set, this length of pipe is a **valve**: gas crosses freely between it and the room sharing
     * its tile.
     *
     * A property of a segment for exactly the reason a gauge is one, and it took a wrong turn to
     * find out. Built first as a deck machine, it did not work at all and could not have: placing a
     * building *displaces the air out of its own tile*, so a valve-as-machine opened onto a cell
     * that placing it had just emptied, and nothing ever crossed. That is not a bug in the placement
     * rule — a machine is a solid thing that occupies a deck, and the rule is right. A valve is not
     * one. It is a fitting on a run, like the pipe it sits on, and fittings do not displace air.
     *
     * Being a segment also makes the impossible states impossible: a valve cannot exist without a
     * pipe to be part of, it needs no ports, and it cannot break a run in two.
     *
     * Meaningful only on [Conduit.Pipe]. Nothing enforces that, in the same way nothing stops a
     * gauge flag being set on a pipe — the brush only ever sets it on plumbing, and [isValve] is asked
     * exclusively by the pipe layer.
     */
    val valve: Boolean = false,
    val joules: Long = conduit.ambientPerTile,
) {
    /** True for a length of pipe that is open to the room around it — see [valve]. */
    val isValve: Boolean get() = valve && conduit == Conduit.Pipe

    /** Whether this tile is joined to its neighbour in [dir]. */
    fun linkedTo(dir: Direction): Boolean = links and (1 shl dir.ordinal) != 0

    /** True for track that joins nothing — a stub, laid but not yet drawn into a line. */
    val isIsolated: Boolean get() = links == 0

    fun joinedTo(dir: Direction): Segment = copy(links = links or (1 shl dir.ordinal))

    fun cutFrom(dir: Direction): Segment = copy(links = links and (1 shl dir.ordinal).inv())

    /** This segment having seen [packet] go past. Reads it; does not consume it. */
    fun reading(packet: Packet): Segment {
        if (!isGauge) return this
        val dominant = packet.contents.dominant ?: return this
        val mass = packet.mass
        return copy(
            lastForm = (packet as? SolidPacket)?.form,
            lastDominant = dominant,
            lastPurity = if (mass == 0L) 0 else (packet.contents[dominant] * SignalField.FULL / mass).toInt(),
            lastMass = mass,
        )
    }
}
