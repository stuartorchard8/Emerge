package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "The cell you were watching died" is a different question from "the world is empty", and the campaign
 * needs the first one. A beat that tells the player to watch one cell fail is describing an edit they made
 * to that cell — its siblings keep running the genome they already had, so by the time a chapter is going
 * well there can be hundreds of them and an empty-world gate would never fire at all.
 *
 * The reading is awkward to recover after the fact: `heldCellPosition` drops `lastHeldId` the moment the
 * cell is gone, and the hosts call it every frame, so a death and a deselection both look like a null
 * selection. Hence the recorded flag, and hence these tests.
 */
class WatchedCellDiedTest {

    private fun oneCell(): Pair<CytoController, EntityId> {
        val c = CytoController()
        val frame = c.tick(0f)
        val id = frame.state.components.getTable<CytoCellComponent>().asMap().keys.first()
        c.focus(id)
        return c to id
    }

    private fun kill(c: CytoController, id: EntityId) {
        val pos = c.tick(0f).state.components.getTable<TransformComponent>().asMap().getValue(id).pos
        c.tap(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y), TouchMode.Delete, CellType.Collector)
        repeat(2) { c.tick(0.25f) }
    }

    @Test
    fun aLiveSelectionHasNotDied() {
        val (c, _) = oneCell()
        assertFalse(c.worldStats().watchedCellDied)
    }

    @Test
    fun theWatchedCellDyingIsRecorded() {
        val (c, id) = oneCell()
        kill(c, id)
        assertTrue(c.worldStats().watchedCellDied, "the cell the player was watching is gone")
    }

    /** The whole point: it reports on the cell, not on the world. A chapter beat lands its edit on ONE
     *  cell, so by the time things are going well the world is full of unedited siblings. */
    @Test
    fun itFiresEvenWhileTheRestOfTheLineageThrives() {
        val (c, id) = oneCell()
        repeat(3) { i -> c.spawn(20f + i * 4f, 20f, CellType.Collector) }
        repeat(4) { c.tick(1f / 64f) }
        val alive = c.worldStats().cellCount
        assertTrue(alive > 1, "expected company in the world, got $alive")

        kill(c, id)
        val stats = c.worldStats()
        assertTrue(stats.cellCount > 0, "the siblings are still going")
        assertFalse(CampaignQuery(stats, paused = false, selectedGenome = null).extinct)
        assertTrue(stats.watchedCellDied, "but the watched one is gone")
    }

    /**
     * The hosts call [CytoController.pruneDeadSelection] once a frame, and it used to route through
     * `clearSelection` — which resets the flag, because a deliberate deselect is not a death. So the death
     * was recorded and wiped in the same frame, and `Competition 5/5` sat there forever waiting for a cell
     * that had already died. Prune has to REPORT the death, not erase it.
     */
    @Test
    fun pruningTheDeadSelectionDoesNotEraseTheDeath() {
        val (c, id) = oneCell()
        repeat(3) { i -> c.spawn(20f + i * 4f, 20f, CellType.Collector) }
        repeat(4) { c.tick(1f / 64f) }
        kill(c, id)

        repeat(3) { c.pruneDeadSelection() }
        assertTrue(c.worldStats().watchedCellDied, "the death has to survive the per-frame prune")
    }

    /** Deselecting is not a death — both leave nothing focused, and a beat waiting on a death must not be
     *  satisfied by the player simply clicking away. */
    @Test
    fun clearingTheSelectionIsNotADeath() {
        val (c, _) = oneCell()
        c.clearSelection()
        c.tick(0f)
        assertFalse(c.worldStats().watchedCellDied)
    }

    /** And picking another cell moves the question onto that one. */
    @Test
    fun selectingAnotherCellResetsIt() {
        val (c, id) = oneCell()
        repeat(3) { i -> c.spawn(20f + i * 4f, 20f, CellType.Collector) }
        repeat(4) { c.tick(1f / 64f) }
        kill(c, id)
        assertTrue(c.worldStats().watchedCellDied)

        val other = c.tick(0f).state.components.getTable<CytoCellComponent>().asMap().keys.first()
        c.focus(other)
        assertFalse(c.worldStats().watchedCellDied, "the new selection is alive")
    }

    // ── What the coach does about it ─────────────────────────────────────────────────────────────────

    /** A world of [others] cells with a death on the books and nothing selected. */
    private fun afterADeath(others: Int = 8) = CampaignQuery(
        WorldStats(0L, others, mapOf(CellType.Collector to others), 3000, emptySet(),
            focused = null, lineage = Lineage(geneCount = 3), watchedCellDied = true),
        paused = false, selectedGenome = null,
    )

    private fun directorOn(step: Step): CampaignDirector {
        val dir = CampaignDirector()
        dir.start(
            Chapter("t", 1, "T", "", org.emerge.demo.cyto.sim.CytoScenario.DEFAULT, listOf(step)),
            CytoController(),
        )
        return dir
    }

    /** A beat waiting on a reading from the selected cell is stranded by the death, so the coach offers
     *  another cell. */
    @Test
    fun aGoalThatNeedsASelectionGetsTheOffer() {
        val dir = directorOn(Step("watch it", Gate.World("clear it", met = { (it.focused?.biomass ?: 0) > 100 })))
        dir.update(afterADeath(), emptySet())
        assertTrue(dir.watchedCellOffer)
    }

    /**
     * ...but a beat that never reads the selection is untouched by the death, and must not be interrupted to
     * discuss it. This is why the offer probes the gate instead of firing on the death alone: a player whose
     * long-forgotten selection dies while a colony grows around them is not stuck, and telling them to pick
     * another cell would be noise over a goal that is going fine.
     */
    @Test
    fun aGoalThatIgnoresTheSelectionDoesNot() {
        val dir = directorOn(Step("grow", Gate.World("grow to 20", met = { it.cellCount >= 20 })))
        dir.update(afterADeath(), emptySet())
        assertFalse(dir.watchedCellOffer, "nothing about this goal has become impossible")
        assertEquals("grow", dir.snapshot()!!.text)
    }

    /** A beat whose goal IS the death has just been satisfied by it. Talking the player out of it there
     *  would replace the payoff copy with a recovery offer for something that went right. */
    @Test
    fun aGoalThatWantedTheDeathDoesNot() {
        val dir = directorOn(Step("it will fail", Gate.World("watch it die", met = { it.watchedCellDied })))
        dir.update(afterADeath(), emptySet())
        assertTrue(dir.gateReady)
        assertFalse(dir.watchedCellOffer)
    }

    /** An empty world is the bigger problem, and has its own offer with its own way out (place a cell, not
     *  pick one). Two coaches talking at once would be worse than either. */
    @Test
    fun extinctionOutranksIt() {
        val dir = directorOn(Step("watch it", Gate.World("clear it", met = { (it.focused?.biomass ?: 0) > 100 })))
        dir.update(afterADeath(others = 0), emptySet())
        assertTrue(dir.extinctionOffer, "nothing left alive at all")
        assertFalse(dir.watchedCellOffer, "so the offer is a cell to PLACE, not one to pick")
    }

    /**
     * The authored case of the above: Competition's last beat asks the player to watch their upgraded cell
     * fail, and gates on precisely that death. It is the one place in the campaign where this is the *win*,
     * so the recovery offer must stay out of its way.
     */
    @Test
    fun competitionsClosingBeatIsStillAllowedToWantTheDeath() {
        val conversion = org.emerge.demo.cyto.host.CampaignContent.PLAYABLE_CHAPTERS
            .first { it.id == org.emerge.demo.cyto.host.CampaignContent.BRANCH_CONVERSION }
        val dir = directorOn(conversion.steps.last())
        dir.update(afterADeath(), emptySet())
        assertTrue(dir.gateReady, "the death IS the goal here")
        assertFalse(dir.watchedCellOffer)
    }

    /**
     * Genesis in miniature: a beat whose goal IS the death, auto-advancing into one that wants a selection.
     * The controller remembers the death until a living cell is picked, so the next step opened by offering
     * to recover from a rupture the player had just been walked through on purpose.
     */
    @Test
    fun aDeathTheLastStepAskedForDoesNotFollowThePlayerIntoTheNext() {
        val dir = CampaignDirector()
        dir.start(
            Chapter(
                "t", 1, "T", "", org.emerge.demo.cyto.sim.CytoScenario.DEFAULT,
                listOf(
                    Step("it will die", Gate.World("watch it die", met = { it.watchedCellDied }), autoAdvance = true),
                    Step("now place another", Gate.World("select one", met = { (it.focused?.biomass ?: 0) > 100 })),
                ),
            ),
            CytoController(),
        )
        dir.update(afterADeath(), emptySet())
        assertEquals("now place another", dir.snapshot()!!.text, "the death advanced the chapter")
        assertFalse(dir.watchedCellOffer, "and belongs to the step it just left")

        // A fresh death, once this step has seen a world without one, is this step's problem and does offer.
        dir.update(
            CampaignQuery(
                WorldStats(0L, 8, mapOf(CellType.Collector to 8), 3000, emptySet(),
                    focused = null, lineage = Lineage(geneCount = 3), watchedCellDied = false),
                paused = false, selectedGenome = null,
            ),
            emptySet(),
        )
        dir.update(afterADeath(), emptySet())
        assertTrue(dir.watchedCellOffer)
    }

    /** A rebuilt world carries no history of the last one. */
    @Test
    fun aFreshWorldHasNoDeath() {
        val (c, id) = oneCell()
        kill(c, id)
        assertTrue(c.worldStats().watchedCellDied)
        c.newGame(org.emerge.demo.cyto.sim.CytoScenario.DEFAULT)
        assertFalse(c.worldStats().watchedCellDied)
    }
}
