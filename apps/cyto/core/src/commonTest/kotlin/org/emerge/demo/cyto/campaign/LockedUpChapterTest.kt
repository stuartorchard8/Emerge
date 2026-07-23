package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locked Up gives the competition lineage the recycling gene, and with it the shape the photosynthesis path
 * already has. Measured on the Supply end state, from the moment the gene lands:
 *
 *   - without it: the waste sits frozen at 586 for the rest of the cell's life, dead in ~5,000 ticks.
 *   - with it:    586 -> 95, biomass recovers 2700 -> 2975 before declining, dead in ~6,600.
 *
 * The gates below sit inside those numbers.
 */
class LockedUpChapterTest {

    private val chapter = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.BRANCH_LOCKED_UP }

    private fun gate(prefix: String): Gate.World =
        chapter.steps.mapNotNull { it.gate as? Gate.World }.single { it.desc.startsWith(prefix) }

    private fun query(waste: Int, genes: Int = 2, recycles: Boolean = false, reserve: Int? = null) = CampaignQuery(
        WorldStats(
            0L, 1, mapOf(CellType.Collector to 1), 2800, emptySet(),
            FocusedCell(CellType.Collector, 2800, mapOf("r" to 1, "g" to 2, "rg" to waste)),
            Lineage(
                geneCount = genes, convertChem = "rg", convertProduct = "rg", hasDivide = true,
                mitosisProduct = "rg", hasPhotosynthesis = recycles, recycleReserve = reserve,
                divideFuelConflicts = true,
            ),
        ),
        paused = false, selectedGenome = null,
    )

    @Test
    fun theOpeningGoalFindsTheStandingPile() {
        val g = gate("Find the")
        assertFalse(g.met(query(95)), "an already-drained cell is not the one to look at")
        assertTrue(g.met(query(586)), "the measured standing pile")
    }

    @Test
    fun theRecyclerGoalNeedsTheLightPoweredBreak() {
        val g = gate("Update the gene to BREAK")
        assertFalse(g.met(query(586, genes = 3)), "a third gene alone is not the right one")
        assertTrue(g.met(query(586, genes = 3, recycles = true)))
    }

    /** The reserve is taught up front here rather than through a die-off — without it the gene strips the
     *  cytoplasm bare and CONVERT, which needs the waste present to fire at all, goes inert. */
    @Test
    fun theReserveIsPartOfTheGeneNotAnAfterthought() {
        val g = gate("Block the BREAK gene")
        assertFalse(g.met(query(586, genes = 3, recycles = true)), "no condition yet")
        assertFalse(g.met(query(586, genes = 3, recycles = true, reserve = 0)), "> 0 still takes the lot")
        assertTrue(g.met(query(586, genes = 3, recycles = true, reserve = 100)))
        assertTrue(g.met(query(586, genes = 3, recycles = true, reserve = 150)), "their own reasoning is fine too")
    }

    @Test
    fun theReclaimGoalWatchesThePileComeDown() {
        val g = gate("Reclaim the")
        assertFalse(g.met(query(586, genes = 3, recycles = true, reserve = 100)), "not started yet")
        assertFalse(g.met(query(474, genes = 3, recycles = true, reserve = 100)), "on its way but not there")
        assertTrue(g.met(query(95, genes = 3, recycles = true, reserve = 100)), "the measured floor")
    }

    /** The two halves of this chapter must not be satisfiable by one reading, or it deadlocks on itself. */
    @Test
    fun findingThePileAndClearingItDoNotOverlap() {
        for (w in listOf(0, 95, 200, 301, 586)) {
            val q = query(w, genes = 3, recycles = true, reserve = 100)
            assertFalse(gate("Find the").met(q) && gate("Reclaim the").met(q), "waste=$w satisfies both")
        }
    }

    @Test
    fun supplyLeadsHere() {
        val supply = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.BRANCH_SUPPLY }
        assertEquals(listOf(CampaignContent.BRANCH_LOCKED_UP), supply.branchesTo)
        assertEquals(CampaignContent.BRANCH_LOCKED_UP, supply.next?.invoke(query(586)))
        assertEquals(
            listOf(CampaignContent.BRANCH_SUPPLY),
            CampaignContent.predecessorsOf(CampaignContent.BRANCH_LOCKED_UP, CampaignContent.PLAYABLE_CHAPTERS),
        )
    }

    /**
     * The reconvergence itself: both branches now end on a lineage that grows, divides and recycles, with a
     * reserve on the recycler. They differ only in which chemical each grows on, which is not something gene
     * groups distinguish — that is what makes Act II able to teach subsystems instead of individual genes.
     */
    @Test
    fun bothPathsEndOnTheSameShape() {
        val leftovers = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.LEFTOVERS }
        for (ch in listOf(leftovers, chapter)) {
            val descs = ch.steps.mapNotNull { (it.gate as? Gate.World)?.desc }
            assertTrue(
                descs.any { it.startsWith("Block the BREAK gene") },
                "${ch.id} must end its lineage with a reserve on the recycler",
            )
        }
        assertEquals(emptyList(), chapter.branchesTo, "both paths stop here until Act II is reworked")
        assertEquals(emptyList(), leftovers.branchesTo)
    }
}
