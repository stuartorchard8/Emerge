package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.MachineKind

/**
 * How many tiles across a machine is. Always **odd**, always square.
 *
 * Odd because a machine is stored at its **centre** tile, and a centre only exists for odd sizes.
 * That one choice removes most of the awkwardness from rotation: turning a building leaves its
 * anchor exactly where it was, so a rotation is a change of facing and nothing else. With a top-left
 * anchor, every rotation would also have to move the machine, and "rotate" would silently become
 * "rotate and translate" — which is fiddly to place and worse to undo.
 *
 * The sizes say what a thing *is*. A conveyor is a fitting, so it is one tile. A processor or a tank
 * is a room-sized installation at three. A smelter is a furnace at five: it should dominate the deck
 * it sits on, and its heat should have somewhere to be.
 */
val MachineKind.size: Int
    get() = when (this) {
        // Fittings and the small deck pieces. A bridge is three tiles long but occupies none of
        // them, so its size says nothing about space -- only its two ports place it.
        MachineKind.Rail, MachineKind.Pipe, MachineKind.Gauge, MachineKind.Valve, MachineKind.Bridge -> 1
        MachineKind.Wire -> 1
        MachineKind.Sensor, MachineKind.Vent, MachineKind.Pump, MachineKind.Airlock -> 1
        MachineKind.Thruster -> 1
        MachineKind.KeyInput -> 1
        MachineKind.Processor, MachineKind.ThermalDecomposer, MachineKind.Vaporizer, MachineKind.Storage -> 3
        // A floor to land a rock on, and a rock is five tiles across.
        MachineKind.Extractor, MachineKind.Smelter -> 5
    }
val DeckMachineKind.diameter: Int
    get() = when (this) {
        DeckMachineKind.Hull -> 1
    }

/** Half-width: how far the footprint reaches from its centre in each direction. */
val MachineKind.reach: Int get() = size / 2
val DeckMachineKind.reach: Int get() = diameter / 2

/**
 * Footprint of indexes pointing to the material a machine is made of.
 */
fun DeckMachineKind.footprint(center: TileIndex, grid: Grid) : Array<TileIndex>? {
//    var sx = size
//    var sy = size
    // N.B. bridges will not be deck machines. Just keeping this here for now.
//    if (this == DeckMachineKind.Bridge) {
//        sx = if (direction.isHorizontal) size else 1
//        sy = if (direction.isVertical) size else 1
//    }

    // Check footprint validity
    val cx = grid.xOf(center)
    val cy = grid.yOf(center)
    if (cx - reach < 0 || cx + reach >= grid.width) return null
    if (cy - reach < 0 || cy + reach >= grid.height) return null

    return Array(diameter*diameter) { grid.tile(
        cx + (it % diameter) - reach,
        cy + (it / diameter) - reach,
    ) }
}
