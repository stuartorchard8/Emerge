package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.reach
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.fitGrid
import org.emerge.demo.outofspace.world.fitToFrame
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The acceptance tests for P4 — **the explicit fit** — written before the implementation.
 *
 * P3 grows and never shrinks, so a world that has been built out and then dismantled keeps the
 * biggest grid it ever needed. The explicit fit is the player saying "tidy up": take the grid back
 * to the bounding box plus the pad, whichever direction that moves each edge.
 *
 * Everything underneath this already exists — `fitGrid` computes the box, `remapped` moves the
 * world, `FrameShift` carries the offset to whoever wrote a coordinate down. So P4 is the trigger
 * and two things the trigger needs:
 *
 * 1. **The offset has to be reported.** `fitGrid` returns a state and says nothing about how far the
 *    origin moved, which was fine while nothing called it during play. The camera and the selection
 *    cannot follow a shift nobody told them about, so [fitToFrame] reports it the way `growToFit`
 *    does and `fitGrid` becomes the shorthand that throws the offset away.
 * 2. **It has to run at the end of the tick**, for the same reason growth does: `Work` is built from
 *    the grid the tick started on, so a resize partway leaves half a world on each lattice.
 *
 * The fit is an [Edit] rather than a method on the controller, so it travels the same lockstep input
 * path as every other player action and two hosts fit on the same tick.
 *
 * ⚠️ **A fit is the first thing in this game that can shrink**, which is why §5 of the plan lands
 * with it and `GridVentTest` is its sibling. Read that file before this one.
 */
class GridFitTriggerTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(96, 60))
    private val pad = 4

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(
            cfg,
            state,
            mapOf(org.emerge.sim.core.PlayerId(0) to OutofspaceInput(edits.toList())),
        )

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * Creative: this file's fixtures grow the world and then **take the pieces back off** to leave
     * padding behind, which is the premise P4 exists for. Outside creative a delete only marks, so
     * nothing would ever come off and there would be no slack to reclaim.
     */
    private fun fitted(): VesselState =
        starterVessel(Grid(96, 60)).fitGrid(pad).copy(creative = true)

    // ── The oracle ────────────────────────────────────────────────────────

    /**
     * What the grid must enclose, re-derived rather than asked for: machine **footprints**, every
     * conduit segment and every bridge.
     *
     * **not** rigid bodies (like rocks), which live outside the world by design (§8).
     *
     * Deliberately a second copy of the same derivation `GridGrowTest` carries. A test that calls
     * `placedBounds` to check `placedBounds` cannot fail.
     */
    private fun footprintBounds(s: VesselState): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        fun cover(x: Int, y: Int, reach: Int) {
            if (x - reach < minX) minX = x - reach
            if (y - reach < minY) minY = y - reach
            if (x + reach > maxX) maxX = x + reach
            if (y + reach > maxY) maxY = y + reach
        }

        for (tile in s.grid.tiles) {
            val m = s.deck[tile] ?: continue
            cover(s.grid.xOf(tile), s.grid.yOf(tile), m.kind.reach)
        }
        for (c in Conduit.entries) {
            val layer = s.conduits[c]
            for (tile in s.grid.tiles) if (layer[tile.index] != null) cover(s.grid.xOf(tile), s.grid.yOf(tile), 0)
        }

        return if (minX > maxX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    /** Clear tiles between the outermost placed thing and each edge: left, top, right, bottom. */
    private fun margins(s: VesselState): List<Int> {
        val b = footprintBounds(s)!!
        return listOf(b[0], b[1], s.grid.width - 1 - b[2], s.grid.height - 1 - b[3])
    }

    /** Every machine's position as `(x, y)`, row-major — an order a translation cannot disturb. */
    private fun positions(s: VesselState): List<Pair<Int, Int>> =
        s.grid.tiles.filter { s[it] != null || s.deck[it] != null }.map { s.grid.xOf(it) to s.grid.yOf(it) }

    /**
     * A world that has grown past its fit: built one tile in from the left edge and one in from the
     * right, then dismantled again, so the grid is bigger than the ship needs on both sides.
     *
     * The left build is the one that matters — it moves the origin, so the fit that undoes it has to
     * move the origin back, and a fit that only ever resized would pass every test that used the
     * right-hand side alone.
     */
    private fun sprawled(): VesselState {
        var s = fitted()
        val leftTile = s.grid.tile(1, s.grid.height / 2)
        s = edit(s, Edit.Place(leftTile, Brush.Building(DeckMachineKind.Hull), Direction.Right))
        s = run(s, 1)
        val rightTile = s.grid.tile(s.grid.width - 2, s.grid.height / 2)
        s = edit(s, Edit.Place(rightTile, Brush.Building(DeckMachineKind.Hull), Direction.Right))
        s = run(s, 1)
        // Take both back off. The grid does not follow — P3 only grows — so the world is now
        // carrying padding it does not need, which is the whole situation P4 exists for.
        s = edit(s, Edit.Remove(s.grid.tile(4, s.grid.height / 2)))
        s = edit(s, Edit.Remove(s.grid.tile(s.grid.width - 5, s.grid.height / 2)))
        return run(s, 1)
    }

    // ── 1. The fixture is the premise: P3 left slack behind ───────────────

    @Test
    fun `a world that grew and was dismantled is carrying slack`() {
        val s = sprawled()
        assertTrue(
            margins(s).any { it > pad },
            "the fixture has no slack to reclaim, so every case below proves nothing: ${margins(s)}",
        )
    }

    // ── 2. fitToFrame: the same fit, and it reports the offset ───────────

    @Test
    fun `fitToFrame lands the same world fitGrid does`() {
        // `fitGrid` becomes the shorthand for "fit and throw the offset away", so the two must not
        // be able to disagree about the shape of the answer.
        val s = sprawled()
        assertEquals(s.fitGrid(pad).grid, s.fitToFrame(pad).state.grid, "the two fits disagree")
        assertEquals(positions(s.fitGrid(pad)), positions(s.fitToFrame(pad).state), "geometry")
    }

    @Test
    fun `fitToFrame reports the offset everything actually moved by`() {
        val s = sprawled()
        val result = s.fitToFrame(pad)

        assertTrue(result.grew, "the fit did not report that the frame changed")
        val before = positions(s)
        val after = positions(result.state)
        assertEquals(before.size, after.size, "a machine went missing across the fit")
        for (i in before.indices) {
            assertEquals(
                before[i].first + result.dx to before[i].second + result.dy,
                after[i],
                "machine $i did not move by the reported (${result.dx}, ${result.dy})",
            )
        }
    }

    @Test
    fun `a fit reclaims the slack, to exactly the pad on every side`() {
        val result = sprawled().fitToFrame(pad)
        assertEquals(listOf(pad, pad, pad, pad), margins(result.state), "margins after an explicit fit")
    }

    @Test
    fun `a fit shrinks — which is the whole reason section 5 exists`() {
        val s = sprawled()
        val after = s.fitToFrame(pad).state
        assertTrue(
            after.grid.size < s.grid.size,
            "the fit did not shrink (${s.grid.width}x${s.grid.height} -> ${after.grid.width}x${after.grid.height})",
        )
    }

    @Test
    fun `fitting twice is fitting once`() {
        val once = sprawled().fitToFrame(pad).state
        val twice = once.fitToFrame(pad)
        assertEquals(once.grid, twice.state.grid, "an idempotent fit changed the grid")
        assertEquals(0, twice.dx, "dx")
        assertEquals(0, twice.dy, "dy")
        assertEquals(positions(once), positions(twice.state), "an idempotent fit moved something")
    }

    // ── 3. Through the reducer, as a player action ────────────────────────

    @Test
    fun `Edit Fit reclaims the slack through the reducer`() {
        val s = sprawled()
        val after = edit(s, Edit.Fit)
        assertNotEquals(s.grid, after.grid, "the reducer ignored the fit")
        assertEquals(listOf(pad, pad, pad, pad), margins(after), "margins after Edit.Fit")
    }

    @Test
    fun `a fit books the offset to the frame shift, so the camera can follow`() {
        val s = sprawled()
        val expected = s.fitToFrame(pad)
        val after = edit(s, Edit.Fit)

        assertEquals(
            s.frameShiftX + expected.dx,
            after.frameShiftX,
            "the fit did not book its x offset — the camera and the selection will not follow it",
        )
        assertEquals(s.frameShiftY + expected.dy, after.frameShiftY, "the fit did not book its y offset")
    }

    @Test
    fun `every ledger survives an explicit fit`() {
        // The sharp case in the whole of P4: this is a *shrink*, so the cells it discards have to be
        // vented rather than dropped — see `GridVentTest`. A world with rocks and 200 ticks of gas
        // behind it, so the discarded padding is genuinely carrying something.
        var s = fitted()
        s = run(s, 50)
        val leftTile = s.grid.tile(1, s.grid.height / 2)
        s = run(edit(s, Edit.Place(leftTile, Brush.Building(DeckMachineKind.Hull), Direction.Right)), 1)
        s = run(edit(s, Edit.Remove(s.grid.tile(4, s.grid.height / 2))), 50)
        assertBalanced(s, "before the fit — the fixture itself")

        val after = edit(s, Edit.Fit)
        assertNotEquals(s.grid, after.grid, "the fixture did not actually shrink")
        assertBalanced(after, "straight after the fit")
        assertBalanced(run(after, 200), "200 ticks after the fit")
    }

    @Test
    fun `a fit on a world that is already fitted does nothing at all`() {
        val s = run(fitted(), 5)
        val after = edit(s, Edit.Fit)
        assertEquals(s.grid, after.grid, "an idempotent fit resized the world")
        assertEquals(s.frameShiftX, after.frameShiftX, "an idempotent fit booked a shift")
        assertEquals(s.frameShiftY, after.frameShiftY, "an idempotent fit booked a shift")
        assertEquals(positions(s), positions(after), "an idempotent fit moved something")
    }

    @Test
    fun `a world that never opted into a pad is never fitted behind its back`() {
        // The P3 principle, restated for the explicit case: a hand-authored fixture keeps the frame
        // it was drawn in. `gridPad` is 0 there, and a fit with no pad to keep would refit worlds
        // that never asked — which is how P2 decided `Save.read` must not fit either.
        val hand = starterVessel(Grid(96, 60)).copy(gridPad = 0)
        val after = edit(hand, Edit.Fit)
        assertEquals(hand.grid, after.grid, "a world with no pad was refitted anyway")
    }

    // ── 4. The holders of a coordinate follow it ──────────────────────────

    @Test
    fun `the selection survives an explicit fit`() {
        // The near-side case specifically: the origin moves, so a raw index means a different tile
        // afterwards. The controller reindexes through `FrameShift` rather than doing arithmetic.
        val controller = OutofspaceController(cfg, sprawled())
        controller.stepOnce()

        // Any real machine will do; found rather than named, because a fit is exactly the moment a
        // written-down tile stops meaning what it meant.
        // On the deck: with the migration done, every building is a deck machine and the machine
        // list holds nothing but bridges.
        val target = controller.state.grid.tiles.first { controller.state.deck[it] != null }
        controller.tool = Tool.Wire
        controller.apply(target)
        assertEquals(target, controller.selected, "the fixture did not select anything")

        // Where that tile is, and where the fit is about to put it. Compared as a *position*, not as
        // the machine sitting on it: a `Machine` carries its own energy, the heat sim moves them
        // every tick, and comparing the objects across the fit's tick fails on a temperature change
        // while the selection is perfectly correct.
        val before = controller.state
        val x = before.grid.xOf(target)
        val y = before.grid.yOf(target)
        val expected = before.fitToFrame(pad)

        controller.fit()
        controller.stepOnce()

        assertNotEquals(TileIndex.NONE, controller.selected, "the selection was dropped by the fit")
        assertEquals(
            controller.state.grid.tile(x + expected.dx, y + expected.dy),
            controller.selected,
            "the selection followed the fit to the wrong tile",
        )
        assertNotEquals(
            null,
            controller.state.deck[controller.selected],
            "the selection landed on an empty tile",
        )
    }

    /** ⚠️ The energy identities are **PARKED** for the unit rescale — see [EnergyLedgers]. */
    private fun assertBalanced(s: VesselState, whenever: String) {
        assertEquals(0L, s.airBalance, "airBalance $whenever")
        EnergyLedgers.assertBalanced(s, whenever)
        // `inTransitMass`, not `mass` — the latter includes the fabric of the ship, which no
        // extractor produced, so it can never be zero.
        assertEquals(0L, s.inTransitMass + s.ventedMass + s.builtMass - s.extractedMass - s.baselineCargoMass, "massBalance $whenever")
        // No body conservation (bodies spawn/despawn freely), just check bodies exist.
        MomentumLedger.assertBalanced(s, "momentumBalance $whenever")
    }
}
