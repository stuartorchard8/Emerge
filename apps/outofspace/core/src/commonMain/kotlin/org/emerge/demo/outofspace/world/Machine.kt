package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.logistics.Packet

/** What kind of thing sits on a tile — the palette the player builds from. */
enum class MachineKind(val label: String) {
    Belt("BELT"),
    Miner("MINER"),
    Processor("PROCESSOR"),
    Smelter("SMELTER"),
    Node("NODE"),
    Vent("VENT"),
    ;

    companion object {
        val ALL: List<MachineKind> = entries.toList()
    }
}

/**
 * A machine on a tile. Immutable — the reducer builds new ones rather than mutating, so a snapshot
 * of the world is a snapshot of the world.
 *
 * Every machine that produces something has a **facing**: its product leaves that side. The two
 * machines with a waste stream ([Processor], [Smelter]) put waste out the *opposite* side, which
 * mirrors the separate out/slag ports on the Godot originals and means a refinery line reads as a
 * spine with waste coming off the back.
 *
 * Rates are grams per second and are turned into whole grams per tick by
 * [org.emerge.demo.outofspace.logistics.Rate], with the fraction kept in each machine's own [carry].
 * That is why carry is part of machine state and not a global: it has to survive a save.
 */
sealed interface Machine {
    val kind: MachineKind
}

/** A machine that faces somewhere. Belts move that way; producers push their product that way. */
sealed interface Directed : Machine {
    val facing: Direction
    fun rotated(): Machine
}

/**
 * A conveyor. [slots] are ordered **head first**: `slots[0]` is the end the packets leave from, in
 * the direction of [facing]; the last slot is where new packets arrive.
 *
 * Slots rather than a throughput number is the whole point — when the line backs up, the slots fill
 * from the head, and you can *see* the jam and where it starts. A belt modelled as a rate would go
 * quietly slow instead.
 */
data class Belt(
    override val facing: Direction,
    val slots: List<Packet?> = List(SLOTS) { null },
) : Directed {
    override val kind: MachineKind get() = MachineKind.Belt
    override fun rotated(): Machine = copy(facing = facing.clockwise)

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
 * An ore source: stands in for the mining/import that Phase 2 does not model yet.
 *
 * [composition] is the ore body it draws from, as a per-kilogram recipe; the buffer accumulates at
 * [gramsPerSecond] and ships a packet whenever there is a packet's worth. When its output is blocked
 * the buffer fills to [BUFFER_CAP] and the miner simply stops — a jam that starts at the source.
 */
data class Miner(
    override val facing: Direction,
    val composition: Mixture,
    val buffer: Resource = Resource(Form.Ore, Mixture.EMPTY),
    val carry: Long = 0L,
    val gramsPerSecond: Long = 1_000L,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Miner
    override fun rotated(): Machine = copy(facing = facing.clockwise)

    companion object {
        const val BUFFER_CAP = 5_000L
    }
}

/**
 * A mineral processor: concentrates its input, product out the front, tailings out the back.
 *
 * Deliberately slower than a miner so that a naively built line jams and the player has to think
 * about throughput. That is the lesson the machine exists to teach.
 */
data class Processor(
    override val facing: Direction,
    val input: Resource? = null,
    val product: Resource? = null,
    val tailings: Resource? = null,
    val carry: Long = 0L,
    val gramsPerSecond: Long = 500L,
    val efficiencyPermille: Int = 900,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Processor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
}

/** A smelter: refined metal out the front, slag out the back. */
data class Smelter(
    override val facing: Direction,
    val input: Resource? = null,
    val refined: Resource? = null,
    val slag: Resource? = null,
    val carry: Long = 0L,
    val gramsPerSecond: Long = 500L,
) : Directed {
    override val kind: MachineKind get() = MachineKind.Smelter
    override fun rotated(): Machine = copy(facing = facing.clockwise)
}

/**
 * The central node. Anything delivered here leaves the tile network and joins the global
 * [Stockpile], from which construction anywhere on the vessel draws.
 *
 * This is the seam between *logistics* and *inventory*: up to here material has a position, and
 * after it material is only a quantity. Having exactly one such seam is what keeps the game from
 * needing a hauling system.
 */
data class Node(val absorbedGrams: Long = 0L) : Machine {
    override val kind: MachineKind get() = MachineKind.Node
}

/** A vent: throws material overboard. Somewhere for slag to go that is not "jam the line". */
data class Vent(val ventedGrams: Long = 0L) : Machine {
    override val kind: MachineKind get() = MachineKind.Vent
}

/** Machine input buffers hold this much before they stop accepting. */
const val MACHINE_BUFFER_CAP = 4_000L
