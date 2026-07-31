package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * The global construction inventory: how much of each [Form] the vessel has banked, and what it is
 * made of.
 *
 * Composition survives being banked — a stockpile of steel smelted from filthy ore is still filthy,
 * and whatever gets built from it inherits that. It would be much easier to reduce everything to a
 * count of items here, and it would throw away the point of the chemistry.
 */
class Stockpile private constructor(private val byForm: Array<Mixture>) {

    operator fun get(form: Form): Mixture = byForm[form.ordinal]

    val totalGrams: Long get() {
        var sum = 0L
        for (m in byForm) sum += m.total
        return sum
    }

    /** Everything held, in [Form] declaration order so the UI never reshuffles itself. */
    fun entries(): List<Pair<Form, Mixture>> =
        Form.ALL.mapNotNull { f -> byForm[f.ordinal].takeIf { !it.isEmpty }?.let { f to it } }

    fun deposit(resource: Resource): Stockpile {
        if (resource.isEmpty) return this
        val next = byForm.copyOf()
        next[resource.form.ordinal] = next[resource.form.ordinal] + resource.mixture
        return Stockpile(next)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Stockpile && byForm.contentEquals(other.byForm))

    override fun hashCode(): Int = byForm.contentHashCode()

    override fun toString(): String = "Stockpile(${entries().joinToString { "${it.first}=${it.second.total}g" }})"

    companion object {
        val EMPTY: Stockpile = Stockpile(Array(Form.ALL.size) { Mixture.EMPTY })
    }
}

/**
 * The whole world at one instant: a tile grid of machines, the global stockpile, and the vessel's
 * own gravity.
 *
 * [gravity] is here — in state, as a vector — even though nothing reads it yet. Per the plan, that is
 * the cheap insurance that keeps acceleration-derived gravity a later decision rather than a
 * rewrite: no code is allowed to assume "down" is a constant or implied by array order.
 *
 * [ventedGrams] and [minedGrams] exist so conservation can be checked across the *whole world*, not
 * just one operation. A vent is the only place matter legitimately leaves and a miner the only place
 * it legitimately arrives, so `mined == in-world + banked + vented` must hold on every tick. That
 * invariant catches an entire category of logistics bug at once.
 */
data class VesselState(
    val grid: Grid,
    val machines: List<Machine?>,
    val stockpile: Stockpile = Stockpile.EMPTY,
    val gravity: Frac2 = DEFAULT_GRAVITY,
    val tick: Long = 0L,
    val minedGrams: Long = 0L,
    val ventedGrams: Long = 0L,
    /**
     * The channel values computed this tick. Kept in the snapshot rather than recomputed by the
     * renderer so that what is drawn is exactly what the sim acted on — and so a machine can be
     * drawn dimmed when its activation is zero, which is the answer to "why has this stopped".
     */
    val signals: Signals = Signals.build { },
) {
    init {
        require(machines.size == grid.size) { "machine list is ${machines.size}, grid holds ${grid.size}" }
    }

    operator fun get(index: Int): Machine? = machines.getOrNull(index)
    operator fun get(x: Int, y: Int): Machine? = if (grid.inBounds(x, y)) machines[grid.index(x, y)] else null

    /** Mass currently sitting in belts and machine buffers — everything the logistics network holds. */
    val inTransitGrams: Long
        get() {
            var sum = 0L
            for (m in machines) sum += massIn(m)
            return sum
        }

    fun withMachine(index: Int, machine: Machine?): VesselState =
        copy(machines = machines.toMutableList().also { it[index] = machine })

    companion object {
        /** One g, straight down the screen. A constant *value*, not a constant in the code. */
        val DEFAULT_GRAVITY: Frac2 = Frac2(Frac(0L, 1), Frac(1L, 1))

        fun empty(grid: Grid): VesselState = VesselState(grid, List(grid.size) { null })
    }
}

/** Total mass held by one machine, wherever it keeps it. Used for world-wide conservation checks. */
fun massIn(machine: Machine?): Long = when (machine) {
    null -> 0L
    is Belt -> machine.slots.sumOf { it?.mass ?: 0L }
    is Miner -> machine.buffer.mass
    is Processor -> (machine.input?.mass ?: 0L) + (machine.product?.mass ?: 0L) + (machine.tailings?.mass ?: 0L)
    is Smelter -> (machine.input?.mass ?: 0L) + (machine.refined?.mass ?: 0L) + (machine.slag?.mass ?: 0L)
    is Fabricator -> machine.inputs.sumOf { it.mass } + (machine.output?.mass ?: 0L)
    is Storage -> machine.contents?.mass ?: 0L
    is Analyzer -> machine.holding?.mass ?: 0L
    is Sensor -> 0L
    is Node -> 0L
    is Vent -> 0L
}

