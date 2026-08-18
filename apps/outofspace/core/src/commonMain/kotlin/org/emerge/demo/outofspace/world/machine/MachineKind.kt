package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Conduit

/**
 * What kind of thing sits on a tile — the palette the player builds from.
 *
 * ⚠️ **Everything here is a fitting on a transport layer now**, and every member names its
 * [conduit]. Anything that takes up floor space is a [DeckMachineKind] — that is the whole of the
 * distinction, and it is the one Stu drew: a deck machine forbids another deck machine its space,
 * which is what makes one casing per tile a representation the deck layer can hold. A fitting shares
 * its tile with whatever is on the deck beneath it, which is why a rail can run underneath a smelter
 * to reach its port.
 *
 * [Bridge] is the odd one and stays here on purpose: it spans three tiles and occupies none of them.
 */
enum class MachineKind(val label: String, val conduit: Conduit? = null, val isPermeable: Boolean = false) {
    Rail("RAIL", Conduit.Rail),
    Pipe("PIPE", Conduit.Pipe),
    Wire("WIRE", Conduit.Signal),
    ;

    companion object {
        val ALL: List<MachineKind> = entries.toList()
    }
}
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
    Vaporizer("VAPORIZER", isPermeable = true),
    Thruster("THRUSTER", isPermeable = true),
    Processor("PROCESSOR"),
    ThermalDecomposer("THERMAL DECOMPOSER", isPermeable = true),
    Smelter("SMELTER"),
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
