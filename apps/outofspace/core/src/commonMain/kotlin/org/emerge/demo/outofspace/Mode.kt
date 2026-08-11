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
}