/**
 * How full a machine is, 0..1000 permille — the one number a [Sensor] reads.
 *
 * Every machine answers, so a sensor can be pointed at anything and mean something. The reference
 * capacity differs by kind (a belt's is its slots, a storage's is its tank), which is the point: the
 * question a sensor asks is "is this backing up?", not "how many grams".
 */
fun fullness(machine: Machine?): Int = when (machine) {
    null -> 0
    is Belt -> machine.occupancy * Signals.FULL / machine.slots.size
    is Miner -> (machine.buffer.mass * Signals.FULL / Miner.BUFFER_CAP).toInt()
    is Processor -> (massIn(machine) * Signals.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is Smelter -> (massIn(machine) * Signals.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is Fabricator -> (massIn(machine) * Signals.FULL / (Fabricator.INPUT_CAP * 2 + MACHINE_OUTPUT_CAP)).toInt()
    is Storage -> ((machine.contents?.mass ?: 0L) * Signals.FULL / Storage.CAP).toInt()
    is Analyzer -> if (machine.holding != null) Signals.FULL else 0
    is Sensor -> 0
    is Node -> 0
    is Vent -> 0
}.coerceIn(0, Signals.FULL)

/**
 * A machine's contents broken out by the buffer they sit in, for the inspector.
 *
 * Named buffers rather than one lump, because "this processor holds 6kg" is far less useful than
 * "3kg waiting, 2kg of concentrate, 1kg of tailings" — the second tells you which side is stuck.
 */
fun contentsBreakdown(machine: Machine?): List<Pair<String, Resource>> = when (machine) {
    null -> emptyList()
    is Belt -> machine.slots.mapIndexedNotNull { i, p ->
        val packet = p ?: return@mapIndexedNotNull null
        val form = (packet as? org.emerge.demo.outofspace.logistics.SolidPacket)?.form ?: Form.Ore
        "SLOT ${i + 1}" to Resource(form, packet.contents)
    }
    is Miner -> listOf("BUFFER" to machine.buffer)
    is Processor -> listOfNotNull(
        machine.input?.let { "INPUT" to it },
        machine.product?.let { "CONCENTRATE" to it },
        machine.tailings?.let { "TAILINGS" to it },
    )
    is Smelter -> listOfNotNull(
        machine.input?.let { "INPUT" to it },
        machine.refined?.let { "REFINED" to it },
        machine.slag?.let { "SLAG" to it },
    )
    is Fabricator -> machine.inputs.mapIndexed { i, r -> "INPUT ${i + 1}" to r } +
        listOfNotNull(machine.output?.let { "OUTPUT" to it })
    is Storage -> listOfNotNull(machine.contents?.let { "STORED" to it })
    is Analyzer -> listOfNotNull(
        machine.holding?.let { p ->
            val form = (p as? org.emerge.demo.outofspace.logistics.SolidPacket)?.form ?: Form.Ore
            "PASSING" to Resource(form, p.contents)
        },
    )
    is Sensor, is Node, is Vent -> emptyList()
}

/** Everything a machine holds, species by species — the finer-grained version of [massIn]. */
fun contentsOf(machine: Machine?): Mixture = when (machine) {
    null -> Mixture.EMPTY
    is Belt -> machine.slots.fold(Mixture.EMPTY) { acc, p -> if (p == null) acc else acc + p.contents }
    is Miner -> machine.buffer.mixture
    is Processor -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.product?.mixture ?: Mixture.EMPTY) + (machine.tailings?.mixture ?: Mixture.EMPTY)
    is Smelter -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.refined?.mixture ?: Mixture.EMPTY) + (machine.slag?.mixture ?: Mixture.EMPTY)
    is Fabricator -> machine.inputs.fold(machine.output?.mixture ?: Mixture.EMPTY) { acc, r -> acc + r.mixture }
    is Storage -> machine.contents?.mixture ?: Mixture.EMPTY
    is Analyzer -> machine.holding?.contents ?: Mixture.EMPTY
    is Sensor -> Mixture.EMPTY
    is Node -> Mixture.EMPTY
    is Vent -> Mixture.EMPTY
}
