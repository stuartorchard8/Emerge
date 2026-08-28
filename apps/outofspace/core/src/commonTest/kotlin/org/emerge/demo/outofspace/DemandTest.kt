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
import org.emerge.demo.outofspace.world.machineBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.material
import org.emerge.demo.outofspace.world.species

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
        s = run(s, 1, OutofspaceInput(listOf(fixtureLay(grid.tile(9, 3), grid.tile(10, 3), Conduit.Rail))))
        s = run(s, 1, OutofspaceInput(listOf(fixtureLay(grid.tile(10, 3), grid.tile(11, 3), Conduit.Rail))))
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
     *
     * ⚠️ **The dirty lump no longer gets through the door, and the claim moved with it rather than
     * being dropped.** At `BUILD_PURITY_PERCENT = 95` a 96:4 blend was admitted, and the point was
     * that its four grams of quartz must not make the two readings disagree about a shortfall they
     * both now measure in matter. At 100 the site does not want that lump at all, so `wants` is
     * false for every route and both readings have to say so **together**: `permits` refuses and
     * `room` answers nought, whatever is or is not already standing on the track.
     *
     * ⛔ **The pairing is still the whole assertion, and the refusal has simply moved upstream.**
     * A lump that cannot be used is now turned away at the *source*, before it is committed, rather
     * than travelling the length of the run to be turned away at the site — which is strictly the
     * better place for it, because a lump refused at a site is a lump standing on a tile that
     * packets can never merge into. What must not happen, in either régime, is one reading saying
     * "yes" while the other says "nought grams": that is a network that stalls with material in the
     * tank, and it is what this test exists to catch.
     */
    @Test
    fun `permits and room agree whether the material can be used or not`() {
        val grid = cfg.initialGrid
        val bill = conduitBillOfMaterials(Conduit.Rail, Conduit.Rail.material.species)
        val gap = 98_000_000_000L
        // 100g of matter each. The first is the recipe and goes through; the second is 4% quartz,
        // which the door now refuses outright.
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

        // The clean lump: usable, so the road being clear or covered is what decides, and the two
        // readings track each other through both.
        val (openClear, roomClear) = readings(clean, carrying = null)
        assertTrue(openClear, "clean: nothing is coming and the gate was shut")
        assertEquals(gap, roomClear, "clean: with the road clear the whole shortfall is worth sending")

        val (openCovered, roomCovered) = readings(clean, carrying = grid.tile(6, 3))
        assertFalse(openCovered, "clean: the shortfall is covered and the gate stayed open")
        assertEquals(0L, roomCovered, "clean: the shortfall is covered and room still wanted more")

        // ⛔ The dirty lump: refused at the source, and refused the same way by both readings
        // whatever is on the route. A "yes" from one and "nought grams" from the other is the stall
        // this test exists to catch, and it would be that failure in either direction.
        for (carrying in listOf(null, grid.tile(6, 3))) {
            val (open, room) = readings(dirty, carrying)
            assertFalse(open, "dirty: a lump nothing can be built from was committed (carrying=$carrying)")
            assertEquals(0L, room, "dirty: room offered to send a lump nothing can use (carrying=$carrying)")
        }
    }

    /**
     * ⛔ **A lump is charged to the sinks that will actually eat it, and to no others.**
     *
     * Two sites past a fork, 300g and 700g short, and 500g standing on the branch only the first of
     * them can draw from. The second is owed its full 700g: nothing on the network is on its way.
     *
     * Both of the rules this replaces get it wrong, in opposite directions, and each was adopted to
     * fix the other:
     *
     *  - charging every lump to **every** route sums the same 500g against both sites, so the pair
     *    read as 1000g wanted and 1000g coming, and the source shut off with 700g still to send;
     *  - charging only the **largest** tally — the compensation — reads covered=500 across the whole
     *    fork, so it sends 500g where 700g is owed and the far site is fed a dribble at a time.
     *
     * Apportionment is neither: the 500g is on a branch only the first site can reach, so the first
     * site takes all of it and the second takes none.
     */
    @Test
    fun `material on one branch is not counted against a sink that cannot receive it`() {
        val grid = cfg.initialGrid
        val bill = conduitBillOfMaterials(Conduit.Rail, Conduit.Rail.material.species)
        val lump = iron(500_000_000_000L)

        //  (2,3)-(3,3)-(4,3)-(5,3)-[A]     A is 300g short, and the 500g stands at (5,3)
        //                  |
        //                (4,4)
        //                  |
        //                 [B]              B is 700g short, with a clear road
        val source = grid.tile(2, 3)
        val siteA = grid.tile(6, 3)
        val siteB = grid.tile(4, 5)
        val tiles = mutableSetOf(
            grid.tile(2, 3), grid.tile(3, 3), grid.tile(4, 3), grid.tile(5, 3), siteA,
            grid.tile(4, 4), siteB,
        )
        val shortA = 300_000_000_000L
        val shortB = 700_000_000_000L

        fun roomAtSource(standingAt: TileIndex): Long {
            val flow = FlowGraph.build(
                tiles,
                sources = setOf(source),
                sinks = setOf(siteA, siteB),
                linked = { tile, dir -> tile in tiles && grid.neighbour(tile, dir) in tiles },
                grid = grid,
            )
            val whitelist = Whitelist.of(
                flow,
                grid.size,
                acceptanceAt = { tile ->
                    when (tile) {
                        siteA -> listOf(Acceptance.forBill(bill, shortA))
                        siteB -> listOf(Acceptance.forBill(bill, shortB))
                        else -> null
                    }
                },
                loadOn = { tile, want ->
                    if (tile != standingAt) 0L
                    else if (want == null || buildableFrom(want, lump)) lump.total else 0L
                },
            )
            return whitelist.room(source, lump)
        }

        assertEquals(
            shortB,
            roomAtSource(standingAt = grid.tile(5, 3)),
            "500g on A's own branch left B looking fed",
        )

        // The other half of the same rule, and the reason the largest-tally hack existed: a lump in
        // the corridor **both** sites draw from is split between them, not charged to each in full.
        // 100g shared against 1000g wanted leaves 900g owed — not 800g, and not nothing.
        val shared = iron(100_000_000_000L)
        val flow = FlowGraph.build(
            tiles,
            sources = setOf(source),
            sinks = setOf(siteA, siteB),
            linked = { tile, dir -> tile in tiles && grid.neighbour(tile, dir) in tiles },
            grid = grid,
        )
        val whitelist = Whitelist.of(
            flow,
            grid.size,
            acceptanceAt = { tile ->
                when (tile) {
                    siteA -> listOf(Acceptance.forBill(bill, shortA))
                    siteB -> listOf(Acceptance.forBill(bill, shortB))
                    else -> null
                }
            },
            loadOn = { tile, want ->
                if (tile != grid.tile(3, 3)) 0L
                else if (want == null || buildableFrom(want, shared)) shared.total else 0L
            },
        )
        assertEquals(
            shortA + shortB - shared.total,
            whitelist.room(source, shared),
            "a packet in the shared corridor was charged to both sites at once",
        )
    }

    /**
     * ⛔ **A site is never in its OWN way — but it is in the way of its neighbour on the same tile.**
     *
     * A ghost *machine* is a sink at the tile it is **fed** at, and nothing says that tile is paid
     * for: draw a run and a machine over the end of it and the site's input port stands on unpaid
     * track. The two appetites then share one tile, and "no blocks on a site's own tile" — which
     * exists so that material can reach the obstruction that is blocking it — was reading that as a
     * fact about the *tile* rather than about the one appetite it is true of.
     *
     * Stu's save, 2026-08-22: a Concentrator construction site at (16,28) over a ghost rail, and a
     * Concentrator at (19,28) three tiles to the right marked for deconstruction. The site's
     * **titanium** appetite propagated up the corridor with a clear road, so the marked machine was
     * allowed to come apart; the door then refused the titanium at the ghost rail, which admits iron
     * and nothing else, and 300kg of casing sat in a corridor it could never leave — in front of the
     * very iron that would have paid for the rail and dissolved the plug. The exact deadlock the
     * whole demand layer exists to prevent, arrived at from the one direction it could not see.
     *
     * Being on the plug's tile is being **behind** it: the material has still got to cross that door.
     *
     * ```
     *  [S] - . - . - g      g is a ghost RAIL with a Concentrator site standing on it
     *   ^ titanium          the site wants titanium; the rail admits iron and nothing else
     * ```
     */
    @Test
    fun `a machine site on unpaid track does not draw what the track will not admit`() {
        val grid = cfg.initialGrid
        val railBill = conduitBillOfMaterials(Conduit.Rail, Conduit.Rail.material.species)
        val siteBill = machineBillOfMaterials(DeckMachineKind.Concentrator, 2, DeckMachineKind.Concentrator.material.species)

        val source = grid.tile(2, 3)
        val ghost = grid.tile(5, 3)
        val tiles = mutableSetOf(source, grid.tile(3, 3), grid.tile(4, 3), ghost)

        val titanium = Mixture.of(Species.Titanium to 100_000_000_000L, energy = 0)
        val ironLump = iron(100_000_000_000L)

        fun permits(lump: Mixture, siteOnGhost: Boolean): Boolean {
            val flow = FlowGraph.build(
                tiles,
                sources = setOf(source),
                sinks = setOf(ghost),
                linked = { tile, dir -> tile in tiles && grid.neighbour(tile, dir) in tiles },
                grid = grid,
                walls = setOf(ghost),
            )
            val whitelist = Whitelist.of(
                flow,
                grid.size,
                acceptanceAt = { tile ->
                    if (tile != ghost) null
                    else buildList {
                        add(Acceptance.forBill(railBill, railBill.total))
                        // The machine site: fed here, and it does not stand in the road itself —
                        // the track it wants to be built over is what does. See `stopsTraffic`.
                        if (siteOnGhost) {
                            add(Acceptance.forBill(siteBill, siteBill.total, stopsTraffic = false))
                        }
                    }
                },
                loadOn = { _, _ -> 0L },
            )
            return whitelist.permits(source, lump, rationed = true)
        }

        assertFalse(
            permits(titanium, siteOnGhost = true),
            "titanium was released toward a site behind a rail ghost that admits only iron",
        )
        assertTrue(
            permits(ironLump, siteOnGhost = true),
            "the ghost rail still has to be able to draw the iron that dissolves it",
        )
        // The control: with no site over it the ghost behaves exactly as it always has.
        assertFalse(permits(titanium, siteOnGhost = false), "a bare ghost rail took titanium")
        assertTrue(permits(ironLump, siteOnGhost = false), "a bare ghost rail refused its own iron")
    }
}
