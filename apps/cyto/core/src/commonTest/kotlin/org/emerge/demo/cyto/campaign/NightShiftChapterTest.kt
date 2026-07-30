package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Night Shift moves the CONVERT gene off daylight and onto the reaction that already funds division. Its
 * goals all read the selected cell across a day/night cycle, so each one is pinned to a measurement:
 *
 *   - CONVERT on Light: one night takes a capped cell from 2999 to 2042 biomass, and no waste ever forms.
 *   - CONVERT on `BOND`: 2999 to 2637, with the waste climbing to ~767 by dawn and back to 1 by morning.
 *
 * The thresholds below sit inside those swings rather than on them.
 */
class NightShiftChapterTest {

    private val chapter = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.NIGHT_SHIFT }

    private fun gate(prefix: String): Gate.World =
        chapter.steps.mapNotNull { it.gate as? Gate.World }.single { it.desc.startsWith(prefix) }

    /** One cell of the lineage this chapter starts from: grows on `r`, divides on `g`+`b`, recycles the
     *  `gb` that leaves behind. [convertFuel] is the reading the chapter's own edit changes. */
    private fun query(biomass: Int, waste: Int, convertFuel: String? = null) = CampaignQuery(
        WorldStats(
            0L, 8, mapOf(CellType.Collector to 8), biomass, emptySet(),
            FocusedCell(CellType.Collector, biomass, mapOf("r" to 400, "g" to 300, "b" to 300, "gb" to waste)),
            Lineage(
                geneCount = 3, convertChem = "r", convertProduct = convertFuel, convertBiomassCap = 3000,
                divideBiomassMinimum = 2000, hasDivide = true, divideProduct = "gb",
                hasPhotosynthesis = true, divideFuelConflicts = false,
            ),
        ),
        paused = false, selectedGenome = null,
    )

    @Test
    fun theOpeningGoalWaitsForTheNightDip() {
        val g = gate("Watch a cell lose ground")
        assertFalse(g.met(query(2999, 0)), "a cell at its cap has not lost anything yet")
        assertTrue(g.met(query(2042, 0)), "the measured bottom of a night on light-powered growth")
    }

    /** A dead cell is not the lesson. Biomass 0 means the selection is gone, not that the player watched
     *  it shrink, and passing there would skip the beat on the way to an empty world. */
    @Test
    fun aDeadCellDoesNotSatisfyTheNightDip() {
        assertFalse(gate("Watch a cell lose ground").met(query(0, 0)))
    }

    @Test
    fun theEditGoalNeedsConvertOnTheDivideReaction() {
        val g = gate("Power the GROW gene")
        assertFalse(g.met(query(3000, 0, convertFuel = null)), "still on light")
        assertFalse(g.met(query(3000, 0, convertFuel = "")), "BOND chosen but no pair picked yet")
        assertTrue(g.met(query(3000, 0, convertFuel = "gb")))
    }

    /** Deliberately the SAME reaction as the divide gene, not merely any bond: the point of the chapter is
     *  one chemistry funding the whole genome, and it is what makes both branches converge on one shape. */
    @Test
    fun someOtherBondIsNotTheGoal() {
        assertFalse(gate("Power the GROW gene").met(query(3000, 0, convertFuel = "rg")))
    }

    @Test
    fun theBuildUpGoalWatchesWasteClimbOvernight() {
        val g = gate("Let ")
        assertFalse(g.met(query(3000, 1, convertFuel = "gb")), "morning: nothing has accumulated")
        assertTrue(g.met(query(2900, 767, convertFuel = "gb")), "the measured pile by dawn")
    }

    @Test
    fun theClearGoalWatchesItGoAgainByMorning() {
        val g = gate("Clear the night")
        assertFalse(g.met(query(2900, 767, convertFuel = "gb")), "still full")
        assertTrue(g.met(query(2999, 1, convertFuel = "gb")))
    }

    /** Both halves of the sawtooth have to be reachable in one cycle, or the chapter deadlocks: the build-up
     *  goal and the clear goal must not be satisfiable by the same reading. */
    @Test
    fun theTwoHalvesOfTheSawtoothDoNotOverlap() {
        for (waste in listOf(0, 1, 99, 201, 767)) {
            val q = query(2900, waste, convertFuel = "gb")
            assertFalse(
                gate("Let ").met(q) && gate("Clear the night").met(q),
                "waste=$waste satisfies both halves at once",
            )
        }
    }

    /** Exhaust leads HERE, and must say so: the other branch sits next in the flat list, so falling through
     *  to list order would hand this player the chapter their genome is not about. */
    @Test
    fun exhaustLeadsToTheNightShift() {
        val exhaust = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.BRANCH_PHOTOSYNTHESIS }
        assertEquals(listOf(CampaignContent.NIGHT_SHIFT), exhaust.branchesTo)
        assertEquals(CampaignContent.NIGHT_SHIFT, exhaust.next?.invoke(query(3000, 0, convertFuel = "gb")))
    }

    /** And it unlocks from Exhaust alone — not from whatever happens to precede it in the list. */
    @Test
    fun itUnlocksFromExhaust() {
        assertEquals(
            listOf(CampaignContent.BRANCH_PHOTOSYNTHESIS),
            CampaignContent.predecessorsOf(CampaignContent.NIGHT_SHIFT, CampaignContent.PLAYABLE_CHAPTERS),
        )
    }

    /** Night Shift names its successor rather than falling through to list order — the conversion branch is
     *  further down the same list, and a photosynthesis player must never land in it. */
    @Test
    fun itNamesItsSuccessorRatherThanFallingThrough() {
        assertEquals(listOf(CampaignContent.LEFTOVERS), chapter.branchesTo)
        val flow = CampaignContent.PLAYABLE_CHAPTERS
        val after = flow[flow.indexOfFirst { it.id == CampaignContent.NIGHT_SHIFT } + 1]
        assertTrue(
            after.id == CampaignContent.LEFTOVERS ||
                CampaignContent.NIGHT_SHIFT !in CampaignContent.predecessorsOf(after.id, flow),
            "${after.id} follows Night Shift in list order but is not where it leads",
        )
    }
}
