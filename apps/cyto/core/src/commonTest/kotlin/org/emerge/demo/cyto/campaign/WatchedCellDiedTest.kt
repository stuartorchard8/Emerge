package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.test.Test
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
