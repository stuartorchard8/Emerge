package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Genesis teaches interactions — select a cell, open its chemistry — that a curious player has very likely
 * already tried by the time the coach gets round to asking. Those goals are therefore **state**, not events:
 * they ask whether the thing is true, so getting there first counts.
 *
 * The two that stay events are the ones with nothing to read: panning the camera and dragging a cell both
 * leave the world exactly as they found it.
 */
class GenesisGatesTest {

    private val genesis = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == "ch00-genesis" }

    private fun gate(prefix: String): Gate.World =
        genesis.steps.mapNotNull { it.gate as? Gate.World }.single { it.desc.startsWith(prefix) }

    private fun query(selected: Boolean, chemistryOpen: Boolean = false) = CampaignQuery(
        WorldStats(
            0L, 1, mapOf(CellType.Collector to 1), 3000, emptySet(),
            focused = if (selected) FocusedCell(CellType.Collector, 3000, mapOf("r" to 100)) else null,
            lineage = Lineage(geneCount = 0),
        ),
        paused = false, selectedGenome = null, chemistryOpen = chemistryOpen,
    )

    @Test
    fun theSelectBeatIsSatisfiedByAnExistingSelection() {
        val g = gate("Select the cell")
        assertFalse(g.met(query(selected = false)), "nothing selected is still nothing selected")
        assertTrue(g.met(query(selected = true)), "already selected counts - the beat asks for a state")
    }

    @Test
    fun theChemistryBeatIsSatisfiedByAnAlreadyOpenReadout() {
        val g = gate("View the chemistry details")
        assertFalse(g.met(query(selected = true, chemistryOpen = false)))
        assertTrue(g.met(query(selected = true, chemistryOpen = true)))
    }

    /**
     * The two beats that must stay events, and why: neither leaves anything behind to read. A camera move
     * ends with the camera somewhere, which is where it always was; a dragged cell is at a position, and
     * cells drift there on their own anyway. Asserted so that "make everything state-based" doesn't get
     * applied to them by a later sweep without someone first inventing the state.
     */
    @Test
    fun onlyTheTracelessGesturesRemainEvents() {
        val events = genesis.steps.mapNotNull { it.gate as? Gate.Did }.map { it.action }
        assertEquals(listOf(PlayerAction.MovedCamera, PlayerAction.MovedCell), events)
    }

    /** Every remaining goal in the playable campaign reads the world rather than an interaction. */
    @Test
    fun theRestOfTheCampaignIsStateGated() {
        val events = CampaignContent.PLAYABLE_CHAPTERS
            .filter { it.id != "ch00-genesis" }
            .flatMap { ch -> ch.steps.mapNotNull { it.gate as? Gate.Did }.map { "${ch.id}: ${it.desc}" } }
        assertEquals(emptyList(), events, "these would ask a player who got ahead to repeat themselves")
    }
}
