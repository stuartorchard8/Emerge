package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // Drive ~3200 steps (frame delta capped at 0.25s ⇒ 16 steps/tick at the 1/64 rate) — past the
        // founder's first division, which slips later under the ~50× light nerf (reserve-building is slower).
        repeat(200) { frame = c.tick(0.25f) }
        assertTrue(c.tick > 1000, "should have run past the first division (was ${c.tick})")
        assertTrue(cells(frame) > start, "the autotroph colony should grow (was $start, now ${cells(frame)})")
    }

    @Test
    fun clickToDeleteRemovesTheTappedCell() {
        // The real player path: CytoController.tap(Delete) → CytoInput → CytoSoaReducer interaction +
        // lifecycle bridges. Guards the click-to-delete regression end-to-end through the live runtime.
        val c = CytoController()
        var frame = c.tick(0f)
        repeat(200) { frame = c.tick(1f) }   // grow a few cells (slower under the ~50× light nerf)
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
    fun panelReportsTheRealTouchCountNotAStub() {
        // Regression: describeGeneSpans hardcoded `val touch = 0`, so a TOUCH clause could never read
        // anything but zero — the panel greyed out TOUCH-gated genes that the sim was actually firing.
        // Two cells spawned essentially on top of each other are in contact immediately, so the contact is
        // CONSTRUCTED rather than waited for: what's under test is that the count survives the SoA →
        // SimState round-trip, not that a colony eventually crowds itself (which cost 400 ticks to say).
        // The tiny offset keeps them from being exactly coincident, which is a degenerate physics case.
        // Un-welded contact is TRANSIENT — cells touch for a tick or two and are pushed apart — so this
        // scans every tick for a non-zero reading rather than sampling the final frame and hoping. The old
        // version drove 400 ticks and looked once, which is why it needed so many: it was a lottery.
        val c = CytoController()
        c.tick(0f)
        c.spawn(0f, 0f, CellType.Collector)
        c.spawn(0.002f, 0f, CellType.Collector)
        var seen = 0
        repeat(60) {
            // The panel reads the materialized SimState, so the count must survive SoA → SimState — that
            // round-trip is the link the stub used to hide.
            val cells = c.tick(1f).state.components.getTable<CytoCellComponent>().asMap()
            seen = maxOf(seen, cells.values.maxOfOrNull { it.touchCount } ?: 0)
        }
        assertTrue(seen > 0, "cells in contact should surface a non-zero touchCount through the live controller path")
    }

    /**
     * Near a world edge the camera is on one side of the seam and a cell can be on the other, yet it still
     * renders under the cursor (the renderer draws every object at its shortest torus delta from the
     * camera). [CytoRenderer.screenToWorld] deliberately returns an *unwrapped* logical point, so such a
     * click arrives a full [CytoLightField.SPAN] away from the cell's stored position. The hit test must
     * wrap it; before the fix it compared flat deltas, missed, and the miss fell through to "insert a cell".
     */
    @Test
    fun cellAtHitsACellAcrossTheTorusSeam() {
        val c = CytoController()
        var frame = c.tick(0f)
        repeat(200) { frame = c.tick(1f) }
        val target = frame.state.components.getTable<CytoCellComponent>().asMap().keys.first()
        val pos = frame.state.components.getTable<TransformComponent>().asMap().getValue(target).pos
        val x = CytoUnits.toLogical(pos.x)
        val y = CytoUnits.toLogical(pos.y)

        // Whichever cell owns this point (discs can overlap) is the one every wrapped click must also find.
        val hit = c.cellAt(x, y)
        assertTrue(hit != null, "sanity: a cell should be hit at its own centre")
        val span = CytoLightField.SPAN
        assertEquals(hit, c.cellAt(x + span, y), "a click one span east should wrap onto the same cell")
        assertEquals(hit, c.cellAt(x - span, y), "a click one span west should wrap onto the same cell")
        assertEquals(hit, c.cellAt(x, y + span), "a click one span north should wrap onto the same cell")
        assertEquals(hit, c.cellAt(x, y - span), "a click one span south should wrap onto the same cell")
    }

    /** The same seam bug lived in the sim-side tap resolution (CytoInteractionSystem.contains), which
     *  resolves Delete/Kill/Set taps. Guards it end-to-end through the live runtime. */
    @Test
    fun clickToDeleteWorksAcrossTheTorusSeam() {
        val c = CytoController()
        var frame = c.tick(0f)
        repeat(200) { frame = c.tick(1f) }
        val cells = frame.state.components.getTable<CytoCellComponent>().asMap()
        assertTrue(cells.size > 1, "need cells to delete (had ${cells.size})")

        val target = cells.keys.first()
        val pos = frame.state.components.getTable<TransformComponent>().asMap().getValue(target).pos
        // Tap a full span away — as a click near the seam arrives.
        val span = CytoLightField.SPAN
        c.tap(CytoUnits.toLogical(pos.x) + span, CytoUnits.toLogical(pos.y), TouchMode.Delete, CellType.Collector)
        frame = c.tick(0.25f)

        assertTrue(
            target !in frame.state.components.getTable<CytoCellComponent>().asMap().keys,
            "a delete tap across the seam should remove the tapped cell",
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

    /**
     * A tap is buffered and drained by the reducer, so while the world is held still it applies to nothing —
     * and then lands whenever the player resumes, which reads as a click that did nothing followed by one
     * that did two things. [CytoController.hasPendingInput] is how a paused host sees there is something
     * waiting and gives it exactly one tick.
     */
    @Test
    fun aTapWaitsForATickAndTheHostCanSeeThatItIsWaiting() {
        val c = CytoController()
        c.newGame(CytoScenario.DEFAULT.copy(founders = emptyList()))
        assertEquals(0, cells(c.tick(0f)), "a founder-less scenario starts empty")
        assertFalse(c.hasPendingInput(), "nothing queued yet")

        c.tap(0f, 0f, TouchMode.Base, CellType.Stem)
        assertTrue(c.hasPendingInput(), "queued, and waiting for a tick that a paused host is not running")
        assertEquals(0, cells(c.tick(0f)), "so no cell yet - this is the click that looks inert")

        assertEquals(1, cells(c.tick(CytoController.STEP)), "one tick lands it")
        assertFalse(c.hasPendingInput(), "and the queue is empty again, so the host stops stepping")
    }

    @Test
    fun reseedLineagePlacesTheCarriedGenomeImmediately() {
        // The campaign Reset's second half: after the world is emptied, put one cell carrying the player's
        // own genome under the camera. It must exist as soon as the call returns - a Reset commonly lands on
        // a Frozen step where no tick is coming, and a world that looks empty until you unpause reads as a
        // broken reset.
        val genes = GeneCodec.parse("Light : Biomass > 0 : Convert r")
        val c = CytoController()
        c.newGame(CytoScenario.DEFAULT.copy(founders = emptyList()))
        assertEquals(0, cells(c.tick(0f)), "a founder-less scenario starts empty")

        c.reseedLineage(genes, 0f, 0f, biomass = mapOf("r" to 1000, "g" to 1000, "b" to 1000))
        assertEquals(1, cells(c.tick(0f)), "the re-seeded cell exists without waiting for a tick")
        assertEquals(genes, c.representativeGenome(), "and it carries the genome it was given")
    }
}
