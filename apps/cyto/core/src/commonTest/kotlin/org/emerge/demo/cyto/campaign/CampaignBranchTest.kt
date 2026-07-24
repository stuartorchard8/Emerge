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

    /**
     * A world that satisfies every gate in `ch01-divide` at once, so one query can walk the whole chapter.
     * That includes `cellCount == 0`: the chapter's own arc runs the lineage to extinction (dividing below
     * the rupture floor) before the player fixes it, and this test is about the routing, not the arc.
     */
    private fun query(conflicts: Boolean?, focused: Boolean = true) = CampaignQuery(
        WorldStats(
            0L, 0, emptyMap(), 100, emptySet(),
            if (!focused) null else FocusedCell(CellType.Collector, 100, emptyMap()),
            Lineage(
                geneCount = 2, convertChem = "r", convertBiomassCap = 3000,
                divideBiomassMinimum = 2000,
                hasDivide = true, divideProduct = "rg", divideFuelConflicts = conflicts,
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
            val before = dir.snapshot()?.stepIndex
            dir.update(if (i == ch.steps.lastIndex) atTheEnd else q, PlayerAction.entries.toSet())
            // A Step.autoAdvance beat has already moved on inside update() — clicking Next on top of that
            // would skip the step it just landed on.
            val movedItself = dir.snapshot()?.let { it.chapterId == ch.id && it.stepIndex != before } == true
            if (!movedItself) assertTrue(dir.tryAdvance(ctrl), "step ${i + 1} of ${ch.id} did not advance")
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

    /**
     * The whole point of routing off the lineage rather than the selected cell: a player who sat and watched
     * until their colony died out — or simply clicked away — still goes to the chapter their genome is about.
     * Nothing is selected here and the conflicting build still routes to its own chapter.
     */
    @Test
    fun anEmptyWorldStillRoutesByWhatThePlayerBuilt() {
        assertEquals(
            CampaignContent.BRANCH_CONVERSION,
            destinationOf(divide, query(conflicts = true), atTheEnd = query(conflicts = true, focused = false)),
        )
    }

    /** Only a genome with nothing to compare — no reaction chosen at all — falls to the stable path. */
    @Test
    fun anUnreadableGenomeFallsToTheStablePath() {
        assertEquals(
            CampaignContent.BRANCH_PHOTOSYNTHESIS,
            destinationOf(divide, query(conflicts = true), atTheEnd = query(conflicts = null)),
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
