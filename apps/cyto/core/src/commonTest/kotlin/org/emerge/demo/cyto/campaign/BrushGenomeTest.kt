package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.GeneCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a Spawn tap places. The precedence used to be copied into each host and had drifted; worse, every
 * scratch chapter declares `spawnGenome = emptyList()`, so the last word was "a gene-less cell" for a player
 * who had a perfectly good lineage. Skipping a chapter made that reachable: skip authors nothing and selects
 * nothing, so the next chapter handed out blank cells.
 */
class BrushGenomeTest {

    private val mitosis = Gene(EnergySource.Light, GeneCondition(emptyList()), GeneAction(ActionType.Mitosis))
    private val convert = Gene(EnergySource.Light, GeneCondition(emptyList()), GeneAction(ActionType.Convert, "r"))

    private fun chapter(
        id: String, spawn: List<Gene>? = emptyList(), copiesHeld: Boolean = false,
    ) = Chapter(
        id, 1, id, "", CytoScenario.DEFAULT, listOf(Step("x", Gate.Next)),
        spawnGenome = spawn, spawnCopiesHeldCell = copiesHeld,
    )

    private fun started(ch: Chapter): Pair<CampaignDirector, CytoController> {
        val c = CytoController()
        c.tick(0f)
        val d = CampaignDirector()
        d.start(ch, c)
        return d to c
    }

    /** A chapter that names a genome means it — including Genesis, whose opening beat is a GENE-LESS cell. */
    @Test
    fun anAuthoredSpawnGenomeWins() {
        val (d, c) = started(chapter("fixed", spawn = listOf(convert)))
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        assertEquals(listOf(convert), d.brushGenome(c))
    }

    /**
     * The bug Stu hit, driven the way he hit it: skip straight past a chapter and into the next one. Skip
     * authors nothing and selects nothing, so the boundary snapshot is the only record that the world has a
     * lineage in it at all — and without it the next chapter hands out blank cells.
     */
    @Test
    fun skippingAChapterStillPlacesTheLineage() {
        val c = CytoController()
        c.newGame(
            CytoScenario.DEFAULT.copy(
                founders = listOf(FounderSpec(CellType.Collector, 1, genome = listOf(convert, mitosis))),
            ),
        )
        c.tick(0f)
        val d = CampaignDirector()
        d.chapters = listOf(chapter("a"), chapter("b"))
        d.start(chapter("a"), c)
        assertTrue(d.tryAdvance(c), "Skip past the whole of chapter a")

        assertNull(c.lastAuthoredGenome, "the player never authored anything - they skipped")
        assertEquals(2, d.brushGenome(c)?.size, "so the brush falls back to the lineage in the world")
    }

    /** With nothing carried and nothing built, an empty chapter genome is still the answer — there is no
     *  lineage to place. */
    @Test
    fun withNothingBuiltItStaysEmpty() {
        val c = CytoController()
        c.newGame(CytoScenario.DEFAULT.copy(founders = emptyList()))
        c.tick(0f)
        val d = CampaignDirector()
        d.start(chapter("scratch"), c)
        assertEquals(emptyList(), d.brushGenome(c))
    }

    /** What they authored beats an empty chapter genome — the case that bit every scratch chapter. */
    @Test
    fun whatTheyAuthoredBeatsAnEmptyChapterGenome() {
        val (d, c) = started(chapter("scratch"))
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        assertTrue(d.brushGenome(c)!!.any { it.action.type == ActionType.Mitosis })
    }

    /** A live selection is fresher still — Ch9's "last-modified brush". */
    @Test
    fun aSelectedCellsGenomeIsFreshest() {
        val ch = chapter("copies", copiesHeld = true)
        val (d, c) = started(ch)
        val id = c.tick(0f).state.components.getTable<CytoCellComponent>().asMap().keys.first()
        c.focus(id)
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        assertEquals(c.heldGenome(), d.brushGenome(c))
    }
}
