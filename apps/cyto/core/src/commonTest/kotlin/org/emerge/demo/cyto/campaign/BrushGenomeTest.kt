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
import org.emerge.demo.cyto.sim.TouchMode
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

    private val divide = Gene(EnergySource.Light, GeneCondition(emptyList()), GeneAction(ActionType.Divide))
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
        c.addHeldGenes(listOf(divide))
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
                founders = listOf(FounderSpec(CellType.Collector, 1, genome = listOf(convert, divide))),
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
        c.addHeldGenes(listOf(divide))
        c.tick(0f)
        assertTrue(d.brushGenome(c)!!.any { it.action.type == ActionType.Divide })
    }

    /**
     * What they authored outranks what they have clicked on. `lastAuthoredGenome` is recorded on an edit and
     * not on a selection exactly so it means their intent, and the brush has to honour that — otherwise
     * clicking a passing cell silently re-points it. The gene-less case is the sharp one: an empty genome is
     * a perfectly good non-null answer, so a selection-first order hands out blank cells.
     */
    @Test
    fun lastAuthoredOutranksWhateverIsSelected() {
        // Two gene-less founders: author onto one, then click the other.
        val c = CytoController()
        // `genome = null` on a FounderSpec means "the seeder's default starter", which is NOT empty - name it.
        c.newGame(CytoScenario.DEFAULT.copy(founders = listOf(FounderSpec(CellType.Collector, 2, genome = emptyList()))))
        c.tick(0f)
        val d = CampaignDirector()
        d.start(chapter("scratch"), c)

        val ids = c.tick(0f).state.components.getTable<CytoCellComponent>().asMap().keys.toList()
        c.focus(ids[0])
        c.addHeldGenes(listOf(convert, divide))
        c.tick(0f)
        val authored = c.lastAuthoredGenome!!.size

        c.focus(ids[1])
        assertTrue(c.heldGenome()!!.isEmpty(), "the newly selected cell really is gene-less")
        assertEquals(authored, d.brushGenome(c)?.size, "the brush still carries what they authored")
    }

    /**
     * The brush is only half the story: what a tap on an existing cell *does* is the touch mode, and in the
     * campaign that has to be SET, so tapping a straggler brings it up to the lineage the player authored.
     * [TouchMode.Base] — the sandbox default — would select it and change nothing.
     */
    @Test
    fun aChapterStartsOnTheSetBrushAndHandsBaseBackOnTheWayOut() {
        val (d, _) = started(chapter("scratch"))
        assertEquals(TouchMode.Set, d.consumeDefaultTouchMode(), "entering a chapter picks SET")
        d.stop()
        assertEquals(TouchMode.Base, d.consumeDefaultTouchMode(), "leaving hands the sandbox its own back")
    }

    /**
     * Consumed, not enforced. Once the campaign teaches the modes, a player who picks a different one has to
     * keep it — a host re-applying the default every frame would take it straight back off them.
     */
    @Test
    fun theDefaultIsOfferedOnceOnly() {
        val (d, _) = started(chapter("scratch"))
        assertEquals(TouchMode.Set, d.consumeDefaultTouchMode())
        assertNull(d.consumeDefaultTouchMode(), "nothing more to apply until the chapter changes")
    }

    /** A live selection wins only where the chapter asks for it — Ch9's "last-modified brush". */
    @Test
    fun aSelectedCellsGenomeIsFreshest() {
        val ch = chapter("copies", copiesHeld = true)
        val (d, c) = started(ch)
        val id = c.tick(0f).state.components.getTable<CytoCellComponent>().asMap().keys.first()
        c.focus(id)
        c.addHeldGenes(listOf(divide))
        c.tick(0f)
        assertEquals(c.heldGenome(), d.brushGenome(c))
    }
}
