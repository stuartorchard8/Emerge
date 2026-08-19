package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Acceptance
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Whitelist
import org.emerge.demo.outofspace.world.buildableFrom
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Demand: nothing moves toward a place that cannot use it.
 *
 * These are the three things a network has to get right for construction and deconstruction to be
 * *reversible* rather than a trap, and all three are the same rule seen from different ends:
 *
 *  1. a marked rail does not shed its metal onto a run with nowhere to put it;
 *  2. a storage does not empty itself down a dead end;
 *  3. a construction site does not draw material it cannot be built from.
 *
 * The failure they prevent is one failure. A source that pours into a run no consumer is on fills
 * that run solid, and a solid run is what leaves a marked rail unable to hand its metal back — it is
 * standing behind its own leavings. That is the deadlock recorded in `agent-scripts/ghosts.txt`, and
 * it is reachable by ordinary play: draw a run, let the tank overfill it, change your mind.
 */
class DemandTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun iron(mass: Long) = Mixture.of(Species.Iron to mass, energy = 0)

    /**
     * A tank at (2,3) with its output port on (3,3), and track running right from there to [toX].
     *
     * [sink] puts a vent on the far end — something with an appetite that never ends. Without it the
     * run is a dead end, which is the whole question.
     */
    private fun tankAndRun(toX: Int, sink: Boolean): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(2, 3), Direction.Right)
        if (sink) deck += Vent(grid.tile(toX + 1, 3))
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, if (sink) toX + 1 else toX, 3)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(2, 3), iron(10L * Capacity.PACKET_MASS))
    }

    /** Everything standing on the track, which is what a jammed run leaves behind. */
    private fun onTrack(s: VesselState): Long {
        var total = 0L
        for (i in 0 until s.grid.size) total += s.rail.massAt(TileIndex(i))
        return total
    }

    // ── 2. A storage does not empty itself down a dead end ────────────────────

    @Test
    fun `a storage with nothing downstream keeps its contents`() {
        val s = run(tankAndRun(toX = 9, sink = false), 20 * RAIL_PERIOD)

        assertEquals(0L, onTrack(s), "the tank poured onto a run with no consumer on it")
        assertEquals(
            10L * Capacity.PACKET_MASS,
            s.inStore(s.grid.tile(2, 3), BufferRole.Inside)?.total,
            "and every gram of it should still be in the tank",
        )
    }

    /** The control: the identical run with something on the end of it moves material as it always did. */
    @Test
    fun `a storage with a consumer downstream pours as it always did`() {
        val s = run(tankAndRun(toX = 9, sink = true), 20 * RAIL_PERIOD)
        assertTrue(
            (s.inStore(s.grid.tile(2, 3), BufferRole.Inside)?.total ?: 0L) < 10L * Capacity.PACKET_MASS,
            "a tank with a vent down the line should be emptying",
        )
    }

    /**
     * ⚠️ The distinction the whole design rests on: **a machine is a bottomless sink.** A vent's
     * appetite never ends, so a run reaching one is never a dead end however long it has been
     * running, and demand must never ration such a network into stalling.
     */
    @Test
    fun `a machine's appetite does not run out`() {
        val early = run(tankAndRun(toX = 9, sink = true), 4 * RAIL_PERIOD)
        val late = run(tankAndRun(toX = 9, sink = true), 40 * RAIL_PERIOD)
        val movedEarly = 10L * Capacity.PACKET_MASS - (early.inStore(early.grid.tile(2, 3), BufferRole.Inside)?.total ?: 0L)
        val movedLate = 10L * Capacity.PACKET_MASS - (late.inStore(late.grid.tile(2, 3), BufferRole.Inside)?.total ?: 0L)
        assertTrue(movedLate > movedEarly, "the tank stopped feeding a vent: $movedEarly then $movedLate")
    }

    // ── 1. A marked rail does not shed metal with nowhere to put it ───────────

    @Test
    fun `a rail marked for deconstruction waits when nothing wants its metal`() {
        // A run with no consumer at all, built and then marked. Its metal has nowhere to go.
        val grid = cfg.initialGrid
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 9, 3)
        var s = VesselState(
            grid, DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.empty(grid.size),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)

        // ⚠️ **Outside creative mode**, or `Remove` deletes the rail outright instead of marking it
        // and there is no deconstruction to observe at all. A stated run is finished track already,
        // so nothing needs building first.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.tile(6, 3), DeleteLayer.Rail))))
        s = run(s, 20 * RAIL_PERIOD)

        assertEquals(0L, onTrack(s), "a rail with nowhere to send its metal put it on the track anyway")
        assertTrue(
            s.conduits[Conduit.Rail][grid.tile(6, 3).index] != null,
            "and it should still be standing, waiting, rather than half gone",
        )
    }

    // ── 3. A site does not draw what it cannot be built from ──────────────────

    /**
     * A tank of the wrong stuff, and a construction site down the line.
     *
     * ⛔ This is the anti-exploit and the demand rule meeting, and they are separate refusals. The
     * site refuses quartz because a rail is not made of quartz — that one is old. What is new is
     * that the *tank* never lets go of it either, because nothing reachable from its port can use
     * it: previously the quartz went out, jammed against the site, and stayed there for ever.
     */
    @Test
    fun `a site does not draw material it cannot be built from`() {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(2, 3), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 9, 3)
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
            .stocked(grid.tile(2, 3), Mixture.of(Species.Quartz to 10L * Capacity.PACKET_MASS, energy = 0))

        // Draw two more tiles of track: unbuilt, and wanting iron they will never see.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Lay(grid.tile(9, 3), grid.tile(10, 3), Conduit.Rail))))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Lay(grid.tile(10, 3), grid.tile(11, 3), Conduit.Rail))))
        s = run(s, 30 * RAIL_PERIOD)

        assertEquals(0L, onTrack(s), "quartz was drawn toward a site that can never use it")
        assertEquals(
            10L * Capacity.PACKET_MASS,
            s.inStore(s.grid.tile(2, 3), BufferRole.Inside)?.total,
            "and it should all still be in the tank",
        )
    }

    /**
     * The same rail with a tank to receive it comes apart. Stated as the pair, because "waits" is
     * only the right behaviour if "proceeds" is what happens the moment there is somewhere to go —
     * the refusal has to be reversible or it is just a new way to brick a vessel.
     */
    @Test
    fun `the same rail comes apart once there is somewhere for its metal to go`() {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(11, 3), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 10, 3)
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)

        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.tile(6, 3), DeleteLayer.Rail))))
        s = run(s, 40 * RAIL_PERIOD)

        assertTrue(
            s.conduits[Conduit.Rail][grid.tile(6, 3).index] == null,
            "with a tank down the line the rail should have come apart",
        )
    }

    // ── 4. The two readings of one appetite agree ─────────────────────────────

    /**
     * ⛔ **[Whitelist.permits] and [Whitelist.room] answer the same question and must never
     * disagree** — one says whether any more is worth committing, the other says how much, and a
     * network in which "yes" and "nought grams" are both true stalls with material in the tank.
     *
     * They disagreed for as long as a site's appetite was measured in bill species while the
     * traffic on the track was measured in matter: the junk the door lets through is the difference,
     * and it grows with every delivery. Stu's extractor read as covered by 400g of 97.85% stock
     * while still short of 391.638g of titanium — the stock carries 391.4g, so it fell short, and
     * the gate that would have topped it up had already shut.
     *
     * Both are matter now, so the composition of the lump is not allowed to change either answer.
     * That is what this asserts: the same two tiles, the same demand, one clean blend and one dirty
     * one, and all four readings agree.
     */
    @Test
    fun `permits and room agree however dirty the material is`() {
        val grid = cfg.initialGrid
        val bill = conduitBillOfMaterials(Conduit.Rail)
        val gap = 98_000_000_000L
        // Both are through the door — a rail's threshold is 95% iron — and both are 100g of matter.
        val clean = iron(100_000_000_000L)
        val dirty = Mixture.of(Species.Iron to 96_000_000_000L, Species.Quartz to 4_000_000_000L, energy = 0)

        val source = grid.tile(3, 3)
        val site = grid.tile(9, 3)
        val tiles = (3..9).mapTo(mutableSetOf()) { grid.tile(it, 3) }

        fun readings(lump: Mixture, carrying: TileIndex?): Pair<Boolean, Long> {
            val flow = FlowGraph.build(
                tiles,
                sources = setOf(source),
                sinks = setOf(site),
                linked = { tile, dir ->
                    (dir == Direction.Left || dir == Direction.Right) &&
                        tile in tiles && grid.neighbour(tile, dir) in tiles
                },
                grid = grid,
            )
            val whitelist = Whitelist.of(
                flow,
                grid.size,
                acceptanceAt = { tile -> if (tile == site) listOf(Acceptance.forBill(bill, gap)) else null },
                loadOn = { tile, want ->
                    if (tile != carrying) 0L
                    else if (want == null || buildableFrom(want, lump)) lump.total else 0L
                },
            )
            return whitelist.permits(source, lump, rationed = true) to whitelist.room(source, lump)
        }

        for ((what, lump) in listOf("clean" to clean, "dirty" to dirty)) {
            val (openEmpty, roomEmpty) = readings(lump, carrying = null)
            assertTrue(openEmpty, "$what: nothing is coming and the gate was shut")
            assertEquals(gap, roomEmpty, "$what: with the road clear the whole shortfall is worth sending")

            // 100g of matter standing on the route covers a 98g shortfall — whatever it is made of.
            val (openFull, roomFull) = readings(lump, carrying = grid.tile(6, 3))
            assertFalse(openFull, "$what: the shortfall is covered and the gate stayed open")
            assertEquals(0L, roomFull, "$what: the shortfall is covered and room still wanted more")
        }
    }
}
