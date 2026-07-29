package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ch01-divide`'s die-off beat: the player is told to watch their cell, whose unconditional DIVIDE gene
 * splits it below the rupture floor.
 *
 * The gate used to be `cellCount == 0` — the whole WORLD emptying. That outlasted its own lesson: the cell
 * the player was watching ruptures, its cousins keep going for thousands of ticks, and the coach sits there
 * saying "push stalled cells into fresh matter" about cells the player is no longer watching. The gate now
 * reads [CampaignQuery.watchedCellDied], which is the event the copy actually describes.
 */
class DivideDieOffBeatTest {

    private val chapter = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == "ch01-divide" }

    private val dieOff: Gate.World =
        chapter.steps.mapNotNull { it.gate as? Gate.World }.single { it.desc.startsWith("Watch your cell") }

    /** A mid-collapse world: [cells] still alive, and the cell the player was watching [died] or didn't. */
    private fun query(cells: Int, died: Boolean) = CampaignQuery(
        WorldStats(
            0L, cells, mapOf(CellType.Collector to cells), 1800, emptySet(),
            if (cells == 0) null else FocusedCell(CellType.Collector, 1800, mapOf("r" to 100)),
            Lineage(
                geneCount = 2, convertChem = "r", convertBiomassCap = 3000,
                hasDivide = true, divideProduct = "rg", divideFuelConflicts = false,
            ),
            watchedCellDied = died,
        ),
        paused = false, selectedGenome = null,
    )

    @Test
    fun aThrivingColonyDoesNotSatisfyTheBeat() {
        assertFalse(dieOff.met(query(cells = 40, died = false)), "nothing has happened to their cell yet")
    }

    /** The fix, in one assertion: their cell ruptured, the colony is fine, and the beat is over. */
    @Test
    fun theWatchedCellRupturingIsEnoughEvenWithTheColonyAlive() {
        assertTrue(dieOff.met(query(cells = 40, died = true)))
    }

    /** A slow collapse that does empty the world still satisfies it — if nothing is alive, theirs died. */
    @Test
    fun aFullExtinctionStillSatisfiesTheBeat() {
        assertTrue(dieOff.met(query(cells = 0, died = true)))
    }

    /**
     * The beat must keep its own copy on screen. [CampaignDirector.extinctionOffer] replaces a step's text
     * with a "your cells are all gone, place a new one" recovery pitch, and it is suppressed by asking the
     * gate whether it would still be met *with a cell alive* ([CampaignQuery.asIfPopulated]). That probe has
     * to clear `watchedCellDied` for a watch-it-die gate to answer "no" — otherwise this step, which asked
     * for the death, gets handed a recovery offer for it.
     */
    @Test
    fun theBeatReadsAsItsOwnGoalSoTheExtinctionOfferStaysAway() {
        val emptied = query(cells = 0, died = true)
        assertTrue(dieOff.met(emptied), "precondition: the gate is met by the collapse")
        assertFalse(dieOff.met(emptied.asIfPopulated()), "the gate's goal IS the death, so the offer is suppressed")
    }

    /** The sibling probe must NOT clear it: [CampaignDirector.watchedCellOffer] asks whether a missing
     *  SELECTION is what blocks an unmet gate, a question the death flag has no part in. */
    @Test
    fun theSelectionProbeLeavesTheDeathFlagAlone() {
        val q = query(cells = 40, died = true)
        assertTrue(q.withProbeSelection(full = false).watchedCellDied)
        assertTrue(q.withProbeSelection(full = true).watchedCellDied)
    }
}
