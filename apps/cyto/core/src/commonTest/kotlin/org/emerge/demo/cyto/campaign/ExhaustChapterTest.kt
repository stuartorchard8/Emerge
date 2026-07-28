package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Exhaust chapter's fix, gate by gate. Its three closing beats — add a gene, make it a light-powered
 * BREAK of the waste, then watch the waste drain — are the first goals in the campaign that read the
 * *contents* of a cell rather than a count of them, so each one is worth pinning down on its own.
 *
 * The payoff is a measured one. On this genome the waste settles at ~700 units per cell and stays there;
 * with the recycling gene it sits at 1. The threshold below is loose on purpose — the beat asks the player
 * to watch a number fall, not to race it to zero.
 */
class ExhaustChapterTest {

    private val chapter = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.BRANCH_PHOTOSYNTHESIS }

    /** The [Gate.World] whose objective line starts with [prefix] — steps are addressed by what they ASK
     *  for, so inserting a beat ahead of one doesn't silently re-point the test at its neighbour. */
    private fun gate(prefix: String): Gate.World =
        chapter.steps.mapNotNull { it.gate as? Gate.World }.single { it.desc.startsWith(prefix) }

    /** A world holding one cell of the Exhaust end-state lineage: grows on `r`, divides on `g`+`b`, and so
     *  accumulates `gb`. [genes] and [held] are what each gate actually discriminates on. */
    private fun query(genes: Int, held: Map<String, Int>, recycles: Boolean = false) = CampaignQuery(
        WorldStats(
            0L, 8, mapOf(CellType.Collector to 8), 3000, emptySet(),
            FocusedCell(CellType.Collector, 3000, held),
            Lineage(
                geneCount = genes, convertChem = "r", convertBiomassCap = 3000,
                divideBiomassMinimum = 2000, hasDivide = true, divideProduct = "gb",
                hasPhotosynthesis = recycles, divideFuelConflicts = false,
            ),
        ),
        paused = false, selectedGenome = null,
    )

    private val loaded = mapOf("r" to 400, "g" to 500, "b" to 500, "gb" to 712)
    private val cleared = mapOf("r" to 400, "g" to 500, "b" to 500, "gb" to 1)

    @Test
    fun theNewGeneGoalNeedsAThirdGene() {
        val g = gate("Give a cell a new gene")
        assertFalse(g.met(query(2, loaded)), "the two genes they arrived with are not the fix")
        assertTrue(g.met(query(3, loaded)))
    }

    /**
     * The reason this is `>=` and not `==`: a player who added a blank gene earlier and never filled it in
     * arrives carrying four, and an exact count would strand them on a goal they have already met.
     */
    @Test
    fun aSpareBlankGeneDoesNotStrandThePlayer() {
        assertTrue(gate("Give a cell a new gene").met(query(4, loaded)))
    }

    @Test
    fun theBreakGoalNeedsTheRecyclingGeneItself() {
        val g = gate("Update the gene to BREAK")
        assertFalse(g.met(query(3, loaded)), "a third gene alone is not the right third gene")
        assertTrue(g.met(query(3, loaded, recycles = true)))
    }

    /** The payoff beat: the waste reading in the panel has to actually come down. */
    @Test
    fun theDrainGoalWaitsForTheWasteToFall() {
        val g = gate("Clear the")
        assertFalse(g.met(query(3, loaded, recycles = true)), "authoring the gene is not the same as it working")
        assertTrue(g.met(query(3, cleared, recycles = true)))
    }

    /** A cell that never held any waste satisfies it too — the goal is "not carrying this", not "carried it
     *  and lost it", and a player who selects a freshly divided daughter must not be stuck. */
    @Test
    fun aCellWithNoWasteAtAllAlreadySatisfiesIt() {
        assertTrue(gate("Clear the").met(query(3, mapOf("r" to 400), recycles = true)))
    }

    /** With nothing selected there is no reading to judge, so the goal stays open rather than passing on a
     *  null — the copy tells the player to watch a specific cell. */
    @Test
    fun nothingSelectedIsNotAPass() {
        val q = query(3, cleared, recycles = true)
        val noSelection = CampaignQuery(
            WorldStats(0L, 8, emptyMap(), 3000, emptySet(), null, q.lineage),
            paused = false, selectedGenome = null,
        )
        assertFalse(gate("Clear the").met(noSelection))
    }

    /** The chapter's own copy names `{bond}`, which the director fills from the divide gene's product. A
     *  lineage that never chose one would render the goal as a blank, so the reading has to be there. */
    @Test
    fun theWasteGoalIsToldWhichMoleculeToWatch() {
        assertEquals("gb", query(3, loaded).lineage?.divideProduct)
    }

    // ── The watched cell dying on the final beat ─────────────────────────────────────────────────────

    /** The same world, but the cell the player was watching has ruptured — the colony is untouched. */
    private fun afterTheWatchedCellDied() = CampaignQuery(
        WorldStats(
            0L, 8, mapOf(CellType.Collector to 8), 3000, emptySet(),
            focused = null, lineage = query(3, cleared, recycles = true).lineage, watchedCellDied = true,
        ),
        paused = false, selectedGenome = null,
    )

    /** The real final step, run by the real director, so this is the chapter as played. */
    private fun directorOnTheFinalStep(): CampaignDirector {
        val dir = CampaignDirector()
        dir.start(Chapter("t", 1, "T", "", chapter.scenario, listOf(chapter.steps.last())), CytoController())
        return dir
    }

    /**
     * Stu's bug: on 7/7 the goal is a reading taken off the *selected* cell, so if that cell dies before
     * dawn the gate can never be satisfied again — while the world carries on looking perfectly healthy,
     * hundreds of siblings running the genome the player just wrote. Nothing announced it, and there was
     * nothing to do; the beat simply stopped being winnable.
     */
    @Test
    fun theWatchedCellDyingOnTheFinalBeatIsNotADeadEnd() {
        val dir = directorOnTheFinalStep()
        dir.update(afterTheWatchedCellDied(), emptySet())

        assertFalse(dir.gateReady, "the goal is unreachable with nothing selected")
        assertTrue(dir.watchedCellOffer, "so the coach has to say so")
        assertTrue(dir.controlMask.allows(Control.Select), "and permit the pick it asks for")
        // Assert the *substitution*, not one word of the copy: an earlier version pinned this to "siblings"
        // and broke on a routine copy pass, which tells you nothing about whether the beat still works.
        val shown = dir.snapshot()!!.text
        assertNotEquals(
            chapter.steps.last().text, shown,
            "the coach's own words, not the step's — a headless observer sees what the player sees",
        )
        assertTrue(shown.contains("place a new cell"), "and they name the way out")
    }

    /** ...and it goes away the moment they pick one, rather than needing to be dismissed. */
    @Test
    fun selectingAnotherCellPutsTheBeatBack() {
        val dir = directorOnTheFinalStep()
        dir.update(afterTheWatchedCellDied(), emptySet())
        assertTrue(dir.watchedCellOffer)

        dir.update(query(3, loaded, recycles = true), emptySet())
        assertFalse(dir.watchedCellOffer, "they are watching a cell again")
        assertTrue(dir.snapshot()!!.text.contains("watch the cell"), "and the beat's own copy is back")
    }

    /** The goal still has to be won on its merits: picking a fresh cell is not itself a pass. */
    @Test
    fun theOfferDoesNotSatisfyTheGoal() {
        val dir = directorOnTheFinalStep()
        dir.update(afterTheWatchedCellDied(), emptySet())
        assertFalse(dir.gateReady)

        dir.update(query(3, loaded, recycles = true), emptySet())
        assertFalse(dir.gateReady, "a cell still full of waste has not cleared it")
        dir.update(query(3, cleared, recycles = true), emptySet())
        assertTrue(dir.gateReady)
    }
}
