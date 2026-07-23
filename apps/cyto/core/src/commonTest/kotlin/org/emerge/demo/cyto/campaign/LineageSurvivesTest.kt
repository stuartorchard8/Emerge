package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The player's work outlives their cells. A lineage can go extinct while they watch — two chapters are
 * *about* that happening — and before this the coach had no answer: every goal keyed on a living cell
 * became unsatisfiable, and the only way on was a Reset the coach never mentioned.
 */
class LineageSurvivesTest {

    private val mitosis = Gene(EnergySource.Light, GeneCondition(emptyList()), GeneAction(ActionType.Mitosis))

    /** A controller with one founder, selected. */
    private fun oneCell(): Pair<CytoController, org.emerge.sim.core.EntityId> {
        val c = CytoController()
        val frame = c.tick(0f)
        val id = frame.state.components.getTable<CytoCellComponent>().asMap().keys.first()
        c.focus(id)
        return c to id
    }

    /** Delete the cell at [id] through the real player path (a Delete tap), and settle. */
    private fun kill(c: CytoController, id: org.emerge.sim.core.EntityId) {
        val pos = c.tick(0f).state.components.getTable<TransformComponent>().asMap().getValue(id).pos
        c.tap(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y), TouchMode.Delete, CellType.Collector)
        repeat(2) { c.tick(0.25f) }
    }

    @Test
    fun authoringAGenomeRecordsIt() {
        val (c, _) = oneCell()
        assertNull(c.lastAuthoredGenome, "nothing authored yet")
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        assertNotNull(c.lastAuthoredGenome)
        assertTrue(c.lastAuthoredGenome!!.any { it.action.type == ActionType.Mitosis })
    }

    /** Selecting a cell is not authoring. The record is of what the player WROTE, so that it means their
     *  intent rather than wherever the mouse last landed. */
    @Test
    fun merelySelectingACellRecordsNothing() {
        val (c, id) = oneCell()
        c.focus(id)
        repeat(3) { c.tick(0.25f) }
        assertNull(c.lastAuthoredGenome)
    }

    @Test
    fun theGenomeOutlivesTheCellItWasWrittenOn() {
        val (c, id) = oneCell()
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        kill(c, id)

        val stats = c.worldStats()
        assertEquals(0, stats.cellCount, "the world should be empty")
        assertNull(stats.focused, "nothing to select")
        val lineage = assertNotNull(stats.lineage, "the genome must survive its cells")
        assertTrue(lineage.hasDivide, "and still read as what the player built")
    }

    /** The state the whole feature exists for: nothing alive, but something to put back. */
    @Test
    fun extinctionWithAGenomeIsAnOfferNotADeadEnd() {
        val (c, id) = oneCell()
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        kill(c, id)

        val dir = CampaignDirector()
        dir.start(
            Chapter("t", 1, "T", "", CytoScenario.DEFAULT, listOf(
                // A step that masks spawning OFF — the offer has to override it, or the player is told to
                // tap and then cannot.
                Step("x", Gate.World("keep a cell", met = { it.cellCount > 0 }), allow = ControlMask.of(Control.Camera)),
            )),
            c,
        )
        dir.update(CampaignQuery(c.worldStats(), paused = false, selectedGenome = null), emptySet())

        assertTrue(dir.extinctionOffer, "extinct with a genome in hand")
        assertTrue(dir.controlMask.allows(Control.Spawn), "the offer must permit the tap it asks for")
        assertTrue(
            dir.snapshot()!!.text.contains("genome is not"),
            "the coach must say so — a headless observer should see the same text the player does",
        )
    }

    /**
     * A step that ASKS for the empty world gets no offer: `ch01-divide` has the player watch their lineage
     * divide itself below the rupture floor, and a recovery banner there would both replace the beat's own
     * copy and send them back to fix a world the next step is about to discuss.
     */
    @Test
    fun aStepWhoseGoalIsExtinctionMakesNoOffer() {
        val (c, id) = oneCell()
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        kill(c, id)

        val dir = CampaignDirector()
        dir.start(
            Chapter("t", 1, "T", "", CytoScenario.DEFAULT, listOf(
                Step("watch it die", Gate.World("die out", met = { it.cellCount == 0 })),
            )),
            c,
        )
        dir.update(CampaignQuery(c.worldStats(), paused = false, selectedGenome = null), emptySet())

        assertTrue(dir.gateReady, "the step's own goal is satisfied")
        assertFalse(dir.extinctionOffer, "the step asked for this death")
        assertEquals("watch it die", dir.snapshot()!!.text)
    }

    /** And it does not linger there: the step that watches a lineage die moves on the moment it does, so the
     *  instruction to push cells around isn't left on screen once there are none left to push. */
    @Test
    fun aStepWhoseGoalIsExtinctionAdvancesItself() {
        val (c, id) = oneCell()
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)

        val dir = CampaignDirector()
        dir.start(
            Chapter("t", 1, "T", "", CytoScenario.DEFAULT, listOf(
                Step("watch it die", Gate.World("die out", met = { it.cellCount == 0 }), autoAdvance = true),
                Step("and now this", Gate.Next),
            )),
            c,
        )
        dir.update(CampaignQuery(c.worldStats(), paused = false, selectedGenome = null), emptySet())
        assertEquals(0, dir.snapshot()!!.stepIndex, "still alive, so still watching")

        kill(c, id)
        dir.update(CampaignQuery(c.worldStats(), paused = false, selectedGenome = null), emptySet())
        assertEquals(1, dir.snapshot()!!.stepIndex, "the death advanced the step with no click")
    }

    /** ...but a lineage that dies while the player is merely READING still gets the net. A [Gate.Next] step
     *  is never "satisfied by" the world, so the suppression above must not reach it. */
    @Test
    fun aDeathDuringAReadingStepStillGetsTheOffer() {
        val (c, id) = oneCell()
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        kill(c, id)

        val dir = CampaignDirector()
        dir.start(Chapter("t", 1, "T", "", CytoScenario.DEFAULT, listOf(Step("x", Gate.Next))), c)
        dir.update(CampaignQuery(c.worldStats(), paused = false, selectedGenome = null), emptySet())

        assertTrue(dir.extinctionOffer)
    }

    /** No genome, no offer: there is nothing to put back, so the coach must not promise one. */
    @Test
    fun extinctionWithoutAGenomeMakesNoOffer() {
        val (c, id) = oneCell()
        kill(c, id)

        val dir = CampaignDirector()
        dir.start(Chapter("t", 1, "T", "", CytoScenario.DEFAULT, listOf(Step("x", Gate.Next))), c)
        dir.update(CampaignQuery(c.worldStats(), paused = false, selectedGenome = null), emptySet())

        assertNull(c.lastAuthoredGenome)
        assertFalse(dir.extinctionOffer)
        assertEquals("x", dir.snapshot()!!.text)
    }

    /** A live selection still wins: the player is working on THAT cell, and the remembered genome is a
     *  fallback, not an override. */
    @Test
    fun aSelectedCellIsPreferredOverTheRememberedGenome() {
        val (c, _) = oneCell()
        c.addHeldGenes(listOf(mitosis))
        c.tick(0f)
        val selected = c.heldGenome()!!
        assertEquals(selected.size, c.worldStats().lineage!!.geneCount)
    }
}
