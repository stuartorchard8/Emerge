package org.emerge.demo.outofspace.world

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
        MachineKind.Sensor, MachineKind.Vent, MachineKind.Pump, MachineKind.Hull, MachineKind.Airlock -> 1
        MachineKind.KeyInput -> 1
        MachineKind.Processor, MachineKind.Vaporizer, MachineKind.Storage -> 3
        // A floor to land a rock on, and a rock is five tiles across.
        MachineKind.Extractor, MachineKind.Smelter -> 5
    }

/** Half-width: how far the footprint reaches from its centre in each direction. */
val MachineKind.reach: Int get() = size / 2
