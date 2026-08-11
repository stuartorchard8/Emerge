package org.emerge.demo.outofspace.world

/**
 * What kind of thing sits on a tile — the palette the player builds from.
 *
 * [conduit] is which layer it goes on. A null conduit means the **deck**: buildings, walls, the
 * things that occupy floor space and that heat and air care about. Everything else is a fitting on a
 * transport layer, and a fitting shares its tile with whatever is on the deck beneath it — which is
 * the whole point of layers, and the reason a rail can run underneath a smelter to reach its port.
 */
enum class MachineKind(val label: String, val conduit: Conduit? = null, val isPermeable: Boolean = false) {
    Rail("RAIL", Conduit.Rail),
    Gauge("GAUGE", Conduit.Rail),
    Pipe("PIPE", Conduit.Pipe),
    Bridge("BRIDGE", Conduit.Rail),
    Extractor("EXTRACTOR", isPermeable=true),
    Processor("PROCESSOR"),
    Vaporizer("VAPORIZER", isPermeable=true),
    Smelter("SMELTER"),
    Storage("STORAGE"),
    Sensor("SENSOR"),
    KeyInput("BUTTON"),
    Vent("VENT"),
    Valve("VALVE", Conduit.Pipe),
    Wire("WIRE", Conduit.Signal),
    Pump("PUMP", isPermeable=true),
    Hull("HULL"),
    Airlock("AIRLOCK"),
    ;

    /** True for the things that take up floor space. */
    val isDeck: Boolean get() = conduit == null

    companion object {
        val ALL: List<MachineKind> = entries.toList()
        val DECK: List<MachineKind> = ALL.filter { it.isDeck }
    }
}
