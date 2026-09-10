package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.DirectedDeckMachine
import org.emerge.demo.outofspace.world.machine.Furnace
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Storage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Pasting onto something that is already standing** — what a stamped click does to a machine of
 * its own *family*, and what `R` adds to it.
 *
 * Three rules, and they are one design (Stu, 2026-09-10):
 *
 * 1. **The family, not the kind.** A warehouse's filter means exactly what it means on a silo or a
 *    buffer — one [Storage] at three capacities — so a stamp crosses between them. It does not cross
 *    to a furnace, which shares no class with any of them. See `MachineSettings.settingsFamily`.
 * 2. **The target's aim survives.** A paste hands over settings; the machine goes on pointing where
 *    it was pointed, and the cursor draws it doing so — the *target's* footprint at the *target's*
 *    facing, not the brush's.
 * 3. **Unless the player says otherwise, per machine.** `R` while pointing at one steps it to the
 *    next facing it could actually adopt, and a paste then applies that. Which is what keeps
 *    copy-and-paste a way of turning something, without turning everything.
 *
 * ⛔ **Asserted on the deck, through the reducer**, not on the cursor: what the controller is holding
 * proves nothing about what the click does.
 */
class PasteOntoMachineTest {

    private val grid = Grid(20, 14)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** The tuned store everything here copies from: 3×3, pointed [Direction.Left], locked to iron. */
    private val WAREHOUSE = grid.tile(4, 4)

    /** A 1×2 store of the same family at its defaults, pointed the other way. */
    private val BUFFER = grid.tile(12, 4)

    /** A 1×3 store, pointed along the row it lies in — the odd×odd shape that is not a square. */
    private val SILO = grid.tile(12, 9)

    /** The same, hemmed in above and below so that a quarter turn has nowhere to swing. */
    private val BOXED_SILO = grid.tile(16, 11)

    /** Another family entirely, with a dial of its own that no store has. */
    private val OVEN = grid.tile(7, 9)

    /** A store of the same family that has not been delivered its metal yet. */
    private val GHOST_BUFFER = grid.tile(16, 4)

    private val EMPTY_FLOOR = grid.tile(4, 8)

    private val IRON: SpeciesFilter = SpeciesFilter(Species.Iron, pure = true)

