package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import org.emerge.demo.cyto.sim.CytoScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The campaign's one branch: which chapter follows `ch01-divide` is decided by the fuel pair the player
 * chose there, and by nothing else. They are never asked — they build something, and what they built picks
 * the chapter — so the routing has to be read off the world at the moment the chapter ends.
 */
class CampaignBranchTest {

    private fun query(conflicts: Boolean?, focused: Boolean = true) = CampaignQuery(
        WorldStats(
            0L, 1, mapOf(CellType.Collector to 1), 100, emptySet(),
            if (!focused) null else FocusedCell(
                CellType.Collector, 100, 2, emptyMap(),
                convertChem = "r", convertBiomassCap = 3000,
                hasMitosis = true, mitosisProduct = "rg",
                divideFuelConflicts = conflicts,
            ),
        ),
        paused = false, selectedGenome = null,
    )

    /**
     * Run [ch] to its end and return the chapter the director lands in. The chapter is walked under a normal
     * built-it-properly world; [atTheEnd] (default: the same) is what the world reads as on the *final*
     * click, which is the only reading the branch is allowed to depend on.
     */
    private fun destinationOf(ch: Chapter, q: CampaignQuery, atTheEnd: CampaignQuery = q): String? {
        val ctrl = CytoController()
        val dir = CampaignDirector()
        dir.chapters = CampaignContent.PLAYABLE_CHAPTERS
        var entered: String? = null
        dir.onChapterEntered = { c, _ -> entered = c.id }
        dir.start(ch, ctrl)
        repeat(ch.steps.size) { i ->
            dir.update(if (i == ch.steps.lastIndex) atTheEnd else q, PlayerAction.entries.toSet())
            assertTrue(dir.tryAdvance(ctrl), "step ${i + 1} of ${ch.id} did not advance")
        }
        return entered
    }

    private val divide = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == "ch01-divide" }

    /** The chapters `ch01-divide` declares it can lead to. Non-null by construction — it is the branch. */
    private val branches: List<String> get() = divide.branchesTo!!

    @Test
    fun aFuelPairThatEatsTheGrowthMonomerTakesTheConversionPath() {
        assertEquals(CampaignContent.BRANCH_CONVERSION, destinationOf(divide, query(conflicts = true)))
    }

    @Test
    fun anIndependentFuelPairTakesThePhotosynthesisPath() {
        assertEquals(CampaignContent.BRANCH_PHOTOSYNTHESIS, destinationOf(divide, query(conflicts = false)))
    }

    /** A player who deselected their cell before the final click leaves nothing to read. Fall to the stable
     *  lineage: it is the one that survives being left alone, so an accident there isn't a dead end. */
    @Test
    fun anUnreadableWorldFallsToTheStablePath() {
        val built = query(conflicts = true)
        assertEquals(
            CampaignContent.BRANCH_PHOTOSYNTHESIS,
            destinationOf(divide, built, atTheEnd = query(null, focused = false)),
        )
        assertEquals(
            CampaignContent.BRANCH_PHOTOSYNTHESIS,
            destinationOf(divide, built, atTheEnd = query(conflicts = null)),
        )
    }

    /** Both destinations have to exist and be reachable, or the branch strands the player mid-campaign. */
    @Test
    fun bothDestinationsAreInThePlayableList() {
        val ids = CampaignContent.PLAYABLE_CHAPTERS.map { it.id }
        for (id in branches) assertTrue(id in ids, "$id is declared but not playable")
    }

    /** A branch destination is unlocked by the chapter that BRANCHES to it, not by whatever happens to sit
     *  before it in the flat list. */
    @Test
    fun branchDestinationsUnlockFromTheBranchingChapter() {
        val flow = CampaignContent.PLAYABLE_CHAPTERS
        for (id in branches) {
            assertEquals(listOf(divide.id), CampaignContent.predecessorsOf(id, flow), "$id unlocks from $id")
        }
    }

    /** A chapter that branches must not ALSO unlock its list neighbour — that would re-open the door the
     *  branch just closed, and the "secret" path would be reachable without earning it. */
    @Test
    fun aBranchingChapterDoesNotAlsoUnlockItsNeighbour() {
        val flow = CampaignContent.PLAYABLE_CHAPTERS
        val after = flow[flow.indexOfFirst { it.id == divide.id } + 1]
        val viaList = CampaignContent.predecessorsOf(after.id, flow)
        assertTrue(
            after.id in branches || divide.id !in viaList,
            "${after.id} follows a branching chapter in list order but isn't one of its branches",
        )
    }

    /** Every linear chapter still unlocks from the one before it — the branch must not disturb the spine. */
    @Test
    fun linearChaptersStillUnlockFromTheirPredecessor() {
        val order = CampaignContent.CHAPTERS
        for (i in 1 until order.size) {
            assertEquals(listOf(order[i - 1].id), CampaignContent.predecessorsOf(order[i].id, order))
        }
        assertEquals(emptyList(), CampaignContent.predecessorsOf(order.first().id, order))
    }

    /** Sanity: a chapter with no `next` still falls through to list order. */
    @Test
    fun aLinearChapterFollowsTheList() {
        val a = Chapter("a", 1, "A", "", CytoScenario.DEFAULT, listOf(Step("x", Gate.Next)))
        val b = Chapter("b", 1, "B", "", CytoScenario.DEFAULT, listOf(Step("y", Gate.Next)))
        val ctrl = CytoController()
        val dir = CampaignDirector()
        dir.chapters = listOf(a, b)
        var entered: String? = null
        dir.onChapterEntered = { c, _ -> entered = c.id }
        dir.start(a, ctrl)
        dir.update(query(conflicts = true), emptySet())
        dir.tryAdvance(ctrl)
        assertEquals("b", entered)
    }
}
