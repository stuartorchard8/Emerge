package org.emerge.demo.outofspace.world

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
