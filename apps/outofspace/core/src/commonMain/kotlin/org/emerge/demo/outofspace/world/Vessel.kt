package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.demo.outofspace.world.fluid.AMBIENT_PRESSURE
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.FlowField
import org.emerge.demo.outofspace.world.fluid.MomentumField
import org.emerge.demo.outofspace.world.fluid.tileMass
import org.emerge.sim.core.physics.primitives.Frac2

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
    /**
     * What moved where during the tick that produced this state, for the renderer — see [Motion].
     *
     * Presentation only: nothing in the sim reads it and the save does not carry it, so a loaded
     * world starts still and is animated again from its first tick. It rides in the snapshot rather
     * than being worked out by the renderer because only the mover knows which of a tile's
     * neighbours a packet came from.
     */
    val motion: Motion = Motion.NONE,
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
     * How the air is moving: momentum on the faces between tiles — see [MomentumField].
     *
     * In state rather than derived, because it is the fluid's memory. A room does not stop being
     * draughty between ticks, and an exhaust plume that had to be recomputed from pressure every
     * tick would have no inertia and so could never be a jet.
     */
    val momentum: MomentumField = MomentumField.still(EdgeGrid(grid)),
    /**
     * Cumulative impulse the air has delivered to the ship, and cumulative momentum that has gone
     * overboard with escaping gas.
     *
     * The vessel does not move yet — flight is Phase 5. These are here now because they are what
     * makes a breach or an exhaust *mean* something, and because a thrust figure that has been
     * accumulating correctly since the first tick is far easier to trust than one bolted on later.
     * In steady flight the two should mirror each other: what pushes the ship is what the ship
     * pushed against.
     */
    val vesselImpulseX: Long = 0L,
    val vesselImpulseY: Long = 0L,
    val exhaustMomentumX: Long = 0L,
    val exhaustMomentumY: Long = 0L,
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

    /**
     * Where the air is going, tile by tile — see [FlowField].
     *
     * Presentation and inspection only; the sim reads [momentum] directly. Cached because the flow
     * overlay wants the whole field every frame while the state behind it only changes once a tick,
     * and rebuilding it sixty times for one tick's worth of answer would be sixty times the work for
     * the same picture.
     */
    val flow: FlowField by lazy { FlowField.derive(EdgeGrid(grid), momentum, tileMass(grid.size, air.copyGrams())) }

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
        (air.pressureAt(index) * 100 / AMBIENT_PRESSURE).toInt()

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
            for (b in bridges) sum += b?.mass ?: 0L
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
    is Bridge -> machine.mass
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
    is Bridge -> machine.carried.size * Signals.FULL / Bridge.SLOTS
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
    // Slot by slot, input end first: "which end of the span is it on" is the only thing worth
    // knowing about a bridge, and one lump labelled IN TRANSIT could not say it.
    is Bridge -> listOf("IN" to machine.entry, "SPAN" to machine.middle, "OUT" to machine.exit)
        .mapNotNull { (label, p) ->
            if (p == null) null else {
                val form = (p as? org.emerge.demo.outofspace.logistics.SolidPacket)?.form ?: Form.Ore
                label to Resource(form, p.contents)
            }
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
    is Storage -> listOfNotNull(machine.contents?.let { "STORED" to it })
    is Sensor, is Vent, is Hull -> emptyList()
}

/** Everything a machine holds, species by species — the finer-grained version of [massIn]. */
fun contentsOf(machine: Machine?): Mixture = when (machine) {
    null -> Mixture.EMPTY
    is Bridge -> machine.carried.fold(Mixture.EMPTY) { acc, p -> acc + p.contents }
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
