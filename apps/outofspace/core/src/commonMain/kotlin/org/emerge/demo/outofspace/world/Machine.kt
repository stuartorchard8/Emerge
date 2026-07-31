package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket

/** What kind of thing sits on a tile — the palette the player builds from. */
enum class MachineKind(val label: String) {
    Belt("BELT"),
    Miner("MINER"),
    Processor("PROCESSOR"),
    Smelter("SMELTER"),
    Fabricator("FABRICATOR"),
    Storage("STORAGE"),
    Sensor("SENSOR"),
    Analyzer("ANALYZER"),
    Vent("VENT"),
    Hull("HULL"),
    ;

    companion object {
        val ALL: List<MachineKind> = entries.toList()
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

/** A machine that faces somewhere. Belts move that way; producers push their product that way. */
sealed interface Directed : Machine {
    val facing: Direction
    fun rotated(): Machine
}

/**
 * A conveyor. [slots] are ordered **head first**: `slots[0]` is the end packets leave from, in the
 * direction of [facing]; the last slot is where new packets arrive.
 *
 * Slots rather than a throughput number is the whole point — when the line backs up the slots fill
 * from the head, and you can *see* the jam and where it starts. A belt modelled as a rate would go
 * quietly slow instead.
 */
data class Belt(
    override val facing: Direction,
    val slots: List<Packet?> = List(SLOTS) { null },
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Belt
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)

    val isFull: Boolean get() = slots.all { it != null }
    val occupancy: Int get() = slots.count { it != null }

    companion object {
        /** Packets visible on one belt tile. Four reads clearly at the zoom the game runs at. */
        const val SLOTS = 4

        /** Ticks between advances. At 60 Hz this is 10 slots/second — 2.5 tiles/second. */
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
 * A fabricator: combines two forms into the one they make, per the crafting tree.
 *
 * It holds at most [MAX_INPUTS] distinct forms and needs no recipe selected — the tree is binary, so
 * having two things that make a third *is* the instruction. A fabricator holding two forms that make
 * nothing simply sits there, which is a legible failure.
 */
data class Fabricator(
    override val facing: Direction,
    val inputs: List<Resource> = emptyList(),
    val output: Resource? = null,
    val carry: Long = 0L,
    val gramsPerSecond: Long = 400L,
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Fabricator
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)

    companion object {
        const val MAX_INPUTS = 2
        const val INPUT_CAP = 8_000L
    }
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

/**
 * An inline assay: material passes through it, and it reports what went by.
 *
 * It holds one packet at a time, reads the fraction of whatever species dominates it, then hands it
 * on — a belt tile that measures. The reading **persists** after the packet leaves, so the signal is
 * a stable "the last thing through here was 41% iron" rather than a flicker, and so the tile can
 * still tell you what it saw when the line is idle.
 *
 * It exists because ore is a mixture and nothing else in the world says so out loud. Wired to a
 * channel it also lets purity drive machinery — running a processor harder on dirtier ore, say.
 */
data class Analyzer(
    override val facing: Direction,
    val channel: Channel = Channel.Amber,
    /** The packet currently inside, if any. One at a time keeps it a measuring belt, not a buffer. */
    val holding: Packet? = null,
    val lastForm: Form? = null,
    val lastDominant: Species? = null,
    /** The dominant species' share of the last thing through, in permille. */
    val lastPurity: Int = 0,
    val lastMass: Long = 0L,
    override val wiring: Wiring = Wiring.RUNNING,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Analyzer
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)

    /** Reads [packet] without consuming it — the analysis is the whole job. */
    fun reading(packet: Packet): Analyzer {
        val dominant = packet.contents.dominant ?: return copy(holding = packet)
        val mass = packet.mass
        return copy(
            holding = packet,
            lastForm = (packet as? SolidPacket)?.form,
            lastDominant = dominant,
            lastPurity = if (mass == 0L) 0 else (packet.contents[dominant] * Signals.FULL / mass).toInt(),
            lastMass = mass,
        )
    }
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
