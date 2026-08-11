package org.emerge.demo.fluidlab.world

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
