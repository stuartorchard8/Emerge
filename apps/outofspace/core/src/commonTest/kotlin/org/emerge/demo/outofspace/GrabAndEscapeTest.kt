package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.DirectedDeckMachine
import org.emerge.demo.outofspace.world.machine.Furnace
import org.emerge.demo.outofspace.world.machine.Hull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **C**, and **ESC** — the two keys the build tools are reached through.
 *
 * These are one file because they are one idea: there is a hierarchy of things the player can be
 * holding, C is how you get further into it with something in your hand, and ESC is how you get back
 * out. Tested through the controller and the reducer together, because half of what C does is only
 * true once the world has actually taken the edit — "the second furnace is the same as the first" is
 * a claim about a furnace on the deck, not about a field on a cursor.
 */
class GrabAndEscapeTest {

    private val grid = Grid(16, 12)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** The tuned furnace everything here copies from. Three tiles across, anchored here. */
    private val OVEN = grid.tile(4, 4)

    /** A second furnace of the same kind, left at its defaults, for the copy to land on. */
    private val PLAIN_OVEN = grid.tile(10, 4)

    /** Bare deck with a run of steel track across it, well clear of both furnaces. */
    private val TRACK = grid.tile(2, 9)

    /**
     * ⛔ **Deliberately not the material either furnace is made of.** Half the claims here are about
     * a material *not* travelling where it should not, and a fixture whose two substances were the
     * same would pass those tests with the feature ripped out.
     */
    private val TRACK_METAL: Species = Species.Titanium

