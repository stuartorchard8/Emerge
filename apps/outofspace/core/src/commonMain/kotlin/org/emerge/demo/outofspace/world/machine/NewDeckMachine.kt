package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.TileIndex

/**
 * A machine of [kind], as it comes out of the box: anchored at [tile], pointing [facing], and
 * carrying whatever settings a freshly placed one starts with.
 *
 * ⛔ **It builds nothing and asks nothing** — the world does not know about what comes back, and
 * whether it may stand where it has been put is [canStand]'s question, not this one. That split is
 * what lets the *cursor* hold one: the preview under the pointer needs a real machine to take a
 * footprint and a set of ports from, and it must be able to get one without touching the deck.
 *
 * It was a private method of the reducer, which is why the preview could not exist. Nothing about it
 * was ever reducer business: it is a constructor with a `when` in front of it.
 */
fun newDeckMachine(kind: DeckMachineKind, tile: TileIndex, facing: Direction): DeckMachine = when (kind) {
    DeckMachineKind.Hull -> Hull(tile)
    DeckMachineKind.Airlock -> Airlock(tile)
    DeckMachineKind.Vent -> Vent(tile)
    // One machine at three sizes — see [Storage]. The kind is handed straight through, which is
    // what makes a new size cost a line here rather than a class.
    DeckMachineKind.Warehouse, DeckMachineKind.Silo, DeckMachineKind.Buffer ->
        Storage(tile, facing, kind, autoLock = true, autoUnlock = true)
    // Placed with both lists empty: a port that has not been told what to trade is inert,
    // and choosing for the player is the one thing a mouth onto their money must not do.
    DeckMachineKind.DockingPort -> DockingPort(tile, facing)
    DeckMachineKind.Sensor -> Sensor(tile, facing, threshold = SignalField.FULL, delay = 0, release = 0)
    DeckMachineKind.KeyInput -> WireButton(tile)
    DeckMachineKind.Pump -> Pump(tile, facing)
    DeckMachineKind.Electrolyzer -> Electrolyzer(tile, facing)
    DeckMachineKind.Thruster -> Thruster(tile, facing)
    DeckMachineKind.Rocket -> Rocket(tile, facing)
    DeckMachineKind.Concentrator -> Concentrator(tile, facing)
    DeckMachineKind.Furnace -> Furnace(tile, facing)
    DeckMachineKind.Extractor -> Extractor(tile, facing)
    DeckMachineKind.Bridge -> Bridge(tile, facing)
    DeckMachineKind.Gauge -> Gauge(tile)
    DeckMachineKind.SolarPanel -> SolarPanel(tile)
    DeckMachineKind.Valve -> Valve(tile)
}
