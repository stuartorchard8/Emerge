package org.emerge.demo.outofspace

/**
 * Whether the keyboard is building the vessel or flying it — see [OutofspaceController.mode].
 *
 * Two modes rather than a chord or a modifier key, because the two activities want the *same* keys
 * and want them comfortable: a hand rests on WASD to pan a view, and it rests on the same place to
 * fly. Sharing them under a modifier would make the good gesture the awkward one in both modes.
 */
enum class Mode(val label: String) {
    Build("BUILD"),
    Flight("FLIGHT"),
    ;

    val next: Mode get() = if (this == Build) Flight else Build

    /**
     * Which frame the camera holds still while in this mode.
     *
     * Derived rather than a separate setting the player toggles, because the mode **is** the choice:
     * picking Build is saying "I am laying pipe" and picking Flight is saying "I am flying", and
     * those are precisely the two questions the frame answers. A second control would only let the
     * player put the two into a combination neither activity wants.
     */
    val camera: CameraFrame get() = if (this == Build) CameraFrame.Grid else CameraFrame.World
}