    /** A world in creative, so a placement finishes instead of standing there as a ghost. */
    private fun world(): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) { deck += Hull(grid.tile(x, 0)); deck += Hull(grid.tile(x, grid.height - 1)) }
        for (y in 1 until grid.height - 1) { deck += Hull(grid.tile(0, y)); deck += Hull(grid.tile(grid.width - 1, y)) }
        deck += fixtureStorage(WAREHOUSE, Direction.Left, filter = IRON)
        deck += fixtureStorage(BUFFER, Direction.Up, kind = DeckMachineKind.Buffer)
        deck += fixtureStorage(SILO, Direction.Right, kind = DeckMachineKind.Silo)
        deck += fixtureStorage(BOXED_SILO, Direction.Right, kind = DeckMachineKind.Silo)
        deck += Hull(grid.tile(16, 10))
        deck += Hull(grid.tile(16, 12))
        deck.standGhost(fixtureStorage(GHOST_BUFFER, Direction.Up, kind = DeckMachineKind.Buffer))
        deck += Furnace(OVEN, Direction.Right)
        return VesselState(
            grid,
            deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            conduits = Conduits.ofRails(MutableList(grid.size) { null }),
            creative = true,
        )
    }

    private fun controller(): OutofspaceController = OutofspaceController(cfg, world())

    /** Everything a click does, in the order a host does it. */
    private fun OutofspaceController.click(tile: TileIndex) = apply(tile)

    /** A controller holding a copy of the tuned warehouse — the start of nearly every test here. */
    private fun holdingTheWarehouse(): OutofspaceController = controller().also {
        it.inspect(WAREHOUSE, InspectLayer.Deck)
        assertTrue(it.grab(), "there is a warehouse under the inspector")
        assertEquals(Direction.Left, it.brushFacing, "grabbed facing the way the original faces")
    }

    private fun storeAt(c: OutofspaceController, tile: TileIndex): Storage =
        assertNotNull(c.state.machineCovering(tile) as? Storage, "no store at $tile")

    private fun facingAt(c: OutofspaceController, tile: TileIndex): Direction =
        assertNotNull(c.state.machineCovering(tile) as? DirectedDeckMachine, "nothing aimed at $tile").facing

    // ── The family ───────────────────────────────────────────────────────────

    /**
     * The headline: a **warehouse's** filter pastes onto a **buffer**.
     *
     * ⛔ Refused before `PLAN_stamp_by_class.md`, on a guard that compared kinds. A store is one
     * machine at three capacities and the size lives in `Storage.capacity`; what a filter means does
     * not change with the size of the box it is set on.
     */
    @Test
    fun a_warehouse_filter_pastes_onto_a_buffer() {
        val c = holdingTheWarehouse()
        assertNull(storeAt(c, BUFFER).filter, "the fixture starts the buffer unlocked")
        val wasMadeOf = c.state.deck.materialOf(storeAt(c, BUFFER))

        c.click(BUFFER)
        c.stepOnce()

        val tuned = storeAt(c, BUFFER)
        assertEquals(IRON, tuned.filter, "the filter did not cross to a store of another size")
        assertEquals(DeckMachineKind.Buffer, tuned.kind, "the paste resized the machine it landed on")
        assertEquals(wasMadeOf, c.state.deck.materialOf(tuned), "and it is still made of what it was made of")
    }

    /** And the machine it lands on goes on pointing exactly where it pointed. */
    @Test
    fun a_paste_leaves_the_target_pointed_where_it_was() {
        val c = holdingTheWarehouse()
        assertNotEquals(c.brushFacing, facingAt(c, BUFFER), "fixture: the brush and the target disagree")

        c.click(BUFFER)
        c.stepOnce()

        assertEquals(Direction.Up, facingAt(c, BUFFER), "a paste turned the machine it landed on")
    }

    /**
     * ⛔ **A furnace's stamp is not a store's**, and the cursor says so before the click.
     *
     * ⚠️ **This is the check on rule 1.** The furnace and the rocket deliberately share a temperature
     * dial, and a stamp that crossed wherever a field happened to have the same name would carry a
     * kiln's setpoint into a combustion chamber. Family means *class*: one machine at several sizes,
     * not two machines with a dial in common.
     */
    @Test
    fun a_stamp_from_another_family_is_refused() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        assertTrue(c.grab())

        val plan = assertNotNull(c.planAt(WAREHOUSE))
        assertFalse(plan.settingsOnly, "a furnace's settings landed on a warehouse")
        assertFalse(plan.allowed, "and the cursor called it a placement rather than a refusal")

        c.click(WAREHOUSE)
        c.stepOnce()
        assertEquals(IRON, storeAt(c, WAREHOUSE).filter, "the warehouse was disturbed by a click meant for a furnace")
    }

    // ── What the cursor draws ────────────────────────────────────────────────

    /**
     * ⭐ **The preview is the machine that would stand there**, which is the target's shape at the
     * target's facing — not the brush's.
     *
     * Drawn off the brush it showed a 3×3 warehouse pointed left over a 1×2 buffer pointed up: a
     * picture of a placement that is not going to happen, over the top of the machine that is.
     */
    @Test
    fun the_cursor_draws_the_machine_that_would_stand() {
        val c = holdingTheWarehouse()

        val plan = assertNotNull(c.planAt(BUFFER))
        assertTrue(plan.settingsOnly, "a click here re-tunes rather than builds")
        assertTrue(plan.allowed)
        assertEquals(Brush.Building(DeckMachineKind.Buffer), plan.brush, "the cursor drew the brush's kind")
        assertEquals(Direction.Up, plan.facing, "the cursor drew the brush's aim")
        assertEquals(BUFFER, plan.tile, "and it snapped to the machine, not the pointer")
    }

    /** From any tile of it: a buffer is two tiles, and the far one is as much the buffer as the anchor. */
    @Test
    fun the_preview_snaps_to_the_machine_from_any_of_its_tiles() {
        val c = holdingTheWarehouse()
        val nose = grid.tile(grid.xOf(BUFFER), grid.yOf(BUFFER) - 1)
        assertNotEquals(BUFFER, nose, "the fixture must point somewhere off the anchor")

        val plan = assertNotNull(c.planAt(nose))
        assertEquals(BUFFER, plan.tile)
        assertEquals(Direction.Up, plan.facing)
    }

    // ── R, aimed at a machine ────────────────────────────────────────────────

    /**
     * **R over a 1×2 machine flips it end for end**, because a quarter turn asks for a block the
     * other way round and there is no such buffer.
     *
     * ⚠️ **A thruster is this shape too, and is deliberately no different** — an earlier design had
     * engines swinging about their hub, and re-laying a footprint is the move tool's job.
     */
    @Test
    fun r_over_an_oblong_machine_flips_it_end_for_end() {
        val c = holdingTheWarehouse()

        c.rotateBrush(BUFFER)

        assertEquals(Direction.Down, c.brushFacing, "a quarter turn was offered to a 1×2")
        assertEquals(Direction.Down, assertNotNull(c.planAt(BUFFER)).facing, "and the cursor did not show it")

        c.click(BUFFER)
        c.stepOnce()
        assertEquals(Direction.Down, facingAt(c, BUFFER), "the paste did not apply the turn the player asked for")
        assertEquals(IRON, storeAt(c, BUFFER).filter, "and it stopped carrying the settings")
    }

    /** Pressed twice it comes back: two facings is the whole of what an oblong has. */
    @Test
    fun r_twice_over_an_oblong_machine_comes_back() {
        val c = holdingTheWarehouse()
        c.rotateBrush(BUFFER)
        c.rotateBrush(BUFFER)
        assertEquals(Direction.Up, c.brushFacing, "the second press did not step on from the first")
    }

    /** **R over an odd×odd machine steps a quarter turn** — every facing is on offer, a line's too. */
    @Test
    fun r_over_a_square_or_a_line_steps_a_quarter_turn() {
        val c = holdingTheWarehouse()

        c.rotateBrush(SILO)
        assertEquals(Direction.Down, c.brushFacing, "a 1×3 line was flipped rather than pivoted")

        c.click(SILO)
        c.stepOnce()
        assertEquals(Direction.Down, facingAt(c, SILO))
        assertEquals(IRON, storeAt(c, SILO).filter)
    }

    /**
     * ⚠️ **A turn that would not fit is skipped, not offered.** The boxed silo cannot swing across
     * its corridor, so `R` gives it the half turn instead of a preview of a paste that would then
     * quietly do nothing.
     */
    @Test
    fun r_skips_a_turn_the_machine_has_no_room_for() {
        val c = holdingTheWarehouse()

        c.rotateBrush(BOXED_SILO)

        assertEquals(Direction.Left, c.brushFacing, "the quarter turn into a wall was offered anyway")
        c.click(BOXED_SILO)
        c.stepOnce()
        assertEquals(Direction.Left, facingAt(c, BOXED_SILO))
    }

    /**
     * ⭐ **The turn the player asked for is the brush's aim too**, so the next thing they build comes
     * out the way the cursor has been showing all along.
     */
    @Test
    fun the_turn_goes_on_the_brush_as_well() {
        val c = holdingTheWarehouse()
        c.rotateBrush(BUFFER)

        c.click(EMPTY_FLOOR)
        c.stepOnce()

        val placed = assertNotNull(c.state.deck[EMPTY_FLOOR], "nothing was placed on bare floor")
        assertEquals(DeckMachineKind.Warehouse, placed.kind, "a click on bare deck builds the brush, not the last target")
        assertEquals(Direction.Down, (placed as DirectedDeckMachine).facing)
    }

    /**
     * ⛔ **And it is a statement about *that* machine.** Every store on the deck is a kind the brush
     * could re-tune, so an intent carried on the brush alone would turn all of them.
     */
    @Test
    fun the_turn_belongs_to_the_machine_it_was_aimed_at() {
        val c = holdingTheWarehouse()
        c.rotateBrush(BUFFER)

        assertEquals(Direction.Right, assertNotNull(c.planAt(SILO)).facing, "the cursor turned a machine nobody aimed at")
        c.click(SILO)
        c.stepOnce()

        assertEquals(Direction.Right, facingAt(c, SILO), "a paste turned a machine the player never turned")
        assertEquals(IRON, storeAt(c, SILO).filter, "and the settings did not cross")
    }

    /** R with the pointer on bare deck is the plain brush rotate, and it forgets what was aimed at. */
    @Test
    fun r_away_from_a_machine_turns_the_brush_and_forgets() {
        val c = holdingTheWarehouse()
        c.rotateBrush(BUFFER)
        assertEquals(BUFFER, c.reaimed)

        c.rotateBrush(EMPTY_FLOOR)

        assertEquals(TileIndex.NONE, c.reaimed, "the pointer left the machine and the intent stayed")
        assertEquals(Direction.Left, c.brushFacing, "Down's clockwise is Left")
        c.click(BUFFER)
        c.stepOnce()
        assertEquals(Direction.Up, facingAt(c, BUFFER), "a turn the player had walked away from was applied anyway")
    }

    /** A fresh copy is a fresh gesture: C forgets whatever was last aimed at. */
    @Test
    fun c_forgets_what_was_aimed_at() {
        val c = holdingTheWarehouse()
        c.rotateBrush(BUFFER)

        c.inspect(WAREHOUSE, InspectLayer.Deck)
        assertTrue(c.grab())

        assertEquals(TileIndex.NONE, c.reaimed)
        c.click(BUFFER)
        c.stepOnce()
        assertEquals(Direction.Up, facingAt(c, BUFFER))
    }

    /**
     * ⛔ **R does not turn a ghost**, and the settings still paste onto one.
     *
     * The move tool's limitation, for the move tool's reason: a part-built machine cannot be
     * demolished and rebuilt without minting metal it has not been delivered, so the reducer swaps a
     * ghost's machine in place — and an in-place swap leaves the occupancy map and the buffer roles
     * claimed by the facing it used to have.
     */
    @Test
    fun r_over_a_ghost_turns_the_brush_instead() {
        val c = holdingTheWarehouse()

        c.rotateBrush(GHOST_BUFFER)

        assertEquals(TileIndex.NONE, c.reaimed, "a ghost was offered a turn")
        assertEquals(Direction.Up, c.brushFacing, "Left's clockwise is Up")

        c.click(GHOST_BUFFER)
        c.stepOnce()
        assertTrue(c.state.deck.isGhost(GHOST_BUFFER), "the paste finished a machine that had no metal")
        assertEquals(IRON, storeAt(c, GHOST_BUFFER).filter, "a ghost may still be tuned before it arrives")
        assertEquals(Direction.Up, facingAt(c, GHOST_BUFFER))
    }

    /** With nothing to paste, R is the brush rotate it has always been. */
    @Test
    fun r_over_a_machine_with_an_unstamped_brush_turns_the_brush() {
        val c = controller()
        c.tool = Tool.Build
        c.buildMaterial = FIXTURE_MACHINE_METAL
        c.brush = Brush.Building(DeckMachineKind.Warehouse)
        val was = c.brushFacing

        c.rotateBrush(BUFFER)

        assertEquals(was.clockwise, c.brushFacing)
        assertEquals(TileIndex.NONE, c.reaimed)
    }
}
