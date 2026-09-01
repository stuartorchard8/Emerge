package org.emerge.demo.outofspace.world

/**
 * What a machine can be told to do. [Run] is a *switch*, not a throttle: it says whether the machine
 * works this tick and nothing about how hard. It was a throttle once, scaled by the strength of the
 * signal driving it — see [Wiring] for why that went, and where the analogue went instead.
 *
 * The map-of-actions shape is kept from the Godot `action_triggers` grammar so that adding a second
 * action later is adding an enum entry rather than reworking the wiring.
 */
enum class Action(val label: String) {
    Run("RUN"),
    Need("NEED"),
}
