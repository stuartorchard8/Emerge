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
    Gauge("GAUGE", Conduit.Rail),
    Pipe("PIPE", Conduit.Pipe),
    Bridge("BRIDGE", Conduit.Rail),
    Valve("VALVE", Conduit.Pipe),
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
    ;

    companion object {
        val ALL: List<DeckMachineKind> = entries.toList()
    }
}
