package org.emerge.demo.outofspace.world

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

    /**
     * How much thermal energy this machine is holding, in the millijoules [Material] documents.
     *
     * On the machine rather than in a field beside it, and that is load-bearing — see [Body]. A
     * parallel array keyed by tile would be desynchronised by `copy(machines = …)`, which is the
     * operation every save load, every fixture and every player edit goes through, and the symptom
     * is a freshly laid rail inheriting the energy of the furnace that used to stand there. Here,
     * a machine's capacity and a machine's energy are properties of the same value and cannot come
     * apart.
     *
     * Defaults to room temperature for the machine's own footprint and material, so placing one
     * needs no separate act of initialisation.
     */
    val joules: Long

    fun withWiring(wiring: Wiring): Machine

    fun withJoules(joules: Long): Machine
}

/**
 * How many tiles' worth of material a machine is made of.
 *
 * The footprint, squared — except for a bridge, which claims no floor space at all and is
 * nonetheless three tiles of metal spanning three tiles of room. Deliberately **not** derived from
 * the clipped [coveredTiles] of wherever it stands: what a thing is made of does not change when it
 * is built near the edge of the grid, and a capacity that varied with position would make an
 * identical machine hold a different amount of heat depending on where you put it.
 */
val MachineKind.thermalTiles: Int
    get() = if (this == MachineKind.Bridge) 3 else size * size

/** What a freshly built machine of this kind holds: all of it, at room temperature. */
fun ambientJoules(kind: MachineKind): Long =
    kind.capacityPerTile * kind.thermalTiles * Temperature.AMBIENT_KELVIN

/** A machine that faces somewhere. Its ports are laid out relative to that direction. */
sealed interface Directed : Machine {
    val facing: Direction
    fun rotated(): Machine
}

/**
 * Machine input buffers hold this much before they stop accepting.
 *
 * **Derivation**: four tonnes — **32 ticks** of a 125 kg/tick machine's throughput, so a machine can
 * run for a good few seconds on a full buffer while its feed is interrupted.
 *
 * ⚠️ Sized in *ticks of throughput*, not in belt-loads, and that distinction has already bitten
 * once. Written as `4 × PACKET_GRAMS` it silently shrank tenfold when the belt-load went from a
 * tonne to 100 kg, leaving every machine with two ticks of buffer — enough that an extractor
 * stalled before its throttle could make any difference, which is a behaviour change nobody asked
 * for. A buffer's job is to decouple a machine from its supply *for a while*; the unit of "a while"
 * is ticks.
 */
val MACHINE_BUFFER_CAP = 4L * Budget.TONNE

/**
 * And output buffers hold this much before the machine stops *running*.
 *
 * Without this a processor whose waste side is blocked keeps working and hoards its tailings
 * indefinitely — tens of tonnes inside one tile, invisibly. Capping it makes a blocked output
 * back up into the input and then up the belt behind it, which is the same way every other blockage
 * in the game behaves: visibly, and starting at the thing that is actually stuck.
 *
 * **Derivation**: the same four tonnes as [MACHINE_BUFFER_CAP], and deliberately equal to it — a
 * machine that can hoard more output than input would drain its feed before it stalled.
 */
val MACHINE_OUTPUT_CAP = 4L * Budget.TONNE
