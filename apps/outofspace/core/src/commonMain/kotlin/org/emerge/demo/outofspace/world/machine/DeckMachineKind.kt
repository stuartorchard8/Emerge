package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Conduit

/**
 * Machine kinds that take up deck space, storing their matter and energy in the deck layer.
 */
enum class DeckMachineKind(val label: String, val isPermeable: Boolean = false) {
    Hull("HULL"),
    Airlock("AIRLOCK"),
    Vent("VENT"),
    Storage("STORAGE"),
    Sensor("SENSOR"),
    KeyInput("BUTTON", isPermeable = true),
    Pump("PUMP", isPermeable = true),
    Thruster("THRUSTER", isPermeable = true),
    Processor("PROCESSOR"),
    ThermalDecomposer("THERMAL DECOMPOSER", isPermeable = true),
    Extractor("EXTRACTOR", isPermeable = true),

    /**
     * Three tiles end to end, and the only kind whose footprint is a line rather than a square —
     * see `DeckMachineKind.span`.
     *
     * Permeable, because a bridge is a gantry and not a block: it is mostly the air under it, so it
     * divides no room and displaces no gas. What it *does* claim is the floor, which is the whole
     * point of it being here — a bridge can no longer be stacked on another building or on another
     * bridge, so crossing a run costs three tiles of deck.
     */
    Bridge("BRIDGE", isPermeable = true),

    /**
     * An instrument standing over a run, reading what goes past. Permeable: a belt with a gauge on
     * it is still a corridor.
     */
    Gauge("GAUGE", isPermeable = true),

    /**
     * An opening between the pipe under it and the room it stands in. Permeable, and that is not a
     * convenience — a valve that displaced the air out of its own tile would open onto the vacuum it
     * had just made. See [Valve].
     */
    Valve("VALVE", isPermeable = true),
    ;

    companion object {
        val ALL: List<DeckMachineKind> = entries.toList()
    }
}
