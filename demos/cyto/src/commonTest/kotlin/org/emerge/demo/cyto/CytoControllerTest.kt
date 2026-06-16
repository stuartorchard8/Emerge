package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke test for the live runtime wiring: [CytoController] drives the struct-of-arrays
 * [org.emerge.demo.cyto.sim.soa.CytoSoaReducer] over a persistent world, materializing a `SimState`
 * once per frame for the renderer / hit-test / save. Confirms the world steps and grows, and that a
 * save snapshot round-trips through the SoA world (decode → fromSimState → toSimState → encode).
 */
class CytoControllerTest {
    private fun cells(frame: CytoFrame) = frame.state.components.getTable<CytoCellComponent>().asMap().size

    @Test
    fun controllerStepsAndGrowsAColony() {
        val c = CytoController()
        var frame = c.tick(0f)
        val start = cells(frame)
        assertTrue(start >= 1, "founder should be present")
        // Drive ~1280 steps (frame delta capped at 0.25s ⇒ 16 steps/tick at the 1/64 rate) — past the
        // founder's first division (~tick 988 under the moving daylight band).
        repeat(80) { frame = c.tick(0.25f) }
        assertTrue(c.tick > 1000, "should have run past the first division (was ${c.tick})")
        assertTrue(cells(frame) > start, "the autotroph colony should grow (was $start, now ${cells(frame)})")
    }

    @Test
    fun clickToDeleteRemovesTheTappedCell() {
        // The real player path: CytoController.tap(Delete) → CytoInput → CytoSoaReducer interaction +
        // lifecycle bridges. Guards the click-to-delete regression end-to-end through the live runtime.
        val c = CytoController()
        var frame = c.tick(0f)
        repeat(80) { frame = c.tick(1f) }   // grow a few cells (slower: break-powered + moving light)
        val cells = frame.state.components.getTable<CytoCellComponent>().asMap()
        assertTrue(cells.size > 1, "need cells to delete (had ${cells.size})")

        val target = cells.keys.first()
        val pos = frame.state.components.getTable<TransformComponent>().asMap().getValue(target).pos
        c.tap(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y), TouchMode.Delete, CellType.Collector)
        frame = c.tick(0.25f)   // process the tap

        assertTrue(
            target !in frame.state.components.getTable<CytoCellComponent>().asMap().keys,
            "click-to-delete should remove the tapped cell",
        )
    }

    @Test
    fun saveSnapshotRoundTripsThroughTheSoaWorld() {
        val c = CytoController()
        repeat(4) { c.tick(1f) }
        val savedTick = c.tick
        val bytes = c.snapshotBytes()
        val before = cells(c.tick(0f))

        val restored = CytoController()
        restored.restoreSnapshot(bytes)
        assertEquals(before, cells(restored.tick(0f)), "restored population should match the saved one")
        assertEquals(savedTick, restored.tick, "restore resumes the sim clock so the day/night phase persists")
        // And it keeps simulating from the restored world.
        var frame = restored.tick(0f)
        repeat(2) { frame = restored.tick(1f) }
        assertTrue(cells(frame) >= 1, "restored colony should keep living")
    }
}
