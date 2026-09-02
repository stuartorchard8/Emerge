package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.canStand
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The build cursor's preview — see [BuildPlan].
 *
 * ⛔ **The test that matters is [preview_agrees_with_the_reducer_everywhere].** Everything else here
 * pins a particular refusal, and could be satisfied by a preview that had merely been written to
 * look right; that one asks the reducer what it would actually do on every tile of a world and
 * refuses to let the two answers differ. A preview that says yes where the reducer says no is worse
 * than no preview, because it is a promise the game then breaks — and it would break it silently,
 * since a refused placement changes nothing and says nothing.
 */
class BuildPlanTest {

    private val grid = Grid(11, 9)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** Bare deck, with a three-by-three tank standing on it — covering (1,1) to (3,3). */
    private fun world(): VesselState {
        val deck = DeckArray(grid)
        deck += fixtureStorage(TANK, Direction.Right)
        return VesselState(
            grid,
            deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
    }

    /** The tank's anchor. Off in a corner, so there is room on this grid for a five-wide machine. */
    private val TANK = grid.tile(2, 2)

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    /**
     * What the reducer *actually does* — the only authority this file recognises.
     *
     * ⚠️ **"Something of that kind is there now and was not before"**, rather than "something is
     * there now". A refused placement changes nothing at all, so the tile a five-wide machine was
     * refused *on top of* still holds the machine that refused it — and an oracle that only looked
     * for an occupant would read that as a success. It did, on the tank's own centre tile.
     */
    private fun reducerPlaces(
        state: VesselState,
        tile: TileIndex,
        kind: DeckMachineKind,
        facing: Direction,
    ): Boolean {
        val before = state.deck[tile]?.kind
        val after = edit(state, fixturePlace(tile, Brush.Building(kind), facing)).deck[tile]?.kind
        return after == kind && before != kind
    }

    /**
     * The build tool out, something picked, something to build it out of — a player mid-build.
     *
     * ⚠️ **The brush has to be said now**, because the palette starts empty: with nothing picked
     * there is no plan to draw at all, which is its own contract and lives in `GrabAndEscapeTest`.
     * Every test here is about what the cursor says once the player *has* chosen.
     */
    private fun controller(state: VesselState = world()): OutofspaceController =
        OutofspaceController(cfg, state).apply {
            tool = Tool.Build
            // Track, which is what the palette used to hold by default and what the two tests
            // below that do not name a brush were written against: it fits anywhere there is grid.
            brush = Brush.Run(Conduit.Rail)
            buildMaterial = FIXTURE_MACHINE_METAL
        }

    // ── The contract ─────────────────────────────────────────────────────────

    /**
     * Every tile, three shapes, two facings: what the preview promises is what the reducer does.
     *
     * The shapes are chosen for the ways a placement can be refused rather than for variety. An
     * extractor is five across, so most of this grid is rim for it; a thruster's footprint is its
     * anchor *plus the tile in front*, which is a different set of tiles for every facing and the
     * one shape a square check would get wrong; a hull is one tile and can be put almost anywhere,
     * which is what makes it the case that would catch a preview that had learned to say no.
     */
    @Test
    fun preview_agrees_with_the_reducer_everywhere() {
        val state = world()
        var allowed = 0
        var refused = 0
        for (kind in listOf(DeckMachineKind.Extractor, DeckMachineKind.Thruster, DeckMachineKind.Hull)) {
            for (facing in listOf(Direction.Right, Direction.Up)) {
                for (tile in grid.tiles) {
                    val preview = state.canStand(kind, tile, facing)
                    assertEquals(
                        reducerPlaces(state, tile, kind, facing),
                        preview,
                        "$kind facing $facing at (${grid.xOf(tile)}, ${grid.yOf(tile)})",
                    )
                    if (preview) allowed++ else refused++
                }
            }
        }
        // Both answers were actually reached — a predicate stuck on one of them would agree with a
        // reducer that had been broken the same way, and this sweep would say nothing at all.
        assertTrue(allowed > 0 && refused > 0, "sweep saw $allowed allowed and $refused refused")
    }

    // ── The individual refusals ──────────────────────────────────────────────

    @Test
    fun a_footprint_over_the_rim_is_refused() {
        val state = world()
        // The bell would be off the top edge; the anchor itself is on the grid.
        assertFalse(state.canStand(DeckMachineKind.Thruster, grid.tile(8, 0), Direction.Up))
        assertTrue(state.canStand(DeckMachineKind.Thruster, grid.tile(8, 0), Direction.Down))
    }

    @Test
    fun a_footprint_over_a_machine_is_refused() {
        val state = world()
        // Not the tank's own tile — a corner of a five-wide extractor clipping a corner of the tank
        // is the case a cursor-tile-only check would wave through.
        assertFalse(state.canStand(DeckMachineKind.Extractor, grid.tile(4, 4), Direction.Right))
        assertTrue(state.canStand(DeckMachineKind.Extractor, grid.tile(6, 4), Direction.Right))
    }

    /**
     * A hull in a tile with nothing but hull around it: the air in there has nowhere to go, so the
     * wall cannot be closed. The rule that is easiest to meet by accident and hardest to see.
     */
    @Test
    fun a_wall_with_nowhere_to_put_the_air_is_refused() {
        val deck = DeckArray(grid)
        val middle = grid.tile(5, 4)
        for (dir in Direction.ALL) deck += Hull(grid.neighbour(middle, dir))
        val state = VesselState(
            grid,
            deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        assertFalse(state.canStand(DeckMachineKind.Hull, middle, Direction.Right))
        assertFalse(reducerPlaces(state, middle, DeckMachineKind.Hull, Direction.Right))
        // A vent is permeable, so it displaces nothing and may stand in the same hole.
        assertTrue(state.canStand(DeckMachineKind.Vent, middle, Direction.Right))
    }

    // ── What the cursor offers ───────────────────────────────────────────────

    @Test
    fun there_is_no_plan_unless_the_build_tool_is_out() {
        val c = controller()
        val tile = grid.tile(1, 1)
        c.tool = Tool.Inspect
        assertNull(c.planAt(tile))
        c.tool = Tool.Delete
        assertNull(c.planAt(tile))
        c.tool = Tool.Build
        assertNotNull(c.planAt(tile))
        // Flying: the same keys mean something else and there is nothing to place.
        c.mode = Mode.Flight
        assertNull(c.planAt(tile))
    }

    @Test
    fun no_plan_off_the_grid() {
        assertNull(controller().planAt(TileIndex.NONE))
    }

    /**
     * ⛔ **Nothing to build *out of* is a refusal, not an absence.** The controller declines to raise
     * an edit at all when no material is chosen, so a click does nothing and says nothing — which is
     * exactly the silence this feature exists to end. The plan is still drawn; it is drawn refused.
     */
    @Test
    fun nothing_to_build_with_is_shown_as_a_refusal() {
        val c = controller()
        val tile = grid.tile(1, 1)
        c.buildMaterial = null
        assertEquals(false, c.planAt(tile)?.allowed)
        c.buildMaterial = FIXTURE_MACHINE_METAL
        assertEquals(true, c.planAt(tile)?.allowed)
    }

    @Test
    fun the_plan_carries_the_brush_and_the_facing_the_player_chose() {
        val c = controller()
        c.brush = Brush.Building(DeckMachineKind.Thruster)
        c.brushFacing = Direction.Up
        val plan = assertNotNull(c.planAt(grid.tile(3, 4)))
        assertEquals(Brush.Building(DeckMachineKind.Thruster), plan.brush)
        assertEquals(Direction.Up, plan.facing)
        // Facing up from (3,4) the bell swings onto the tank's bottom-right corner.
        assertFalse(plan.allowed)
        // The same tile, turned the other way: the bell points at open deck.
        c.brushFacing = Direction.Down
        assertEquals(true, c.planAt(grid.tile(3, 4))?.allowed)
    }

    /** Track is refused by nothing but an empty pocket: the conduit layers no longer exclude. */
    @Test
    fun a_run_is_allowed_wherever_there_is_grid() {
        val c = controller()
        c.brush = Brush.ALL.first { it is Brush.Run }
        assertTrue(grid.tiles.all { c.planAt(it)?.allowed == true })
    }
}
