package org.emerge.demo.outofspace

import org.emerge.sim.core.SimInput

/**
 * One player's input for one tick: what they built, and what they are holding down.
 *
 * The two are different kinds of thing and are carried differently on purpose. An [Edit] is an
 * **event** — it happened once, it must not happen twice, and replaying it replays the build.
 * [heldKeys] is a **level**: it is not something that happened, it is how the controls are right
 * now, and a tick with no input at all correctly means "nothing held". Modelling a held key as a
 * pair of press/release edits would make a dropped packet leave a thruster firing forever.
 *
 * A bitmask over [org.emerge.demo.outofspace.world.machine.InputKey], so it costs nothing to send every tick.
 */
data class OutofspaceInput(
    val edits: List<Edit> = emptyList(),
    val heldKeys: Int = 0,
) : SimInput {
    companion object {
        val EMPTY = OutofspaceInput()
    }
}
