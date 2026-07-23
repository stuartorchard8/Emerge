package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoTestWorld
import org.emerge.demo.cyto.sim.CytoWorldConfig
import org.emerge.sim.core.EntityId

/**
 * Put a [CytoTestWorld.Fixture] into a live [CytoController] — the bridge between a stated world and
 * something you can tick, inspect and screenshot.
 *
 * It goes in through the **save path** rather than a bespoke back door: the fixture is encoded and restored
 * exactly as an F9 load would be. That keeps the controller free of a test-only world-setter, and means a
 * fixture can't quietly depend on state the game itself can't persist.
 */
fun CytoController.loadFixture(fixture: CytoTestWorld.Fixture) {
    // The reducer sizes per-tile buffers from the world geometry at construction, and restoreSnapshot
    // rebuilds it — so the holder must reflect the fixture's scenario first (same contract as newGame).
    CytoWorldConfig.applyFrom(fixture.scenario)
    restoreSnapshot(CytoSaveCodec.encode(fixture.state))
}

/**
 * The id of the fixture's named cell in this controller, resolved by position — ids are not stable across
 * the save round-trip, positions are.
 */
fun CytoController.fixtureCell(fixture: CytoTestWorld.Fixture, name: String): EntityId {
    val (x, y) = fixture.positionOf(name)
    return cellAt(x, y) ?: error("no cell at '$name' ($x, $y) — the fixture didn't load")
}

/** Load [fixture], select its named cell, and publish so the panel readings are live. The usual opening. */
fun CytoController.focusFixtureCell(fixture: CytoTestWorld.Fixture, name: String): EntityId {
    loadFixture(fixture)
    val id = fixtureCell(fixture, name)
    focus(id)
    publish()
    return id
}
