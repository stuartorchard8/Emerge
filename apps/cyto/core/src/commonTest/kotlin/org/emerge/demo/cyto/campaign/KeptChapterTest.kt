package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.host.CampaignContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ch05b-kept` — the close of Act I. It teaches no biology, so what there is to guard is structural: the
 * export affordance must arrive *here* and nowhere earlier (the button appearing IS the lesson), and the
 * chapter must sit between the reclaim chapter and the bridge into Act II.
 */
class KeptChapterTest {

    private val chapter = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.KEPT }

    /**
     * **The load-bearing assertion.** `Control.Save` gates EXPORT GENOME, and the chapter's copy says "there
     * is a new button" — which is a lie the moment any earlier chapter grants it. Checked across the whole
     * playable arc rather than in this chapter alone, because the failure mode is somewhere else.
     */
    @Test
    fun exportIsWithheldEverywhereBeforeThisChapter() {
        val leaks = CampaignContent.PLAYABLE_CHAPTERS
            .takeWhile { it.id != CampaignContent.KEPT }
            .flatMap { ch -> ch.steps.filter { it.allow.allows(Control.Save) }.map { "${ch.id}: ${it.text}" } }
        assertEquals(emptyList(), leaks, "the export button must not exist before the chapter that introduces it")
    }

    /** And it genuinely arrives: withheld while the player is still selecting a cell, granted on the beat
     *  that points at it. */
    @Test
    fun theButtonArrivesOnTheStepThatRingsIt() {
        val exportStep = chapter.steps.first { it.spotlight?.target == "EXPORT GENOME" }
        assertTrue(exportStep.allow.allows(Control.Save))
        assertEquals(
            PlayerAction.ExportedGenome, (exportStep.gate as Gate.Did).action,
            "the gate must be the write itself, so cancelling the name screen does not tick it off",
        )
        val before = chapter.steps[chapter.steps.indexOf(exportStep) - 1]
        assertFalse(before.allow.allows(Control.Save), "the button has to be absent for its arrival to read")
    }

    /** It closes Act I and hands straight over to the Act II bridge. */
    @Test
    fun itSitsBetweenReclaimAndTheActTwoBridge() {
        assertEquals(1, chapter.act)
        assertEquals(
            listOf(CampaignContent.RECLAIM),
            CampaignContent.predecessorsOf(CampaignContent.KEPT, CampaignContent.PLAYABLE_CHAPTERS),
        )
        assertEquals(listOf(CampaignContent.REHOMED_HOLD), chapter.branchesTo)
    }

    /**
     * It continues the world it is about. Rebuilding here would hand the player a fresh world and then ask
     * them to export "the colony that is not going anywhere" — which would be somebody else's colony.
     */
    @Test
    fun itKeepsThePlayersOwnWorldAndGenome() {
        assertFalse(chapter.startsFreshWorld)
        assertTrue(chapter.spawnCopiesHeldCell)
        assertEquals(emptyList(), chapter.spawnGenome, "a stock genome here would overwrite what they built")
    }
}
