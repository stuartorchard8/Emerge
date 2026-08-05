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
    Pipe("PIPE", Conduit.Pipe),
    Bridge("BRIDGE", Conduit.Rail),
    Extractor("EXTRACTOR"),
    Processor("PROCESSOR"),
    Smelter("SMELTER"),
    Storage("STORAGE"),
    Sensor("SENSOR"),
    Vent("VENT"),
    Valve("VALVE", Conduit.Pipe),
    Pump("PUMP"),
    Hull("HULL"),
    ;

    /** True for the things that take up floor space. */
    val isDeck: Boolean get() = conduit == null

    /**
     * True for a deck machine that air and rocks pass straight through.
     *
     * A separate question from [isDeck], and the [Extractor] is why: it claims floor space, so
     * nothing else can be built on it, and it is nonetheless a plate rather than a block. Air fills
     * it, [StructureMap] leaves it [Structure.Interior] and [overlapsHull] does not see it — which is
     * the only way a rock can come to be lying **on** the machine that eats it.
     */
    val isPermeable: Boolean get() = this == Extractor

    companion object {
        val ALL: List<MachineKind> = entries.toList()
        val DECK: List<MachineKind> = ALL.filter { it.isDeck }
    }
}
