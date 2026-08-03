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

/**
 * What a machine can be told to do. One entry today, deliberately: [Run] is a *throttle*, not a
 * switch — its magnitude scales the machine's rate, so a half-strength signal is a half-speed
 * machine and weights mean something beyond on and off.
 *
 * The map-of-actions shape is kept from the Godot `action_triggers` grammar so that adding a second
 * action later is adding an enum entry rather than reworking the wiring.
 */
enum class Action(val label: String) {
    Run("RUN"),
}
