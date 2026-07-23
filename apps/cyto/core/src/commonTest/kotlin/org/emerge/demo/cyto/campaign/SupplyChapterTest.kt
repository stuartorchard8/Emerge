package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The competition arc is two chapters. `ch02-conversion` ends on the die-off - the player retargets CONVERT
 * onto the molecule division makes, and the lineage collapses for it - and `ch03-supply` picks up from there
 * with the separate lesson: the cell now depends on a molecule it only ever made by accident, so it has to
 * start making it on purpose.
 */
class SupplyChapterTest {

    private val competition = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.BRANCH_CONVERSION }
    private val supply = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.BRANCH_SUPPLY }

    private fun query(convertChem: String?, convertProduct: String?) = CampaignQuery(
        WorldStats(
            0L, 1, mapOf(CellType.Collector to 1), 3000, emptySet(),
            FocusedCell(CellType.Collector, 3000, mapOf("rg" to 100)),
            Lineage(
                geneCount = 2, convertChem = convertChem, convertProduct = convertProduct,
                hasDivide = true, mitosisProduct = "rg", divideFuelConflicts = true,
            ),
        ),
        paused = false, selectedGenome = null,
    )

    /** The split point: the collapse is an ending, not a mid-chapter stumble. */
    @Test
    fun competitionEndsOnTheDieOff() {
        val last = competition.steps.last()
        val gate = last.gate as Gate.World
        assertEquals("What could go wrong?", gate.desc)
        assertTrue(gate.met(deadQuery()), "gates on the watched cell, not an empty world")
        assertTrue(last.autoAdvance, "the collapse itself segues into Supply")
    }

    private fun deadQuery() = CampaignQuery(
        WorldStats(0L, 40, mapOf(CellType.Collector to 40), 3000, emptySet(), null, null, watchedCellDied = true),
        paused = false, selectedGenome = null,
    )

    @Test
    fun competitionLeadsToSupply() {
        assertEquals(listOf(CampaignContent.BRANCH_SUPPLY), competition.branchesTo)
        assertEquals(CampaignContent.BRANCH_SUPPLY, competition.next?.invoke(query("rg", null)))
        assertEquals(
            listOf(CampaignContent.BRANCH_CONVERSION),
            CampaignContent.predecessorsOf(CampaignContent.BRANCH_SUPPLY, CampaignContent.PLAYABLE_CHAPTERS),
        )
    }

    /** Supply carries on in the same world - the player's colony, mid-collapse, is the starting condition. */
    @Test
    fun supplyContinuesTheWorldItInherits() {
        assertFalse(supply.startsFreshWorld)
        assertTrue(supply.steps.first().allow.allows(Control.Spawn), "they may need to put a cell back")
    }

    /** Its one edit: the CONVERT gene powered by the reaction that produces what it now grows on. */
    @Test
    fun theSupplyGoalWantsConvertPoweredByItsOwnProduct() {
        val gate = supply.steps.mapNotNull { it.gate as? Gate.World }.single { it.desc.startsWith("Update the first gene to BOND") }
        assertFalse(gate.met(query("rg", null)), "still on light - nothing makes the molecule on purpose")
        assertFalse(gate.met(query("rg", "")), "BOND picked but no pair chosen")
        assertTrue(gate.met(query("rg", "rg")))
    }

    /** Both halves of the arc are reachable from the menu. */
    @Test
    fun bothHalvesArePlayable() {
        val ids = CampaignContent.PLAYABLE_CHAPTERS.map { it.id }
        assertTrue(CampaignContent.BRANCH_CONVERSION in ids)
        assertTrue(CampaignContent.BRANCH_SUPPLY in ids)
    }
}
