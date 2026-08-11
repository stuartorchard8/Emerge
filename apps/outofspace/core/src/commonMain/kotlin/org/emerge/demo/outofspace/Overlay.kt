package org.emerge.demo.outofspace

/**
 * What the world is being looked at *through*.
 *
 * The fluid views are deliberately four rather than one, because the sim keeps four things apart that
 * a single "air" picture runs together, and every model correction so far has come from noticing that
 * two of them disagreed:
 *
 * - [Air] is **composition** — which gas, and how much of it.
 * - [Pressure] is **moles**, which is what the projection solves and what pushes.
 * - [Density] is **mass**, which is what gravity pulls on and what stratifies.
 * - [Flow] is **net movement** — the grams diffusion actually carries across a tile each tick, which
 *   is the only one of the four that is not a scalar and so is the only one a tint cannot show. It
 *   is measured from a diffusion pass rather than read off a stored velocity, because since the
 *   momentum solver was extracted there is no velocity to read.
 *
 * Pressure and density looking different is the whole reason `stratifyColumns` could be deleted, so
 * being able to put them side by side is not a luxury — it is how that claim gets checked.
 */
enum class Overlay(val label: String) {
    None("PLAIN"),
    Heat("HEAT"),
    Air("AIR"),
    Pressure("PRESSURE"),
    Density("DENSITY"),
    Flow("FLOW"),
    ;

    /** What `H` cycles to next. One key beats six, and the HUD has buttons for direct picks. */
    val next: Overlay get() = entries[(ordinal + 1) % entries.size]
}
