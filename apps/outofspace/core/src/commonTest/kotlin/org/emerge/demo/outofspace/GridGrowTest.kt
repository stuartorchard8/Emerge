package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.fitGrid
import org.emerge.demo.outofspace.world.growToFit
import org.emerge.demo.outofspace.world.remapped
import org.emerge.demo.outofspace.world.size
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The acceptance tests for P3 — grow on demand — written before the implementation, as P1 and P2
 * were. `PLAN_dynamic_grid.md` §9 and `HANDOFF_P3.md` argue the shape; this states it.
 *
 * The one thing to hold on to while reading: **growth is side-agnostic**. An earlier draft grew only
 * on `+x`/`+y` so that the origin never moved, but `index = y * width + x`, so a far-side growth
 * still changes what every *stored index* means. The holders have to be corrected either way, and
 * once they are, a near-side growth is the same correction plus a reported offset. So every test
 * here that has a far case has the matching near case, and the two must not be different code paths.
 *
 * Expectations are re-derived, never typed: the pad is recomputed from the bounding box, the shift
 * is read back from what growth reported, and the determinism case digests two worlds against each
 * other rather than against a literal.
 */
class GridGrowTest {

    companion object {
        init { RockSpawner.enabled = false }
    }

    private val cfg = OutofspaceConfig(initialGrid = Grid(96, 60))
    private val pad = 4

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(org.emerge.sim.core.PlayerId(0) to OutofspaceInput(edits.toList())))

    /** The starter vessel on a grid that already has exactly [pad] clear on every side. */
    private fun fitted(): VesselState =
        starterVessel(Grid(96, 60)).fitGrid(pad)

    // ── The oracle ────────────────────────────────────────────────────────

    /**
     * An independent re-derivation of what the box must enclose — machine **footprints**, not
     * anchors, plus every conduit segment and bridge. Deliberately not a call into
     * production code: a test that asks the implementation for the answer cannot then check it.
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
        for (c in Conduit.entries) {
            val layer = s.conduits[c]
            for (i in layer.indices) if (layer[i] != null) cover(s.grid.xOf(i), s.grid.yOf(i), 0)
        }

        return if (minX > maxX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    /** Clear tiles between the outermost placed thing and each edge: left, top, right, bottom. */
    private fun margins(s: VesselState): IntArray {
        val b = footprintBounds(s)!!
        return intArrayOf(b[0], b[1], s.grid.width - 1 - b[2], s.grid.height - 1 - b[3])
    }

    /**
     * Every machine's position as `(x, y)`, in row-major order.
     *
     * A translation preserves row-major order, so the two lists line up index for index — which is
     * the point: if they stop lining up, something moved that should not have.
     */
    private fun positions(s: VesselState): List<Pair<Int, Int>> =
        s.machines.indices.filter { s.machines[it] != null }.map { s.grid.xOf(it) to s.grid.yOf(it) }

    /** The tiles a layer occupies, as `(x, y)` in the grid that holds them. */
    private fun occupied(s: VesselState, of: (VesselState) -> List<Int>): Set<Pair<Int, Int>> =
        of(s).map { s.grid.xOf(it) to s.grid.yOf(it) }.toSet()

    /** [state] with a hull dropped straight into the tile list — a placement with no reducer. */
    private fun withHullAt(state: VesselState, x: Int, y: Int): VesselState {
        val machines = state.machines.toMutableList()
        machines[state.grid.index(x, y)] = Hull()
        return state.copy(machines = machines)
    }

    /**
     * The four edges, each as a tile one clear tile in from that edge — which is to say three tiles
     * inside a pad of four, so growth must restore three.
     *
     * `near` marks the edges whose growth moves the origin, and so must report a non-zero offset.
     */
    private fun edgeCases(s: VesselState): List<Triple<String, Pair<Int, Int>, Boolean>> = listOf(
        Triple("left", 1 to s.grid.height / 2, true),
        Triple("top", s.grid.width / 2 to 1, true),
        Triple("right", (s.grid.width - 2) to s.grid.height / 2, false),
        Triple("bottom", s.grid.width / 2 to (s.grid.height - 2), false),
    )

    // ── 1. The pad is restored, on whichever edge was crowded ────────────

    @Test
    fun `placing within the pad grows that edge, all four of them`() {
        for ((name, at, _) in edgeCases(fitted())) {
            val crowded = withHullAt(fitted(), at.first, at.second)
            assertTrue(margins(crowded).any { it < pad }, "$name: the fixture did not crowd an edge")

            val grown = crowded.growToFit(pad)

            val m = margins(grown.state)
            assertTrue(
                m.all { it >= pad },
                "$name: pad not restored — margins ${m.toList()} on a ${grown.state.grid.width}x${grown.state.grid.height} grid",
            )
            // Grown by exactly what was missing, never more: this is grow-on-demand, not refit.
            assertEquals(
                margins(crowded).map { maxOf(it, pad) },
                m.toList(),
                "$name: grew further than the crowding called for",
            )
        }
    }

    // ── 2 & 3. What growth reports is what actually moved ────────────────

    @Test
    fun `growth preserves relative geometry`() {
        for ((name, at, _) in edgeCases(fitted())) {
            val before = withHullAt(fitted(), at.first, at.second)
            val after = before.growToFit(pad).state

            val a = positions(before)
            val b = positions(after)
            assertEquals(a.size, b.size, "$name: a machine went missing")
            // Separations between every pair, unchanged — the property a translation cannot break
            // and a mis-strided remap always does.
            for (i in a.indices) for (j in a.indices) {
                assertEquals(
                    a[i].first - a[j].first to a[i].second - a[j].second,
                    b[i].first - b[j].first to b[i].second - b[j].second,
                    "$name: machines $i and $j moved apart",
                )
            }
        }
    }

    @Test
    fun `the reported delta is what everything actually moved by`() {
        for ((name, at, near) in edgeCases(fitted())) {
            val before = withHullAt(fitted(), at.first, at.second)
            val result = before.growToFit(pad)
            val after = result.state
            val dx = result.dx
            val dy = result.dy

            assertTrue(dx >= 0 && dy >= 0, "$name: growth reported a shrink ($dx, $dy)")
            if (near) {
                assertTrue(dx > 0 || dy > 0, "$name: a near-side growth left the origin alone")
            } else {
                assertEquals(0, dx, "$name: a far-side growth moved the origin in x")
                assertEquals(0, dy, "$name: a far-side growth moved the origin in y")
            }
            assertTrue(result.grew, "$name: growth did not report that it grew")

            // Machines, by position, in row-major order.
            val pa = positions(before)
            val pb = positions(after)
            assertEquals(pa.size, pb.size, "$name: machine count")
            for (i in pa.indices) {
                assertEquals(pa[i].first + dx to pa[i].second + dy, pb[i], "$name: machine $i")
            }

            // Every other tile-addressed thing, as a set of positions shifted by the same delta.
            val layers = buildList<Pair<String, (VesselState) -> List<Int>>> {
                for (c in Conduit.entries) {
                    add("conduit $c" to { s: VesselState -> s.conduits[c].indices.filter { s.conduits[c][it] != null } })
                }
                add("bridges" to { s: VesselState -> s.bridges.indices.filter { s.bridges[it] != null } })
                add("diverters" to { s: VesselState -> s.diverters.forkCursors.keys.toList() })
            }
            for ((what, sel) in layers) {
                assertEquals(
                    occupied(before, sel).map { it.first + dx to it.second + dy }.toSet(),
                    occupied(after, sel),
                    "$name: $what did not move by the reported delta",
                )
            }
        }
    }

    // ── 4. The ledgers ────────────────────────────────────────────────────

    @Test
    fun `every ledger stays zero across a growth on any edge`() {
        // Built **through the reducer**, unlike the geometry cases above: a hull dropped straight
        // into the machine list is ship fabric that `baselineEnergy` never counted, so the world is
        // already 287 MJ adrift before anything grows. The fixture would have read as a growth that
        // broke the heat ledger. This is also the path the player takes.
        for ((name, at, _) in edgeCases(fitted())) {
            val before = fitted()
            assertBalanced(before, "before growing $name — the fixture itself")

            val grown = edit(
                before,
                Edit.Place(before.grid.index(at.first, at.second), MachineKind.Hull, Direction.Right),
            )
            assertNotEquals(before.grid, grown.grid, "$name: the reducer did not grow the grid")
            assertBalanced(grown, "straight after growing $name")
            assertBalanced(run(grown, 300), "300 ticks after growing $name")
        }
    }

    /** ⚠️ The energy identities are **PARKED** for the unit rescale — see [EnergyLedgers]. */
    private fun assertBalanced(s: VesselState, whenever: String) {
        assertEquals(0L, s.airBalance, "airBalance $whenever")
        EnergyLedgers.assertBalanced(s, whenever)
        // Aboard is `inTransitMass`, **not** `mass` — the latter adds the fabric of the ship
        // itself, which no extractor ever produced, so it can never be zero. Stated wrongly once
        // before, which cost a whole session; the rest of the suite says it correctly.
        assertEquals(
            0L,
            s.inTransitMass + s.ventedMass - s.extractedMass,
            "massBalance $whenever",
        )
        // No body conservation (bodies spawn/despawn freely), just check bodies exist.
    }

    // ── 5. The P1 gap, pinned ─────────────────────────────────────────────

    @Test
    fun `motion does not survive a growth`() {
        // A per-tile array sized to the old grid, read by the renderer at new-grid indices. Fixed in
        // `remapped`; pinned here because P3 is the first thing that resizes mid-play.
        val before = withHullAt(fitted(), 1, 10).copy(
            motion = Motion(
                ByteArray(fitted().grid.size) { Motion.FROM_PORT.toByte() },
                LongArray(fitted().grid.size) { 7L },
                emptyMap(),
                emptyList(),
            ),
        )
        val after = before.growToFit(pad).state
        for (tile in 0 until after.grid.size) {
            assertEquals(null, after.motion.arrivedFrom(tile), "stale arrival at $tile")
            assertEquals(0L, after.motion.previousMassAt(tile), "stale mass at $tile")
        }
    }

    // ── 6. The usual case ─────────────────────────────────────────────────

    @Test
    fun `an edit well inside the pad grows nothing`() {
        val s = fitted()
        val inside = withHullAt(s, s.grid.width / 2, s.grid.height / 2)
        val result = inside.growToFit(pad)

        assertEquals(0, result.dx, "dx")
        assertEquals(0, result.dy, "dy")
        assertEquals(inside.grid, result.state.grid, "the grid changed shape for nothing")
        assertTrue(!result.grew, "reported a growth that did not happen")
    }

    @Test
    fun `growing twice is growing once`() {
        val once = withHullAt(fitted(), 1, 10).growToFit(pad)
        val twice = once.state.growToFit(pad)
        assertEquals(once.state.grid, twice.state.grid, "second growth changed the grid")
        assertEquals(0, twice.dx, "second growth moved the origin in x")
        assertEquals(0, twice.dy, "second growth moved the origin in y")
    }

    // ── 7. The holders of a coordinate, which is the P4 work pulled forward

    @Test
    fun `the state reports how far the frame moved, cumulatively`() {
        // The channel every holder outside VesselState reads: nothing about a new state says how
        // far it moved, so the state says. See [FrameShift].
        val s = fitted()
        val start = s.frameShiftX to s.frameShiftY
        val grown = withHullAt(s, 1, 10).growToFit(pad)
        val after = grown.state.copy(
            frameShiftX = grown.state.frameShiftX + grown.dx,
            frameShiftY = grown.state.frameShiftY + grown.dy,
        )

        val shift = FrameShift(s)
        val move = shift.advance(after)
        assertEquals(grown.dx, move.dx, "reported dx")
        assertEquals(grown.dy, move.dy, "reported dy")
        assertTrue(move.moved, "a growth that nothing downstream would notice")
        assertEquals(start.first + grown.dx, after.frameShiftX, "frameShiftX is cumulative")

        // A tile index held across the growth still names the same tile.
        val held = s.grid.index(20, 12)
        assertEquals(
            20 + grown.dx to 12 + grown.dy,
            after.grid.xOf(move.reindex(held)) to after.grid.yOf(move.reindex(held)),
            "a held index followed the frame",
        )
        // And a second advance with nothing new to report is a no-op.
        val again = shift.advance(after)
        assertTrue(!again.moved, "the same growth was reported twice")
        assertEquals(move.reindex(held), again.reindex(move.reindex(held)), "no-op reindex")
    }

    @Test
    fun `the selection survives a near-side growth through the controller`() {
        val controller = OutofspaceController(cfg, fitted())
        // One tick first: `occupancy` is derived by the reducer, so a freshly built world has none
        // and the wiring tool would select nothing.
        controller.stepOnce()
        val before = controller.state
        val extractor = before.machines.indices.first { before.machines[it]?.kind == MachineKind.Extractor }
        controller.tool = Tool.Wire
        controller.apply(extractor)
        assertEquals(extractor, controller.selected, "the fixture did not select the extractor")
        controller.tool = Tool.Build

        // Build into the left pad: the near edge, so the origin moves.
        controller.brush = MachineKind.Hull
        controller.place(before.grid.index(1, before.grid.height / 2))
        val after = controller.stepOnce()

        assertNotEquals(before.grid, after.grid, "the reducer did not grow the grid")
        assertTrue(controller.selected >= 0, "the selection was thrown away")
        assertEquals(
            MachineKind.Extractor,
            after.machines[controller.selected]?.kind,
            "the selection now names a different tile",
        )
        assertEquals(
            before.grid.xOf(extractor) + (after.frameShiftX - before.frameShiftX),
            after.grid.xOf(controller.selected),
            "the selection did not follow the frame",
        )
    }

    // ── 8. Determinism: the strongest single assertion available ─────────

    @Test
    fun `a growth mid-run leaves the same world as having been that size all along`() {
        for ((name, at, near) in edgeCases(fitted())) {
            // A: grows during the tick the edit lands.
            val a0 = fitted()
            val target = a0.grid.index(at.first, at.second)
            val a1 = edit(a0, Edit.Place(target, MachineKind.Hull, Direction.Right))

            // B: the same world, already the size A will grow to, with the same edit at the tile it
            // will have ended up at. `remapped` is P1's, tested in isolation, so B leans on nothing
            // P3 wrote.
            val shape = a0.growToFit(pad)
            val b0 = a0.remapped(shape.state.grid, shape.dx, shape.dy)
            val b1 = edit(
                b0,
                Edit.Place(b0.grid.index(at.first + shape.dx, at.second + shape.dy), MachineKind.Hull, Direction.Right),
            )

            assertEquals(a1.grid, b1.grid, "$name: A did not reach B's shape")
            assertEquals(digest(a1), digest(b1), "$name: the two worlds differ at rest")
            assertEquals(
                digest(run(a1, 300)),
                digest(run(b1, 300)),
                "$name: the two worlds diverge once they run — a field was not remapped" +
                    if (near) " (near-side: the fields moved, so this is the sharper case)" else "",
            )
        }
    }

    /**
     * Everything a resize could plausibly lose, in one string. `motion` is excluded because it is
     * presentation and deliberately dropped; `frameShiftX`/`frameShiftY` because they are a running
     * total of how a world got here rather than a fact about it.
     */
    private fun digest(s: VesselState): String = buildString {
        append(s.grid.width).append('x').append(s.grid.height)
        append('|').append(s.tick)
        for (m in s.machines) append('|').append(m?.toString() ?: "-")
        for (b in s.bridges) append('|').append(b?.toString() ?: "-")
        for (c in Conduit.entries) for (seg in s.conduits[c]) append('|').append(seg?.toString() ?: "-")
        for ((tile, cursor) in s.diverters.forkCursors.entries.sortedBy { it.key }) {
            append('|').append(tile).append(':').append(cursor)
        }
        append('|').append(s.atmosphereMass).append('|').append(s.atmosphereEnergy)
        append('|').append(s.storedEnergy).append('|').append(s.radiatedEnergy)
        append('|').append(s.vesselImpulseX).append('|').append(s.vesselImpulseY)
        append('|').append(s.extractedMass).append('|').append(s.ventedMass)
        append('|').append(s.stockpile.toString())
        for (b in s.bodies.sortedWith(compareBy({ b -> b.positionX }, { b -> b.positionY }))) {
            append('|').append(b.positionX).append(',').append(b.positionY).append(',').append(b.mass)
        }
    }
}
