package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachineKind

val DeckMachineKind.diameter: Int
    get() = when (this) {
        DeckMachineKind.Hull, DeckMachineKind.Airlock, DeckMachineKind.Vent -> 1
        // A room-sized installation, as it was on the machine list.
        DeckMachineKind.Storage -> 3
        DeckMachineKind.Sensor, DeckMachineKind.KeyInput, DeckMachineKind.Pump,
        DeckMachineKind.Thruster, DeckMachineKind.Gauge, DeckMachineKind.Valve,
        -> 1
        DeckMachineKind.Vaporizer, DeckMachineKind.Processor, DeckMachineKind.ThermalDecomposer -> 3
        // Five: it should dominate the deck it sits on, and its heat should have somewhere to be.
        DeckMachineKind.Extractor -> 5
        // Three tiles end to end. Only ever three *along* its facing — see [span].
        DeckMachineKind.Bridge -> 3
    }

/**
 * Whether the footprint is a **line** along the machine's facing rather than a square.
 *
 * The bridge is the only one, and it is why [footprint] takes a facing at all. A square footprint is
 * the same set of tiles however the machine is turned, which is what let rotation be "a change of
 * facing and nothing else" — see [size]. A span breaks that: turning a bridge moves it off two tiles
 * and onto two others, so a rotation can be blocked, and `Edit.Rotate` has to check.
 *
 * Worth the exception rather than making a bridge 3×3, which would have it claim nine tiles to cross
 * one, or 1×1, which is what it was when it occupied no floor at all and could be stacked without
 * limit.
 */
val DeckMachineKind.span: Boolean get() = this == DeckMachineKind.Bridge

/** Half-width: how far the footprint reaches from its centre in each direction. */
val DeckMachineKind.reach: Int get() = diameter / 2

/**
 * Footprint of indexes pointing to the material a machine is made of, or null if it does not fit on
 * [grid] at [center].
 *
 * [facing] is only read for a [span]; a square footprint covers the same tiles whichever way its
 * machine is pointing, so every other kind ignores it.
 */
fun DeckMachineKind.footprint(
    center: TileIndex,
    grid: Grid,
    facing: Direction = Direction.Right,
): Array<TileIndex>? {
    val cx = grid.xOf(center)
    val cy = grid.yOf(center)
    // How far it reaches along x and along y — equal for a square, and all in one axis for a span.
    val rx = if (!span || facing.isHorizontal) reach else 0
    val ry = if (!span || !facing.isHorizontal) reach else 0

    // Check footprint validity
    if (cx - rx < 0 || cx + rx >= grid.width) return null
    if (cy - ry < 0 || cy + ry >= grid.height) return null

    val w = rx * 2 + 1
    return Array(w * (ry * 2 + 1)) { grid.tile(cx + (it % w) - rx, cy + (it / w) - ry) }
}
