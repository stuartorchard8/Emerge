package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Press, carry, turn, let go** — `PLAN_machine_relocation.md` increment 2.
 *
 * ⛔ **The world is not edited until the button comes up.** A carry is controller state, exactly as
 * the build cursor is, so every test here checks the deck is untouched mid-gesture and only changes
 * on the drop.
 */
class MoveToolTest {

    private val grid = Grid(16, 14)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private val at = grid.tile(5, 5)
    private val away = grid.tile(11, 9)

    private fun controller(): OutofspaceController {
        val deck = DeckArray(grid)
        deck += Concentrator(at, Direction.Right)
        val state = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = true)
        val c = OutofspaceController(cfg, state)
        c.tool = Tool.Move
        return c
    }

    /** Runs whatever the controller has queued, as a host does once a tick. */
    private fun settle(c: OutofspaceController, ticks: Int = 2) = repeat(ticks) { c.stepOnce() }

    // ── The gesture ──────────────────────────────────────────────────────────

    @Test
    fun `a machine moves when it is let go somewhere it fits`() {
        val c = controller()
        c.apply(at)
        assertNotNull(c.carrying, "pressing on a machine did not pick it up")
        c.carryTo(away)
        assertNotNull(c.state.deck[at], "the machine left the deck before the button came up")

        c.drop()
        settle(c)

        assertNotNull(c.state.deck[away], "it did not land")
        assertNull(c.state.deck[at], "it did not leave")
        assertNull(c.carrying, "the hand is still full")
    }

    /**
     * ⭐ **Letting go somewhere it does not fit is the cancel.** There is no cancel key and no state
     * to be stuck in: the machine is either moved or exactly where it was.
     */
    @Test
    fun `letting go where it does not fit puts it back`() {
        val c = controller()
        c.apply(at)
        c.carryTo(grid.tile(0, 5))   // a 3x3 hanging off the rim

        c.drop()
        settle(c)

        assertNotNull(c.state.deck[at], "it left even though it could not land")
        assertNull(c.carrying, "letting go did not end the carry")
    }

    /** ⛔ And a press that picks nothing up is not a carry at all. */
    @Test
    fun `pressing bare deck picks nothing up`() {
        val c = controller()
        c.apply(grid.tile(1, 1))
        assertNull(c.carrying, "bare deck went into the hand")
    }

    // ── R ────────────────────────────────────────────────────────────────────

    /**
     * ⭐ **`R` turns what is on the cursor, and needs no precedence rule to say which.** A player
     * cannot be holding a brush and carrying a machine at once.
     */
    @Test
    fun `R turns the carried machine and leaves the brush alone`() {
        val c = controller()
        val brushWas = c.brushFacing
        c.apply(at)
        assertEquals(Direction.Right, c.carriedFacing)

        c.rotateBrush()

        assertEquals(Direction.Down, c.carriedFacing, "R did not turn what was being carried")
        assertEquals(brushWas, c.brushFacing, "R turned the brush as well")
    }

    @Test
    fun `R turns the brush again once the machine is put down`() {
        val c = controller()
        val brushWas = c.brushFacing
        c.apply(at)
        c.drop()

        c.rotateBrush()

        assertEquals(brushWas.clockwise, c.brushFacing, "R stopped turning the brush")
    }

    @Test
    fun `a machine lands facing the way it was carried`() {
        val c = controller()
        c.apply(at)
        c.rotateBrush()
        c.carryTo(away)
        c.drop()
        settle(c)

        val landed = assertNotNull(c.state.deck[away])
        assertEquals(Direction.Down, (landed as org.emerge.demo.outofspace.world.machine.DirectedDeckMachine).facing)
    }

    // ── The cursor ───────────────────────────────────────────────────────────

    /**
     * ⛔ **The carried machine draws through the placement cursor**, which is the whole design: the
     * player is choosing where a machine goes, and the cursor already knows how to ask that.
     */
    @Test
    fun `the cursor previews the carried machine, allowed and refused`() {
        val c = controller()
        assertNull(c.planAt(away), "the move tool drew a plan with nothing in hand")

        c.apply(at)

        val fits = assertNotNull(c.planAt(away), "nothing was previewed while carrying")
        assertEquals(Brush.Building(DeckMachineKind.Concentrator), fits.brush)
        assertTrue(fits.allowed, "somewhere clear previewed as refused")
        assertFalse(fits.settingsOnly, "a move previewed as a re-tune")

        val overhang = assertNotNull(c.planAt(grid.tile(0, 5)))
        assertFalse(overhang.allowed, "hanging off the rim previewed as allowed")
    }

    /**
     * ⛔ **Its own tiles do not count as in the way**, and the cursor has to agree with the reducer
     * about that or it reads red for the commonest gesture the tool has.
     */
    @Test
    fun `the cursor allows a nudge onto the machine's own tiles`() {
        val c = controller()
        c.apply(at)

        assertTrue(c.planAt(grid.tile(6, 5))?.allowed == true, "a one-tile nudge previewed as refused")
        assertTrue(c.planAt(at)?.allowed == true, "turning in place previewed as refused")
    }

    // ── Escape ───────────────────────────────────────────────────────────────

    /** Escape puts the machine back down; a second press steps off the tool. */
    @Test
    fun `escape puts a carried machine down before it puts the tool away`() {
        val c = controller()
        c.apply(at)
        c.carryTo(away)

        assertTrue(c.escape(), "escape did nothing while carrying")
        assertNull(c.carrying, "escape did not end the carry")
        assertEquals(Tool.Move, c.tool, "escape put the tool away as well as the machine")
        settle(c)
        assertNotNull(c.state.deck[at], "escaping a carry moved the machine anyway")

        assertTrue(c.escape())
        assertEquals(Tool.Inspect, c.tool, "a second escape did not step off the tool")
    }

    // ── What may not be picked up ────────────────────────────────────────────

    @Test
    fun `a ghost cannot be picked up`() {
        val deck = DeckArray(grid)
        deck.standGhost(Concentrator(at, Direction.Right))
        val state = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        val c = OutofspaceController(cfg, state)
        c.tool = Tool.Move

        c.apply(at)

        assertNull(c.carrying, "a ghost went into the hand")
        assertNull(c.planAt(away), "and the cursor drew it")
    }

    /** Any tile of a machine picks up the machine — the anchor, not the square pressed. */
    @Test
    fun `pressing a corner picks up the whole machine`() {
        val c = controller()
        c.apply(grid.tile(4, 4))
        assertEquals(at, c.carriedFrom, "a press on the corner picked up something else")
    }

    @Test
    fun `nothing is carried by default`() {
        val c = controller()
        assertNull(c.carrying)
        assertEquals(TileIndex.NONE, c.carriedFrom)
    }
}
