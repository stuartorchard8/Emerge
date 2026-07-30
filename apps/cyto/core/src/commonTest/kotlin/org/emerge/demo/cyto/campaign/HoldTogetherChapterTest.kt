package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.GeneCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ch06-hold` — the bridge into the rehomed Act II. Two things have to hold: the subsystem it hands out is
 * bound to the player's own chemistry, and the grouping beat accepts whatever they chose to call their work.
 */
class HoldTogetherChapterTest {

    private val chapter = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.REHOMED_HOLD }

    /** The ch05 end state, on a fuel pair that is deliberately NOT the authored autotroph's `r`/`g`. */
    private val settled = lineageOf(GeneCodec.parse("""
        Bond b g : Biomass < 3000 : Convert bg
        Bond b g : Biomass > 2000 : Divide sever
        Light : bg > 100 : Break b g
        Light : bg < 200 : Import bg
    """.trimIndent()))

    private fun query(genome: String) = CampaignQuery(
        WorldStats(
            0L, 4, mapOf(CellType.Collector to 4), 3000, emptySet(),
            FocusedCell(CellType.Collector, 3000, mapOf("bg" to 150)),
            lineageOf(GeneCodec.parse(genome)),
        ),
        paused = false, selectedGenome = null,
    )

    /**
     * **The point of the whole exercise.** The Act II subsystems were authored against `r`/`g`; handed to a
     * `b`/`g` player unchanged they would insert a gene that can never fire.
     */
    @Test
    fun theInsertIsBoundToThePlayersFuelPair() {
        val grouping = assertNotNull(chapter.groupingFor)(settled)
        val insert = grouping.groups.single().insert.single()

        val src = insert.source as EnergySource.FormBond
        assertEquals("b" to "g", src.a to src.b, "the subsystem must burn the player's fuel, not the autotroph's")
        assertEquals(ActionType.Repair, insert.action.type)
        assertTrue(
            insert.condition.clauses.any { (it.lhs as? org.emerge.demo.cyto.sim.Operand.Chem)?.species == "bg" },
            "the gate must read the player's own bond too - a half-rebound gene is the bug this guards",
        )
        assertEquals(CampaignContent.GROUP_HOLD_NAME, insert.group, "inserted genes arrive already tagged")
    }

    /** With no lineage to read it still hands out a working subsystem — the autotroph's own. */
    @Test
    fun withNoLineageItFallsBackToAWorkingSubsystem() {
        val insert = assertNotNull(chapter.groupingFor)(null).groups.single().insert.single()
        assertEquals("r" to "g", (insert.source as EnergySource.FormBond).let { it.a to it.b })
    }

    /**
     * The grouping gate is about whether the work is organised, never about the word chosen. The player names
     * their own subsystem, so a gate on a specific name would fail everyone who picked a different one.
     */
    @Test
    fun theGroupingGateAcceptsAnyNameThePlayerChooses() {
        val gate = chapter.steps.first { (it.gate as? Gate.World)?.desc?.contains("group") == true }.gate as Gate.World

        val ungrouped = """
            Bond b g : Biomass < 3000 : Convert bg
            Bond b g : Biomass > 2000 : Divide sever
        """.trimIndent()
        assertFalse(gate.met(query(ungrouped)), "an untagged heap is what the step is asking them to fix")

        // Two players, two names, both correct.
        for (name in listOf("Survive", "my stuff")) {
            val grouped = ungrouped.lines().joinToString("\n") { "$it : $name" }
            assertTrue(gate.met(query(grouped)), "'$name' must satisfy the gate")
        }

        // Half-done does not count.
        val partial = "Bond b g : Biomass < 3000 : Convert bg : Survive\nBond b g : Biomass > 2000 : Divide sever"
        assertFalse(gate.met(query(partial)), "one gene left outside the group is not organised")
    }

    /** It follows the merge chapter, and offers exactly the one subsystem it teaches. */
    @Test
    fun itFollowsReclaimAndOffersOneSubsystem() {
        assertEquals(
            listOf(CampaignContent.RECLAIM),
            CampaignContent.predecessorsOf(CampaignContent.REHOMED_HOLD, CampaignContent.PLAYABLE_CHAPTERS),
        )
        assertEquals(setOf(CampaignContent.GROUP_HOLD_NAME), chapter.insertableGroups)
    }
}
