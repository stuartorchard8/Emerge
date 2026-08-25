package org.emerge.demo.outofspace.world.machine

/**
 * Machine kinds that take up deck space, storing their matter and energy in the deck layer.
 *
 * ### The two ways a machine can be in the way
 *
 * There used to be one flag, `isPermeable`, and it answered two questions that are not the same
 * one. A smelter standing inside the hull is a solid object an asteroid bounces off, *and* every
 * tile of it is open to the room's air — which is the whole reason it can rust, and the reason it
 * can cook the room it stands in. With one flag it had to be either a wall the air could not reach
 * or a plate a rock fell through, and neither is a smelter.
 *
 * - [preventAirflow] — gas can neither sit in this tile nor cross it. It is what
 *   [org.emerge.demo.outofspace.world.StructureMap] fills against, so it is what decides where a
 *   *room* is, and placing one displaces the air out of its own tiles. Almost nothing sets it: a
 *   wall and a shut airlock, and that is the list.
 * - [preventThoroughfare] — a rigid body can neither sit in this tile nor cross it. It defaults to
 *   [preventAirflow], because a thing that holds the air out is certainly solid, and the interesting
 *   kinds are the ones that set it on its own.
 */
enum class DeckMachineKind(
    val label: String,
    val preventAirflow: Boolean = false,
    val preventThoroughfare: Boolean = preventAirflow,
    /** Whether this machine holds its product when its RUN activation is zero. */
    val gatesOutput: Boolean = false,
) {
    Hull("HULL", preventAirflow = true),
    Airlock("AIRLOCK", preventAirflow = true),
    Vent("VENT"),
    Storage("STORAGE", preventThoroughfare = true, gatesOutput = true),
    Sensor("SENSOR"),
    KeyInput("BUTTON"),
    Pump("PUMP"),
    /**
     * A rocket motor: two tiles end to end, and the only kind whose anchor is **not** the middle of
     * its own footprint — see `FootprintShape.Nose`.
     *
     * The tile it is stored at is the chamber, which is what you feed; the second tile is the bell,
     * which juts out into the exhaust direction. Solid to a rock, permeable to gas, because a bell
     * that held the air out could not exhaust into anything.
     */
    Thruster("THRUSTER", preventThoroughfare = true),
    Processor("PROCESSOR", preventThoroughfare = true, gatesOutput = true),
    ThermalDecomposer("THERMAL DECOMPOSER", preventThoroughfare = true, gatesOutput = true),
    Extractor("EXTRACTOR", gatesOutput = true),

    /**
     * Three tiles end to end, and the only kind whose footprint is a line rather than a square —
     * see `FootprintShape.Span`.
     *
     * Permeable, because a bridge is a gantry and not a block: it is mostly the air under it, so it
     * divides no room and displaces no gas. What it *does* claim is the floor, which is the whole
     * point of it being here — a bridge can no longer be stacked on another building or on another
     * bridge, so crossing a run costs three tiles of deck.
     */
    Bridge("BRIDGE"),

    /**
     * An instrument standing over a run, reading what goes past. Permeable: a belt with a gauge on
     * it is still a corridor.
     */
    Gauge("GAUGE"),

    /**
     * An opening between the pipe under it and the room it stands in. Permeable, and that is not a
     * convenience — a valve that displaced the air out of its own tile would open onto the vacuum it
     * had just made. See [Valve].
     */
    Valve("VALVE"),
    ;

    companion object {
        val ALL: List<DeckMachineKind> = entries.toList()
    }
}
