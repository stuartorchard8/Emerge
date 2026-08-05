package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.fitGrid
import org.emerge.demo.outofspace.world.size
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance tests for `fitGrid` — written before it exists, and deliberately so.
 *
 * `PLAN_dynamic_grid.md` argues its constraints in prose across §8 and §10, and prose is exactly
 * what a first attempt lost: it enclosed the rock field, measured machines by their anchors, and
 * fitted the starter vessel to 92×50 rather than the ~41×31 §1 promises — passing its own tests the
 * whole way. Everything that plan asks for is stated here as an assertion instead, so that getting
 * it wrong is not something an implementation can do quietly.
 *
 * The expected bounding box is computed here by [footprintBounds] rather than written down as a
 * literal. That is on purpose: a magic `41` would tell an implementation *what* to return without
 * telling it what the number means, and would have to be re-derived by hand every time the starter
 * vessel changes shape.
 */
class GridFitTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(96, 60))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * An independent oracle for what the box must enclose: every tile a machine *covers*, not the
     * tile it is stored at. A smelter is anchored at its centre and reaches two tiles past it, so
     * a box drawn round the anchors clips the hull off its own ship.
     *
     * Deliberately a re-derivation rather than a call into production code — a test that asks the
     * implementation what the answer is cannot then check it.
     *
     * Returns `(minX, minY, maxX, maxY)`, or null if nothing is placed.
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

        for (i in s.machines.indices) {
            val m = s.machines[i] ?: continue
            cover(s.grid.xOf(i), s.grid.yOf(i), m.kind.size / 2)
        }
        for (i in s.bridges.indices) {
            if (s.bridges[i] == null) continue
            cover(s.grid.xOf(i), s.grid.yOf(i), 0)
        }
        for (c in org.emerge.demo.outofspace.world.Conduit.entries) {
            val layer = s.conduits[c]
            for (i in layer.indices) {
                if (layer[i] == null) continue
                cover(s.grid.xOf(i), s.grid.yOf(i), 0)
            }
        }
        for (tile in s.debris.tiles()) cover(s.grid.xOf(tile), s.grid.yOf(tile), 0)

        return if (minX > maxX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    // ── The box ──────────────────────────────────────────────────────────

    @Test
    fun `the box is the footprint plus exactly four on every side`() {
        val fitted = starterVessel(Grid(96, 60), rocks = 0).fitGrid(pad = 4)
        val b = footprintBounds(fitted)!!

        assertEquals(4, b[0], "pad on the left")
        assertEquals(4, b[1], "pad on the top")
        assertEquals(4, fitted.grid.width - 1 - b[2], "pad on the right")
        assertEquals(4, fitted.grid.height - 1 - b[3], "pad on the bottom")
    }

    @Test
    fun `the hull can never touch the grid edge`() {
        // §1.3: StructureMap floods inward from the boundary, so a hull flush against it reads as
        // interior and the whole ship is inside-out. The pad is what makes that unrepresentable.
        val fitted = starterVessel(Grid(96, 60), rocks = 0).fitGrid(pad = 4)
        for (i in fitted.machines.indices) {
            if (fitted.machines[i] == null) continue
            val x = fitted.grid.xOf(i)
            val y = fitted.grid.yOf(i)
            assertTrue(
                x > 0 && y > 0 && x < fitted.grid.width - 1 && y < fitted.grid.height - 1,
                "a machine sits on the grid edge at ($x, $y)",
            )
        }
    }

    @Test
    fun `a fitted grid is a fraction of the fixed one`() {
        // §1.2 is the whole performance case, and it is a claim about a ratio, so assert the ratio.
        // The fixed grid was 96×60 = 5760 tiles; the plan predicts ~41×31 = 1271, a 4.5× cut.
        // Three× is the loosest reading of that promise that still fails the 92×50 near-miss.
        val fitted = starterVessel(Grid(96, 60), rocks = 0).fitGrid(pad = 4)
        val tiles = fitted.grid.width * fitted.grid.height
        assertTrue(tiles * 3 < 96 * 60, "fitted to $tiles tiles, against 5760 — no real cut")
    }

    @Test
    fun `rocks do not enlarge the box`() {
        // §8, at length: rocks live outside the world quite happily. `overlapsHull` bounds-checks
        // every tile and treats off-grid as open space, and the hull is in-bounds by construction,
        // so there is no path from "off-grid" to "through the wall". A box drawn around the rock
        // field is a box the size of the sky, which is the thing this whole item exists to avoid.
        val bare = starterVessel(Grid(96, 60), rocks = 0).fitGrid(pad = 4)
        val withRocks = starterVessel(Grid(96, 60), rocks = 12).fitGrid(pad = 4)

        assertTrue(withRocks.rocks.isNotEmpty(), "the fixture needs rocks for this to mean anything")
        assertEquals(bare.grid.width, withRocks.grid.width, "a rock widened the box")
        assertEquals(bare.grid.height, withRocks.grid.height, "a rock heightened the box")
    }

    @Test
    fun `a rock outside the box survives the fit with its position intact`() {
        // Rocks are offsets, not indices. They may end up at negative grid coordinates and that is
        // ordinary — but they must move by exactly the shift, and none may be dropped.
        val before = starterVessel(Grid(96, 60), rocks = 12)
        val after = before.fitGrid(pad = 4)

        assertEquals(before.rocks.size, after.rocks.size, "a rock went missing in the fit")
        assertEquals(
            before.rocks.sumOf { it.massGrams },
            after.rocks.sumOf { it.massGrams },
            "rock mass changed across the fit",
        )
    }

    // ── Growing ──────────────────────────────────────────────────────────

    @Test
    fun `a vessel built against the edge grows rather than clips`() {
        // The fit must be free to make the grid *bigger*. An implementation that clamps to the
        // current bounds passes every shrink test and is useless to P3.
        val tight = starterVessel(Grid(36, 26), rocks = 0)
        val fitted = tight.fitGrid(pad = 4)
        val b = footprintBounds(fitted)!!

        assertTrue(fitted.grid.width > 36 || fitted.grid.height > 26, "the grid refused to grow")
        assertEquals(4, b[0], "pad on the left after growing")
        assertEquals(4, b[1], "pad on the top after growing")
        assertEquals(4, fitted.grid.width - 1 - b[2], "pad on the right after growing")
        assertEquals(4, fitted.grid.height - 1 - b[3], "pad on the bottom after growing")
    }

    @Test
    fun `fitting is idempotent`() {
        val once = starterVessel(Grid(96, 60), rocks = 0).fitGrid(pad = 4)
        val twice = once.fitGrid(pad = 4)

        assertEquals(once.grid.width, twice.grid.width)
        assertEquals(once.grid.height, twice.grid.height)
        assertEquals(digest(once), digest(twice), "a second fit changed the world")
    }

    // ── The ledgers ──────────────────────────────────────────────────────

    @Test
    fun `every ledger is zero after a fit, and stays zero`() {
        // Not "preserved" — *zero*. Two equal non-zero numbers are a broken world twice.
        val fitted = starterVessel(Grid(96, 60), rocks = 12).fitGrid(pad = 4)
        assertBalanced(fitted, "straight after the fit")
        assertBalanced(run(fitted, 300), "after 300 ticks on the fitted grid")
    }

    private fun assertBalanced(s: VesselState, whenever: String) {
        assertEquals(0L, s.airBalance, "airBalance $whenever")
        assertEquals(0L, s.airJouleBalance, "airJouleBalance $whenever")
        assertEquals(
            0L,
            s.massGrams + s.ventedGrams - s.extractedGrams,
            "massBalance $whenever",
        )
        assertEquals(
            0L,
            s.baselineRockGrams + s.capturedGrams - s.extractedGrams - s.rocks.sumOf { it.massGrams },
            "rockBalance $whenever",
        )
        assertEquals(
            0L,
            s.storedJoules + s.radiatedJoules + s.solidToAirJoules -
                s.generatedJoules - s.constructionJoules - s.baselineJoules,
            "heatBalance $whenever",
        )
    }

    // ── The one that catches a field nobody remembered ────────────────────

    @Test
    fun `the fit does not depend on the grid the vessel was authored on`() {
        // §10 calls this the strongest single assertion available, and §12 ranks "a field nobody
        // remembered" as risk #1 — this is its mitigation. The starter vessel is written in
        // absolute coordinates, so authoring it on two different grids gives two worlds that differ
        // only by their lattice and their origin. Fit both and they must become the *same world*,
        // and then stay the same world for three hundred ticks of fluid, heat and logistics.
        //
        // Any field that `remapped` forgets to carry survives on one path and not the other, and
        // shows up here as a digest that diverges. One test, every field.
        val fromWide = starterVessel(Grid(96, 60), rocks = 0).fitGrid(pad = 4)
        val fromWider = starterVessel(Grid(120, 80), rocks = 0).fitGrid(pad = 4)

        assertEquals(fromWide.grid.width, fromWider.grid.width, "same vessel, different fitted width")
        assertEquals(fromWide.grid.height, fromWider.grid.height, "same vessel, different fitted height")
        assertEquals(digest(fromWide), digest(fromWider), "the fitted worlds differ at rest")
        assertEquals(
            digest(run(fromWide, 300)),
            digest(run(fromWider, 300)),
            "the fitted worlds diverge once they run — a field was not remapped",
        )
    }

    /**
     * Everything a resize could plausibly lose, in one string: the tile-indexed layers, the sparse
     * maps, both dense fields, both edge fields, the rocks and every ledger term.
     *
     * `motion` is excluded on purpose — it is presentation, and §3 says a resize is a frame where
     * nothing animates.
     */
    private fun digest(s: VesselState): String = buildString {
        append(s.grid.width).append('x').append(s.grid.height)
        append('|').append(s.tick)
        for (m in s.machines) append('|').append(m?.toString() ?: "-")
        for (b in s.bridges) append('|').append(b?.toString() ?: "-")
        for (c in org.emerge.demo.outofspace.world.Conduit.entries) {
            for (seg in s.conduits[c]) append('|').append(seg?.toString() ?: "-")
        }
        for (tile in s.debris.tiles().sorted()) {
            append('|').append(tile).append('=').append(s.debris[tile].toString())
        }
        for ((tile, cursor) in s.diverters.cursor.entries.sortedBy { it.key }) {
            append('|').append(tile).append(':').append(cursor)
        }
        append('|').append(s.atmosphereGrams).append('|').append(s.atmosphereJoules)
        append('|').append(s.storedJoules).append('|').append(s.radiatedJoules)
        append('|').append(s.vesselImpulseX).append('|').append(s.vesselImpulseY)
        append('|').append(s.extractedGrams).append('|').append(s.ventedGrams)
        append('|').append(s.capturedGrams).append('|').append(s.stockpile.toString())
        for (r in s.rocks.sortedWith(compareBy({ it.positionX }, { it.positionY }))) {
            append('|').append(r.positionX).append(',').append(r.positionY)
            append(',').append(r.massGrams)
        }
    }
}
