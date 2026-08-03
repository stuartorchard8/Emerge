package org.emerge.demo.outofspace

/** What the world is being looked at *through*. */
enum class Overlay(val label: String) {
    None("PLAIN"),
    Heat("HEAT"),
    Air("AIR"),
    ;

    /** What `H` cycles to next. One key beats three, and the HUD has buttons for direct picks. */
    val next: Overlay get() = entries[(ordinal + 1) % entries.size]
}
