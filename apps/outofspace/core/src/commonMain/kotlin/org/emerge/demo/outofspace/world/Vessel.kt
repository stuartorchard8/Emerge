package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * The global construction inventory: how much of each [Form] the vessel has to build with, and what
 * it is made of.
 *
 * **This is a view over the vessel's [Storage] machines, not an account of its own.** Material is
 * available for construction because it is sitting in a warehouse somewhere aboard, and it stops
 * being available the moment that warehouse is emptied, moved or breached. The earlier design had a
 * central node that absorbed deliveries into a separate tally, which meant matter existed in one of
 * two mutually exclusive places and the conservation check had to name both; deriving it instead
 * removes the seam entirely — there is no act of "banking", only of storing.
 *
 * Composition survives storage — a tank of steel smelted from filthy ore is still filthy, and
 * whatever gets built from it inherits that. It would be much easier to reduce everything to a count
 * of items here, and it would throw away the point of the chemistry.
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

    override fun equals(other: Any?): Boolean =
        this === other || (other is Stockpile && byForm.contentEquals(other.byForm))

    override fun hashCode(): Int = byForm.contentHashCode()

    override fun toString(): String = "Stockpile(${entries().joinToString { "${it.first}=${it.second.total}g" }})"

    companion object {
        val EMPTY: Stockpile = Stockpile(Array(Form.ALL.size) { Mixture.EMPTY })

        /** Everything sitting in every storage aboard, gathered by form. */
        fun of(machines: List<Machine?>): Stockpile {
            var any = false
            val byForm = Array(Form.ALL.size) { Mixture.EMPTY }
            for (m in machines) {
                val held = (m as? Storage)?.contents ?: continue
                if (held.isEmpty) continue
                byForm[held.form.ordinal] = byForm[held.form.ordinal] + held.mixture
                any = true
            }
            return if (any) Stockpile(byForm) else EMPTY
        }
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
 * it legitimately arrives, so `mined == in-world + vented` must hold on every tick. That invariant
 * catches an entire category of logistics bug at once.
 *
 * There is no separate "banked" term any more: the [Stockpile] is derived from the storages, so what
 * it holds is already counted in [inTransitGrams] and adding it again would double-count.
 */
data class VesselState(
    val grid: Grid,
    /** The deck: buildings, walls, the things that take up floor space. */
    val machines: List<Machine?>,
    /**
     * The rail layer — one segment per tile, sharing tiles freely with the deck beneath.
     *
     * A separate list rather than a second thing in `machines`, because that is what a layer *is*:
     * track running under a smelter and the smelter itself are both real, both at that tile, and
     * neither is in the other's way. Structure, heat and air never look here.
     */
    val rails: List<Segment?> = List(machines.size) { null },
    /** Bridges, stored at their middle tile. They occupy nothing, so they are not in [occupancy]. */
    val bridges: List<Bridge?> = List(machines.size) { null },
    /** Which way each fork last sent material — see [Diverters]. */
    val diverters: Diverters = Diverters.EMPTY,
    val gravity: Frac2 = DEFAULT_GRAVITY,
    /** Loose material lying on the deck — see [Debris]. Part of "aboard" for conservation purposes. */
    val debris: Debris = Debris.EMPTY,
    val tick: Long = 0L,
    val minedGrams: Long = 0L,
    val ventedGrams: Long = 0L,
    /**
     * Cumulative joules put into the world by machines doing work, and cumulative joules radiated
     * away to space. The thermal counterpart of [minedGrams] and [ventedGrams], and they buy the
     * same thing: `stored + radiated − generated` must never move, so an energy leak is one
     * assertion away rather than a mystery.
     */
    val generatedJoules: Long = 0L,
    val radiatedJoules: Long = 0L,
    /** Cumulative grams of atmosphere lost to space. Air's counterpart to [radiatedJoules]. */
    val airVentedGrams: Long = 0L,
    /**
     * The channel values computed this tick. Kept in the snapshot rather than recomputed by the
     * renderer so that what is drawn is exactly what the sim acted on — and so a machine can be
     * drawn dimmed when its activation is zero, which is the answer to "why has this stopped".
     */
    val signals: Signals = Signals.build { },
    /** Derived from where the hull is, every tick — see [StructureMap]. */
    val structure: StructureMap = StructureMap.derive(grid, machines),
    /** Which tiles each machine covers, derived every tick — see [Occupancy]. */
    val occupancy: Occupancy = Occupancy.derive(grid, machines),
    val heat: HeatField = HeatField.ambient(grid, StructureMap.derive(grid, machines), Occupancy.derive(grid, machines)),
    /**
     * The energy the world started with. Fixed at construction so `stored + radiated − generated`
     * has something to be compared against — the thermal twin of the mass balance.
     */
    val baselineJoules: Long = heat.totalJoules,
    val air: AirField = AirField.ambient(grid, StructureMap.derive(grid, machines)),
    /**
     * The air the world started with. Solids and gases never interconvert, so they get separate
     * ledgers — `atmosphere + airVented == baselineAir` is a cleaner statement than folding gas into
     * the ore balance, and a break in one does not obscure the other.
     */
    val baselineAirGrams: Long = air.totalGrams,
) {
    init {
        require(machines.size == grid.size) { "machine list is ${machines.size}, grid holds ${grid.size}" }
    }

    /**
     * What the vessel can build with: the contents of every storage aboard.
     *
     * Derived rather than stored, for the same reason [structure] is — a cached copy is one more
     * thing that can disagree with the world, and this is cheap to fold.
     */
    val stockpile: Stockpile get() = Stockpile.of(machines)

    /** Temperature of a tile in kelvin, accounting for what is in it. */
    fun kelvinAt(index: Int): Int =
        heat.kelvinAt(index, HeatField.capacityOf(structure, occupancy, index))

    /** The machine covering a tile, wherever its centre happens to be. */
    fun machineCovering(index: Int): Machine? = machines.getOrNull(occupancy[index])

    /** The rail segment on a tile, if the layer has one there. */
    fun railAt(index: Int): Segment? = rails.getOrNull(index)

    /**
     * Every port any building or bridge exposes, keyed by the tile it sits on.
     *
     * Derived rather than stored, like everything else structural. Bridges are folded in here rather
     * than handled separately, which is the whole reason they need no special case: to the network a
     * bridge is a thing with an input port and an output port, exactly like a smelter.
     */
    fun portsByTile(conduit: Conduit): Map<Int, List<Port>> {
        val out = HashMap<Int, MutableList<Port>>()
        fun add(port: Port) {
            if (port.conduit == conduit) out.getOrPut(port.tile) { mutableListOf() }.add(port)
        }
        for (i in machines.indices) {
            val m = machines[i] ?: continue
            for (port in portsOf(grid, m, i)) add(port)
        }
        for (i in bridges.indices) {
            val b = bridges[i] ?: continue
            for (port in portsOf(grid, b, i)) add(port)
        }
        return out
    }

    /** Every connection point of the machine stored at [index]. */
    fun portsAt(index: Int): List<Port> {
        val m = machines.getOrNull(index) ?: return emptyList()
        return portsOf(grid, m, index)
    }

    val storedJoules: Long get() = heat.totalJoules

    /** Total atmosphere still aboard. */
    val atmosphereGrams: Long get() = air.totalGrams

    /** Pressure of a tile as a percentage of one atmosphere, for readouts. */
    fun pressurePercentAt(index: Int): Int =
        (air.pressureAt(index) * 100 / AirField.AMBIENT_AIR.total).toInt()

    operator fun get(index: Int): Machine? = machines.getOrNull(index)
    operator fun get(x: Int, y: Int): Machine? = if (grid.inBounds(x, y)) machines[grid.index(x, y)] else null

    /**
     * Every gram still aboard: in belts, in machine buffers, and lying loose on the deck.
     *
     * Debris counts. Taking a machine apart moves its contents from one term of this sum to another
     * rather than removing them, which is exactly why dismantling stopped reading as a leak.
     */
    val inTransitGrams: Long
        get() {
            var sum = debris.totalGrams
            for (m in machines) sum += massIn(m)
            for (r in rails) sum += r?.held?.mass ?: 0L
            for (b in bridges) sum += b?.held?.mass ?: 0L
            return sum
        }

    /** Just the loose material, for the readout that distinguishes "stored" from "spilled". */
    val debrisGrams: Long get() = debris.totalGrams

    fun withMachine(index: Int, machine: Machine?): VesselState =
        copy(machines = machines.toMutableList().also { it[index] = machine })

    companion object {
        /** One g, straight down the screen. A constant *value*, not a constant in the code. */
        val DEFAULT_GRAVITY: Frac2 = Frac2(Frac(0L, 1), Frac(1L, 1))

        fun empty(grid: Grid): VesselState = VesselState(grid, List(grid.size) { null })
    }
}

/**
 * What falls on the floor when a machine is taken apart: everything it was holding, keeping forms
 * separate. Defined in terms of [contentsBreakdown] so there is exactly one list of "where a machine
 * keeps things" — a second one would drift, and the drift would look like a conservation bug.
 */
fun spoilsOf(machine: Machine?): List<Resource> =
    contentsBreakdown(machine).map { it.second }.filter { !it.isEmpty }

/** Total mass held by one machine, wherever it keeps it. Used for world-wide conservation checks. */
fun massIn(machine: Machine?): Long = when (machine) {
    null -> 0L
    is Bridge -> machine.held?.mass ?: 0L
    is Miner -> machine.buffer.mass
    is Processor -> (machine.input?.mass ?: 0L) + (machine.product?.mass ?: 0L) + (machine.tailings?.mass ?: 0L)
    is Smelter -> (machine.input?.mass ?: 0L) + (machine.refined?.mass ?: 0L) + (machine.slag?.mass ?: 0L)
    is Storage -> machine.contents?.mass ?: 0L
    is Sensor -> 0L
    is Hull -> 0L
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
    is Bridge -> if (machine.held != null) Signals.FULL else 0
    is Miner -> (machine.buffer.mass * Signals.FULL / Miner.BUFFER_CAP).toInt()
    is Processor -> (massIn(machine) * Signals.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is Smelter -> (massIn(machine) * Signals.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is Storage -> ((machine.contents?.mass ?: 0L) * Signals.FULL / Storage.CAP).toInt()
    is Sensor -> 0
    is Hull -> 0
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
    is Bridge -> listOfNotNull(
        machine.held?.let { p ->
            val form = (p as? org.emerge.demo.outofspace.logistics.SolidPacket)?.form ?: Form.Ore
            "IN TRANSIT" to Resource(form, p.contents)
        },
    )
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
    is Storage -> listOfNotNull(machine.contents?.let { "STORED" to it })
    is Sensor, is Vent, is Hull -> emptyList()
}

/** Everything a machine holds, species by species — the finer-grained version of [massIn]. */
fun contentsOf(machine: Machine?): Mixture = when (machine) {
    null -> Mixture.EMPTY
    is Bridge -> machine.held?.contents ?: Mixture.EMPTY
    is Miner -> machine.buffer.mixture
    is Processor -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.product?.mixture ?: Mixture.EMPTY) + (machine.tailings?.mixture ?: Mixture.EMPTY)
    is Smelter -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.refined?.mixture ?: Mixture.EMPTY) + (machine.slag?.mixture ?: Mixture.EMPTY)
    is Storage -> machine.contents?.mixture ?: Mixture.EMPTY
    is Sensor -> Mixture.EMPTY
    is Hull -> Mixture.EMPTY
    is Vent -> Mixture.EMPTY
}
