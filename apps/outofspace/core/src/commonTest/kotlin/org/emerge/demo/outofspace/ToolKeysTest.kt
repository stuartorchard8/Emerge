package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Stockpile
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
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
 * A key per tool — B, X, Z, Q — and E for the material.
 *
 * Companion to `GrabAndEscapeTest`, which owns C and ESC. These are the other half of the same idea:
 * every tool is one key away and the same key aims it, so the editor has no cycle whose length
 * changes when a tool is added.
 *
 * ⛔ **The claim that matters is [opening_a_tool_does_not_also_aim_it].** Everything else here pins
 * a particular cycle and would survive the rule being wrong; that one is what keeps DELETE·TOP,
 * CUT·RAIL and the empty palette reachable from the keyboard at all.
 */
class ToolKeysTest {

    private val grid = Grid(14, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /**
     * Two tanks, because **one tank can hold only one buildable material.**
     *
     * `buildableSpecies` counts stores holding nothing *but* one species — a 50/50 tank is a blend
     * with no dominant species and is buildable from nothing at all. So a fixture that wants two
     * materials on offer needs two tanks, and a single tank stocked twice would silently offer none.
     */
    private val TANK = grid.tile(4, 5)
    private val TANK2 = grid.tile(9, 4)
    private val TRACK = grid.tile(9, 8)

    /**
     * ⛔ **The track's metal must differ from anything in the tanks.** Half of what is asserted here
     * is a material arriving from one place rather than the other, and a fixture whose metals were
     * the same would pass every one of those with the feature ripped out. Track is iron — see
     * `materialBefore(Conduit.Rail)`.
     */
    private val CARGO: Species = Species.Copper

    /** A hull, up to two tanks, and a length of iron track. */
    private fun world(vararg loose: Pair<Species, Long>): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) { deck += Hull(grid.tile(x, 0)); deck += Hull(grid.tile(x, grid.height - 1)) }
        // ⚠️ The corners belong to the rows above — standing a second plate on one throws.
        for (y in 1 until grid.height - 1) { deck += Hull(grid.tile(0, y)); deck += Hull(grid.tile(grid.width - 1, y)) }
        val tanks = listOf(TANK, TANK2)
        for (tile in tanks.take(maxOf(loose.size, 1))) deck += fixtureStorage(tile, Direction.Right)
        val rails = MutableList<Segment?>(grid.size) { null }
        rails[TRACK.index] = Segment(Conduit.Rail, material = FIXTURE_RAIL_METAL)
        var state = VesselState(
            grid,
            deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            conduits = Conduits.ofRails(rails),
        )
        for ((i, entry) in loose.withIndex()) {
            val (species, packets) = entry
            state = state.stocked(
                tanks[i],
                Mixture.of(species to packets * Capacity.PACKET_MASS, energy = 0L),
                BufferRole.Inside,
            )
        }
        return state
    }

    /** The default world: one tank of copper. */
    private fun stocked(): VesselState = world(CARGO to 8L)

    private fun controller(state: VesselState = stocked()): OutofspaceController =
        OutofspaceController(cfg, state)

    // ── Open, then aim ───────────────────────────────────────────────────────

    /**
     * The rule the whole scheme rests on: the **first** press takes the tool out and leaves its aim
     * alone; the **second** advances it.
     *
     * ⛔ Advance on the way in and there is no keyboard path to DELETE·TOP, to CUT·RAIL, or to the
     * empty palette — the state ESC leaves the player in and the one a click reads a tile from. The
     * defaults would be the one rung the keys could never reach.
     */
    @Test
    fun opening_a_tool_does_not_also_aim_it() {
        val c = controller()

        c.reachFor(Tool.Delete)
        assertEquals(Tool.Delete, c.tool)
        assertEquals(DeleteLayer.Top, c.deleteLayer, "arriving at DELETE leaves it on TOP")

        c.reachFor(Tool.Cut)
        assertEquals(Tool.Cut, c.tool)
        assertEquals(Conduit.Rail, c.cutConduit, "arriving at CUT leaves it on RAIL")

        // The palette is the one that would hurt most: it is a state, not a default.
        c.brush = null
        c.reachFor(Tool.Build)
        assertEquals(Tool.Build, c.tool)
        assertNull(c.brush, "arriving at BUILD leaves the palette as it was — empty")
    }

    /** A second press on the same key aims it, and the third keeps going. */
    @Test
    fun a_second_press_aims_the_tool() {
        val c = controller()

        c.reachFor(Tool.Delete)
        c.reachFor(Tool.Delete)
        assertEquals(DeleteLayer.entries[1], c.deleteLayer)

        c.reachFor(Tool.Cut)
        c.reachFor(Tool.Cut)
        assertEquals(Tool.CUTTABLE[1], c.cutConduit)

        c.reachFor(Tool.Build)
        c.reachFor(Tool.Build)
        assertEquals(Brush.ALL.first(), c.brush, "the first press of B on an empty palette fills it")
    }

    /** Every aim wraps, so the key never dead-ends on the last rung. */
    @Test
    fun the_aims_wrap() {
        val c = controller()
        c.openTool(Tool.Delete)
        repeat(DeleteLayer.entries.size) { c.reachFor(Tool.Delete) }
        assertEquals(DeleteLayer.Top, c.deleteLayer)

        c.openTool(Tool.Cut)
        repeat(Tool.CUTTABLE.size) { c.reachFor(Tool.Cut) }
        assertEquals(Conduit.Rail, c.cutConduit)
    }

    /**
     * CANCEL has no aim, and pressing its key twice is not a bug to be fixed.
     *
     * Calling off a deconstruction is blind on purpose — a player pointing at a condemned tile means
     * "not that one", and naming which of its four layers they meant would be a worse tool.
     */
    @Test
    fun cancel_has_nothing_to_aim() {
        val c = controller()
        c.reachFor(Tool.Cancel)
        c.reachFor(Tool.Cancel)
        assertEquals(Tool.Cancel, c.tool, "still cancelling, and nothing else moved")
    }

    /** Reaching for a tool from any other tool arrives, rather than stepping towards it. */
    @Test
    fun a_tool_is_always_one_press_away() {
        val c = controller()
        for (want in listOf(Tool.Cut, Tool.Build, Tool.Delete, Tool.Cancel, Tool.Cut)) {
            c.reachFor(want)
            assertEquals(want, c.tool, "one press should reach $want")
        }
    }

    // ── E: the material ──────────────────────────────────────────────────────

    /**
     * Opening BUILD with nothing chosen picks **the most of anything the network can deliver**.
     */
    @Test
    fun opening_build_picks_the_most_abundant_loose_material() {
        val c = controller(world(Species.Iron to 2L, Species.Copper to 9L))
        assertNull(c.buildMaterial)

        c.reachFor(Tool.Build)

        assertEquals(Species.Copper, c.buildMaterial, "there is more copper aboard than iron")
    }

    /**
     * A material the player has chosen is **respected even when there is none of it**, and reopening
     * the tool does not quietly retract it.
     *
     * ⛔ This is the half that is easy to get wrong: an auto-pick that ran unconditionally would
     * overwrite a deliberate choice every time the player put a tool down and picked it back up.
     */
    @Test
    fun a_chosen_material_survives_reopening_the_tool_even_if_unavailable() {
        val c = controller()
        c.buildMaterial = Species.Titanium
        assertFalse(Species.Titanium in c.materialsOffered(), "the fixture has no titanium aboard")

        c.openTool(Tool.Delete)
        c.reachFor(Tool.Build)

        assertEquals(Species.Titanium, c.buildMaterial, "the player's own choice stood")
    }

    /** Nothing loose aboard and nothing to fall back on: the picker stays empty rather than lying. */
    @Test
    fun opening_build_on_an_empty_ship_picks_nothing() {
        val c = controller(world())
        c.reachFor(Tool.Build)
        assertNull(c.buildMaterial, "there is nothing the network could deliver")
    }

    /**
     * In creative the allowance is the fallback, so the tool is never opened unusable.
     *
     * ⚠️ **Below whatever is actually aboard**, never instead of it: a creative player with iron in
     * a tank is offered the iron first.
     */
    @Test
    fun creative_falls_back_to_the_allowance() {
        val bare = controller(world().copy(creative = true))
        bare.reachFor(Tool.Build)
        assertEquals(Stockpile.CREATIVE_MATERIALS.first(), bare.buildMaterial)

        val withCargo = controller(stocked().copy(creative = true))
        withCargo.reachFor(Tool.Build)
        assertEquals(CARGO, withCargo.buildMaterial, "what is aboard outranks the allowance")
    }

    /** E steps along the picker's own list, and wraps. */
    @Test
    fun e_steps_along_the_offered_materials() {
        val c = controller(world(Species.Iron to 2L, Species.Copper to 9L))
        val offered = c.materialsOffered()
        assertEquals(listOf(Species.Copper, Species.Iron), offered, "heaviest loose first")

        c.cycleMaterial(1)
        assertEquals(offered[0], c.buildMaterial, "from nothing chosen, E lands on the first")
        c.cycleMaterial(1)
        assertEquals(offered[1], c.buildMaterial)
        c.cycleMaterial(1)
        assertEquals(offered[0], c.buildMaterial, "and wraps")
    }

    /**
     * E from a material that is not on the list starts at the top rather than nowhere.
     *
     * The same answer the palette gives from empty — a cycle that could not be entered from an
     * off-list value would strand a player who had chosen something they have run out of.
     */
    @Test
    fun e_from_an_unavailable_material_starts_at_the_top() {
        val c = controller()
        c.buildMaterial = Species.Titanium
        c.cycleMaterial(1)
        assertEquals(c.materialsOffered().first(), c.buildMaterial)
    }

    /** With nothing aboard at all, E has nowhere to go and leaves the choice alone. */
    @Test
    fun e_on_an_empty_ship_changes_nothing() {
        val c = controller(world())
        c.buildMaterial = Species.Titanium
        c.cycleMaterial(1)
        assertEquals(Species.Titanium, c.buildMaterial)
    }

    /**
     * The material is not a tool's property: it outlives every one of them, which is why E is its
     * own key rather than a rung in somebody's cycle.
     */
    @Test
    fun the_material_survives_changing_tool() {
        val c = controller()
        c.reachFor(Tool.Build)
        val chosen = assertNotNull(c.buildMaterial)

        c.reachFor(Tool.Delete)
        c.reachFor(Tool.Cut)
        c.reachFor(Tool.Cancel)
        c.reachFor(Tool.Build)

        assertEquals(chosen, c.buildMaterial)
    }

    // ── The copy key keeps its own material ──────────────────────────────────

    /**
     * ⛔ **C does not come through the auto-pick.** A copy brings the material of the thing it
     * copied, and an auto-pick running alongside it would either overwrite that or be a no-op that
     * only looks like it works because the two happened to agree.
     */
    @Test
    fun copying_keeps_the_copied_material_not_the_abundant_one() {
        val c = controller()
        c.inspect(TRACK, InspectLayer.Rail)
        assertTrue(c.grab())

        assertEquals(Tool.Build, c.tool)
        assertEquals(FIXTURE_RAIL_METAL, c.buildMaterial, "the track's own metal, not the tank's")
        // The guard on the fixture: were the track and the cargo the same metal, this test would
        // pass with the auto-pick wrongly overwriting what the copy brought.
        assertNotEquals(CARGO, c.buildMaterial, "the fixture's two metals must differ")
    }
}
