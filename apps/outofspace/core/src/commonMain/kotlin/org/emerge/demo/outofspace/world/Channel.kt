package org.emerge.demo.outofspace.world

/**
 * A signal channel — the vessel's wiring colours.
 *
 * A fixed palette rather than player-named strings, because naming things needs a keyboard and this
 * game has to work on a phone. Six colours is plenty for a vessel and they read at a glance, which
 * is the same reason ONI and Factorio use coloured wires and icons.
 *
 * [Always] is the constant: it reads 1000 permille forever and is what every machine is wired to by
 * default, so a freshly placed machine simply works and wiring is something you *add*.
 */
enum class Channel(val label: String, val color: Long) {
    Always("ALWAYS", 0x9AA4B4FFL),
    Red("RED", 0xD05A4AFFL),
    Green("GREEN", 0x5AC07AFFL),
    Blue("BLUE", 0x4A8AD0FFL),
    Amber("AMBER", 0xE0A93AFFL),
    Cyan("CYAN", 0x4AC0C8FFL),
    Violet("VIOLET", 0xA06AD0FFL),
    ;

    companion object {
        val ALL: List<Channel> = entries.toList()

        /** The channels a sensor may emit on — everything except the constant. */
        val EMITTABLE: List<Channel> = ALL.filter { it != Always }
    }
}