    /** A world in creative, so a placement finishes instead of standing there as a ghost. */
    private fun world(): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) { deck += Hull(grid.tile(x, 0)); deck += Hull(grid.tile(x, grid.height - 1)) }
        // ⚠️ The corners belong to the rows above — standing a second plate on one throws.
        for (y in 1 until grid.height - 1) { deck += Hull(grid.tile(0, y)); deck += Hull(grid.tile(grid.width - 1, y)) }
        deck += Furnace(OVEN, Direction.Right, setTemperature = TUNED_KELVIN, dwellTicks = TUNED_DWELL)
        deck += Furnace(PLAIN_OVEN, Direction.Right)
        val rails = MutableList<Segment?>(grid.size) { null }
        rails[TRACK.index] = Segment(Conduit.Rail, material = TRACK_METAL)
        return VesselState(
            grid,
            deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            conduits = Conduits.ofRails(rails),
            creative = true,
        )
    }

    private fun controller(): OutofspaceController = OutofspaceController(cfg, world())

    /** Everything a click does, in the order a host does it — see [OutofspaceController.apply]. */
    private fun OutofspaceController.click(tile: TileIndex) = apply(tile)

    private fun furnaceAt(c: OutofspaceController, tile: TileIndex): Furnace =
        assertNotNull(c.state.machineCovering(tile) as? Furnace, "no furnace at $tile")

    // ── C: taking a copy ─────────────────────────────────────────────────────

    /**
     * The headline: point at a tuned furnace, press C, put one down, and the new one is tuned the
     * same way.
     *
     * ⛔ **Through the reducer, not off the cursor.** That the controller is *holding* a setpoint
     * proves nothing at all — the whole of the old clipboard's failure mode was settings that were
     * captured perfectly and then had to be pasted by a second key nobody pressed. What is asserted
     * is the machine that ends up on the deck.
     */
    @Test
    fun c_then_a_click_builds_the_same_machine_again() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        assertTrue(c.grab(), "there is a furnace under the inspector")
        assertEquals(Tool.Build, c.tool, "C takes the build tool out")

        c.click(EMPTY_FLOOR)
        c.stepOnce()

        val copy = furnaceAt(c, EMPTY_FLOOR)
        assertEquals(TUNED_KELVIN, copy.setTemperature)
        assertEquals(TUNED_DWELL, copy.dwellTicks)
    }

    /** And it is made of what the original is made of, without the player picking anything. */
    @Test
    fun c_takes_the_material_too() {
        val c = controller()
        // A player who has been building in something else entirely, which is the case that matters:
        // grabbing has to *overwrite* the standing choice, not merely fill it in when it is empty.
        c.buildMaterial = Species.Copper
        c.inspect(OVEN, InspectLayer.Deck)
        assertTrue(c.grab())

        assertEquals(c.state.deck.materialOf(furnaceAt(c, OVEN)), c.buildMaterial)
    }

    /**
     * C on the RAIL layer hands over track, in the metal that track is made of — not the deck's.
     *
     * ⚠️ **This is the layer doing the work.** The inspector has already made the player say which
     * of a tile's several things they mean, so C never has to guess; a version that looked at the
     * tile instead would answer "furnace" for every tile of track threaded under one.
     */
    @Test
    fun c_on_a_conduit_layer_takes_the_conduit_and_its_metal() {
        val c = controller()
        c.inspect(TRACK, InspectLayer.Rail)
        assertTrue(c.grab())

        assertEquals(Brush.Run(Conduit.Rail), c.brush)
        assertEquals(TRACK_METAL, c.buildMaterial)
        assertNull(c.stamped, "a length of track has no settings to carry")
    }

    /**
     * On bare air there is nothing to copy — and C still takes the build tool out, with the palette
     * empty.
     *
     * A key that did nothing at all would read as broken, and "build something" is what C means even
     * when the thing under the cursor is a room.
     */
    @Test
    fun c_on_nothing_opens_an_empty_palette() {
        val c = controller()
        c.inspect(EMPTY_FLOOR, InspectLayer.Atmosphere)
        assertFalse(c.grab(), "nothing was picked up")
        assertEquals(Tool.Build, c.tool)
        assertNull(c.brush)
    }

    /** Changing the material after grabbing keeps the settings — "the same machine, in titanium". */
    @Test
    fun changing_the_material_keeps_the_settings() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        c.grab()
        c.buildMaterial = Species.Titanium

        c.click(EMPTY_FLOOR)
        c.stepOnce()

        val copy = furnaceAt(c, EMPTY_FLOOR)
        assertEquals(TUNED_KELVIN, copy.setTemperature, "the tuning survived the change of substance")
        assertEquals(Species.Titanium, c.state.deck.materialOf(copy), "and it is built out of the new one")
    }

    /**
     * Picking a different building out of the palette drops the settings.
     *
     * ⛔ **A furnace's dwell is not a pump's anything.** The stamp is keyed to a kind, so the moment
     * the kind changes it is meaningless — and a stamp that outlived its kind would be applied to
     * whatever the player picked next by a `withSettings` that has no way to know it is stale.
     */
    @Test
    fun picking_another_building_drops_the_settings() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        c.grab()
        assertNotNull(c.stamped)

        c.brush = Brush.Building(DeckMachineKind.Warehouse)
        assertNull(c.stamped)
    }

    // ── Putting a copy down on something that is already there ───────────────

    /**
     * The paste half: a stamped brush clicked onto a machine of its own kind **tunes that one**.
     *
     * The machine has to still be the same object's worth of matter afterwards — this is a setting
     * changing, not a demolition and a rebuild — which is what the material assertion is for.
     */
    @Test
    fun clicking_an_existing_machine_hands_over_the_settings_only() {
        val c = controller()
        val wasMadeOf = c.state.deck.materialOf(furnaceAt(c, PLAIN_OVEN))
        assertNotEquals(TUNED_KELVIN, furnaceAt(c, PLAIN_OVEN).setTemperature, "the fixture starts them different")

        c.inspect(OVEN, InspectLayer.Deck)
        c.grab()
        // Deliberately holding a different substance than the target is made of, so that a click
        // which recast the machine would show up as a changed material rather than as nothing.
        c.buildMaterial = Species.Copper
        c.click(PLAIN_OVEN)
        c.stepOnce()

        val tuned = furnaceAt(c, PLAIN_OVEN)
        assertEquals(TUNED_KELVIN, tuned.setTemperature, "it took the setpoint")
        assertEquals(wasMadeOf, c.state.deck.materialOf(tuned), "and is still made of what it was made of")
    }

    /**
     * Turning the brush and clicking a machine already on the deck **turns that machine**.
     *
     * ⚠️ The facing comes off the cursor rather than out of the stamp, which is the only reason this
     * works: a stamp that insisted on the facing it was captured with could copy a machine's tuning
     * but never re-aim one.
     */
    @Test
    fun a_turned_brush_turns_the_machine_it_is_clicked_on() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        c.grab()
        assertEquals(Direction.Right, c.brushFacing, "grabbed facing the way the original faces")
        c.rotateBrush()
        val aimed = c.brushFacing
        assertNotEquals(Direction.Right, aimed)

        c.click(PLAIN_OVEN)
        c.stepOnce()

        assertEquals(aimed, (furnaceAt(c, PLAIN_OVEN) as DirectedDeckMachine).facing)
    }

    /** The cursor says so before the click: a hand-over is drawn as a yes, never as a refusal. */
    @Test
    fun the_cursor_calls_a_hand_over_a_hand_over() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        c.grab()

        val over = assertNotNull(c.planAt(PLAIN_OVEN))
        assertTrue(over.settingsOnly, "clicking a furnace with a furnace stamp re-tunes it")
        assertTrue(over.allowed, "and is therefore allowed, though the tile is occupied")

        val empty = assertNotNull(c.planAt(EMPTY_FLOOR))
        assertFalse(empty.settingsOnly, "on bare deck the same click builds")
    }

    /**
     * The preview **snaps to the machine it would re-tune**, wherever on it the pointer is.
     *
     * ⛔ A furnace is three tiles across, so a click on its corner is a click on the furnace. Drawn
     * off the pointer, the preview was a second furnace hanging off the corner of the first — which
     * reads as an overlapping placement, the one thing this click is not.
     */
    @Test
    fun the_preview_snaps_to_the_machine_it_would_re_tune() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        c.grab()

        val corner = grid.tile(grid.xOf(PLAIN_OVEN) - 1, grid.yOf(PLAIN_OVEN) - 1)
        assertNotEquals(PLAIN_OVEN, corner, "the fixture must point somewhere off the anchor")
        val plan = assertNotNull(c.planAt(corner))

        assertTrue(plan.settingsOnly)
        assertEquals(PLAIN_OVEN, plan.tile, "the preview aligned with the machine, not the pointer")
    }

    /** And the click still lands, aimed from that same off-centre tile. */
    @Test
    fun a_hand_over_works_from_any_tile_of_the_machine() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        c.grab()

        c.click(grid.tile(grid.xOf(PLAIN_OVEN) - 1, grid.yOf(PLAIN_OVEN) - 1))
        c.stepOnce()

        assertEquals(TUNED_KELVIN, furnaceAt(c, PLAIN_OVEN).setTemperature)
    }

    /** A brush with no stamp on it is an ordinary build, and an occupied tile refuses it as before. */
    @Test
    fun an_unstamped_brush_is_still_refused_by_an_occupied_tile() {
        val c = controller()
        c.tool = Tool.Build
        c.buildMaterial = FIXTURE_MACHINE_METAL
        c.brush = Brush.Building(DeckMachineKind.Furnace)

        val plan = assertNotNull(c.planAt(PLAIN_OVEN))
        assertFalse(plan.settingsOnly)
        assertFalse(plan.allowed, "nothing stamped means nothing to hand over — it is just in the way")
    }

    // ── ESC: one rung at a time ──────────────────────────────────────────────

    /**
     * The whole ladder, walked from the bottom in one test.
     *
     * ⛔ **Written as a sequence and not as five tests**, because the claim *is* the sequence: each
     * rung is only meaningful as the one below the last. Five independent assertions would pass
     * against an ESC that jumped straight to the top from anywhere.
     */
    @Test
    fun escape_walks_back_up_the_hierarchy_one_rung_at_a_time() {
        val c = controller()
        c.inspect(OVEN, InspectLayer.Deck)
        c.grab()
        assertEquals(Tool.Build, c.tool)
        assertNotNull(c.brush)

        // 1. Holding a building → the palette, still open, now empty.
        assertTrue(c.escape())
        assertEquals(Tool.Build, c.tool, "still building")
        assertNull(c.brush, "just not holding anything")
        assertNull(c.stamped, "and not holding a copy of anything either")

        // 2. The empty palette → reading, still pointed where it was pointed.
        assertTrue(c.escape())
        assertEquals(Tool.Inspect, c.tool)
        assertEquals(OVEN, c.inspectTile, "the tile survived the way out of the palette")

        // 3. Reading a tile → reading nothing.
        assertTrue(c.escape())
        assertEquals(TileIndex.NONE, c.inspectTile)

        // 4. Nothing left. The caller's cue to open the menu — see `OutofspaceHud.escape`.
        assertFalse(c.escape())
    }

    /**
     * DELETE, CANCEL and CUT step out to the inspector **keeping the tile**, not to a build palette
     * the player was never in.
     */
    @Test
    fun escape_from_a_destructive_tool_lands_on_the_tile_it_was_reading() {
        for (tool in listOf(Tool.Delete, Tool.Cancel, Tool.Cut)) {
            val c = controller()
            c.inspect(OVEN, InspectLayer.Deck)
            c.tool = tool

            assertTrue(c.escape(), "$tool is a rung")
            assertEquals(Tool.Inspect, c.tool, "$tool steps out to the inspector")
            assertEquals(OVEN, c.inspectTile, "$tool leaves the tile where it was")
        }
    }

    /** Flying is not on the ladder, and one press is always enough to get out of it. */
    @Test
    fun escape_out_of_flight_takes_one_press() {
        val c = controller()
        c.mode = Mode.Flight
        assertTrue(c.escape())
        assertEquals(Mode.Build, c.mode)
    }

    // ── The palette you can stand in ─────────────────────────────────────────

    /**
     * With nothing picked, a click **reads the tile** — and the palette stays open.
     *
     * This is what makes C reachable from inside the build tool: click the machine you want another
     * of, press C, and you are holding it. A click that placed a default, or did nothing, would both
     * break that.
     */
    @Test
    fun a_click_with_an_empty_palette_reads_the_tile() {
        val c = controller()
        c.tool = Tool.Build
        c.buildMaterial = FIXTURE_MACHINE_METAL
        assertNull(c.brush)

        c.click(OVEN)
        c.stepOnce()

        assertEquals(OVEN, c.inspectTile, "it read the tile")
        assertEquals(Tool.Build, c.tool, "and stayed in the build tool")
        // Now the gesture the whole state exists for.
        assertTrue(c.grab())
        assertEquals(Brush.Building(DeckMachineKind.Furnace), c.brush)
    }

    /** An empty palette places nothing, however hard it is clicked. */
    @Test
    fun an_empty_palette_builds_nothing() {
        val c = controller()
        c.tool = Tool.Build
        c.buildMaterial = FIXTURE_MACHINE_METAL

        c.click(EMPTY_FLOOR)
        c.stepOnce()

        assertNull(c.state.machineCovering(EMPTY_FLOOR), "nothing was placed")
        assertNull(c.planAt(EMPTY_FLOOR), "and the cursor had nothing to draw")
    }

    // ── The top of the ladder: the menu ──────────────────────────────────────

    /**
     * ESC with nothing left to step out of opens the menu **and stops the world** — and ESC again
     * puts both back.
     *
     * ⛔ **Through the HUD, because the menu is a sheet and sheets are the HUD's.** The controller
     * reports the ladder is exhausted and stops there — see [OutofspaceController.escape] — so this
     * is the one rung that cannot be asserted anywhere else.
     */
    @Test
    fun the_last_escape_opens_the_menu_and_pauses() {
        val c = controller()
        val hud = OutofspaceHud()
        c.paused = false

        hud.escape(c)
        assertEquals(Sheet.Menu, hud.openSheet)
        assertTrue(c.paused, "the menu is not somewhere to stand while the ship tumbles")

        hud.escape(c)
        assertEquals(Sheet.None, hud.openSheet)
        assertFalse(c.paused, "and the switch goes back where the player left it")
    }

    /**
     * A game the player had already stopped stays stopped when they close the menu.
     *
     * The menu borrows the pause switch; it does not own it.
     */
    @Test
    fun the_menu_gives_the_pause_switch_back_as_it_found_it() {
        val c = controller()
        val hud = OutofspaceHud()
        c.paused = true

        hud.escape(c)
        hud.escape(c)
        assertTrue(c.paused)
    }

    /**
     * Starting the world running from inside the menu means it, and closing the menu does not undo
     * it a fraction of a second later.
     */
    @Test
    fun playing_from_inside_the_menu_survives_closing_it() {
        val c = controller()
        val hud = OutofspaceHud()
        c.paused = false
        hud.escape(c)

        // What the menu's own PLAY button does, and what SPACE does behind it.
        c.paused = false
        hud.escape(c)
        assertFalse(c.paused, "the player's own choice outranks what the menu was restoring")
    }

    /** Somewhere with room for a three-by-three machine and nothing else near it. */
    private val EMPTY_FLOOR = grid.tile(7, 8)

    private companion object {
        /**
         * A setpoint and a dwell that are **not** a fresh furnace's, so that a copy which quietly
         * failed to carry them would read as a copy that carried the defaults.
         */
        val TUNED_KELVIN: Int = Furnace.SETPOINTS.last()
        val TUNED_DWELL: Int = Furnace.DWELLS.last()
    }
}
