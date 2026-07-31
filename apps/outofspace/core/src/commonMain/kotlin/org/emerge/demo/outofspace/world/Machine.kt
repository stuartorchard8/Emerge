package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket

/**
 * What kind of thing sits on a tile — the palette the player builds from.
 *
 * [conduit] is which layer it goes on. A null conduit means the **deck**: buildings, walls, the
 * things that occupy floor space and that heat and air care about. Everything else is a fitting on a
 * transport layer, and a fitting shares its tile with whatever is on the deck beneath it — which is
 * the whole point of layers, and the reason a rail can run underneath a smelter to reach its port.
 */
enum class MachineKind(val label: String, val conduit: Conduit? = null) {
    Rail("RAIL", Conduit.Rail),
    Gauge("GAUGE", Conduit.Rail),
    Bridge("BRIDGE", Conduit.Rail),
    Miner("MINER"),
    Processor("PROCESSOR"),
    Smelter("SMELTER"),
    Storage("STORAGE"),
    Sensor("SENSOR"),
    Vent("VENT"),
    Hull("HULL"),
    ;

    /** True for the things that take up floor space. */
    val isDeck: Boolean get() = conduit == null

    companion object {
        val ALL: List<MachineKind> = entries.toList()
        val DECK: List<MachineKind> = ALL.filter { it.isDeck }
    }
}

/**
 * A machine on a tile. Immutable — the reducer builds new ones rather than mutating, so a snapshot
 * of the world is a snapshot of the world.
 *
 * Every machine that produces something has a **facing**: its product leaves that side. The two with
 * a waste stream ([Processor], [Smelter]) put waste out the side *clockwise* of facing, which
 * mirrors the separate out/slag ports on the Godot originals and makes a refinery line read as a
 * spine with waste coming off it.
 *
 * Every machine also carries [wiring]: the `Σ(signal × weight)` rules that decide whether — and how
 * fast — it runs. New machines default to "wired to ALWAYS at full", so placing one just works and
 * wiring is something you add rather than something you must do.
 *
 * Rates are grams per second, turned into whole grams per tick by
 * [org.emerge.demo.outofspace.logistics.Rate] with the fraction kept in each machine's own `carry`.
 * Carry is machine state and not a global precisely so it survives a save.
 */
sealed interface Machine {
    val kind: MachineKind
    val wiring: Wiring
    fun withWiring(wiring: Wiring): Machine
}

/** A machine that faces somewhere. Its ports are laid out relative to that direction. */
sealed interface Directed : Machine {
    val facing: Direction
    fun rotated(): Machine
}

/**
 * A **bridge**: a three-tile span that takes material in on one side and puts it out on the other a
 * tick later, leaving everything between its two connections clear.
 *
 * It is how two runs of the same conduit cross. And it needs no special case anywhere in the network
 * code, because it is not one: it occupies **nothing on any layer**, so a run passing beneath it is
 * unconnected for the ordinary reason — the two share no port. "Hopping over" is what that looks
 * like from the outside; there is no hop in the model.
 *
 * The one packet of storage is what makes it a building rather than a wormhole. A tick of latency is
 * a real cost, so a bridge is a trade rather than a free win, and a line built out of them is slower
 * than one that did not need them.
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
    val held: Packet? = null,
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Bridge
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)

    companion object {
        /** Ticks between a conduit advancing. At 60 Hz this is 2.5 tiles a second. */
        const val STEP_TICKS = 6
    }
}

/**
 * An ore source: a stand-in for the mining and import the game does not model yet, and deliberately
 * shallow. When its output is blocked the buffer fills to [BUFFER_CAP] and it stops.
 */
data class Miner(
    override val facing: Direction,
    val composition: Mixture,
    val buffer: Resource = Resource(Form.Ore, Mixture.EMPTY),
    val carry: Long = 0L,
    val gramsPerSecond: Long = 1_000L,
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Miner
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)

    companion object {
        const val BUFFER_CAP = 5_000L
    }
}

/**
 * A mineral processor: the **concentrate leaves by the facing side** and the **tailings by the side
 * clockwise of it**. Never backwards — the input arrives that way.
 *
 * That direction contract is what makes a straight-through chain work: feed one processor's output
 * into the next and purity climbs (41% iron becomes 75%, then 100%), at the cost of throwing more
 * and more still-useful material into the tailings. Wasteful and effective, which is the trade the
 * machine exists to offer. Each stage needs somewhere for its tailings to go, or it backs up.
 *
 * Deliberately slower than a miner, so a naively built line jams and the player has to think about
 * throughput. That is the other lesson it teaches.
 */
data class Processor(
    override val facing: Direction,
    val input: Resource? = null,
    val product: Resource? = null,
    val tailings: Resource? = null,
    val carry: Long = 0L,
    val gramsPerSecond: Long = 500L,
    val efficiencyPermille: Int = 900,
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Processor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
}

/** A smelter: refined metal out the front, slag out the side. */
data class Smelter(
    override val facing: Direction,
    val input: Resource? = null,
    val refined: Resource? = null,
    val slag: Resource? = null,
    val carry: Long = 0L,
    val gramsPerSecond: Long = 500L,
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Smelter
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
}

/**
 * A buffer you can see the level of. Holds one form, releases it out the front while its RUN
 * activation is positive — so a storage wired to a sensor is a valve, and a storage wired to nothing
 * is a dead end that fills up.
 *
 * **Storage is also the vessel's inventory.** The global [Stockpile] construction draws on is the sum
 * of every storage aboard, computed fresh each tick — there is no separate act of "banking". That
 * keeps material in one place instead of two: what you can build with is exactly what you can walk
 * up to and point at, and blowing a hole beside a full warehouse costs you the contents.
 */
data class Storage(
    override val facing: Direction,
    val contents: Resource? = null,
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Storage
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)

    companion object {
        const val CAP = 20_000L
    }
}

/**
 * Watches the tile it faces and emits that machine's fullness on [channel].
 *
 * This is where signals come from, and the only reason wiring has anything to say. One sensor, one
 * channel, one reading — a machine that measured several things would need a UI to say which.
 */
data class Sensor(
    override val facing: Direction,
    val channel: Channel = Channel.Red,
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Sensor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
}

/** A vent: throws material overboard. Somewhere for slag to go that is not "jam the line". */
data class Vent(
    val ventedGrams: Long = 0L,
    override val wiring: Wiring = Wiring.RUNNING,
) : Machine {
    override val kind: MachineKind get() = MachineKind.Vent
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
}

/**
 * A wall. It does nothing, which is the point: it is the only thing that separates inside from
 * outside, and everything about heat and air follows from where it is.
 *
 * Hull is a machine rather than a separate "paint the structure" mode purely so it reuses the whole
 * build/remove/inspect pipeline unchanged — placing a wall and placing a belt should not be two
 * different verbs. [StructureMap] derives the enclosed space from wherever these end up.
 */
data class Hull(
    override val wiring: Wiring = Wiring.RUNNING,
) : Machine {
    override val kind: MachineKind get() = MachineKind.Hull
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
}

/** Machine input buffers hold this much before they stop accepting. */
const val MACHINE_BUFFER_CAP = 4_000L

/**
 * And output buffers hold this much before the machine stops *running*.
 *
 * Without this a processor whose waste side is blocked keeps working and hoards its tailings
 * indefinitely — tens of kilograms inside one tile, invisibly. Capping it makes a blocked output
 * back up into the input and then up the belt behind it, which is the same way every other blockage
 * in the game behaves: visibly, and starting at the thing that is actually stuck.
 */
const val MACHINE_OUTPUT_CAP = 4_000L
