package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.massIn
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.contentsOf
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

/**
 * The tile world: belts, machines, jams and the whole-world conservation invariant.
 *
 * The headline assertion is [`nothing is created or destroyed`][`the world never loses a unit of mass`]:
 * an extractor is the only place ore enters the world and a vent the only place it leaves, so
 *
 *     extracted == aboard + vented
 *
 * must hold on **every** tick. One assertion catches an entire category of logistics bug — a packet
 * duplicated on handoff, a jam that eats a slot, a buffer overwritten instead of merged.
 */
class VesselSimTest {

    /**
     * No world rocks, the same way ten other classes here ask for none.
     *
     * These are logistics tests: every body they care about is feedstock they placed on a plate
     * themselves, and [RockSpawner] populating the sky around the vessel is scenery that some of
     * them then have to reason about. `an extractor eats its rock and then stops` asserts the world
     * has no bodies left in it, which is a statement about the feedstock and is only expressible
     * while nothing else is arriving.
     *
     * ⚠️ It matters that this is stated here rather than inherited. [RockSpawner.enabled] is a
     * global that nothing sets back to true, so a class without this line sees rocks or does not
     * depending on which class the runner reached first — and this test passed for exactly that
     * reason until `ticksToMove` grew by [OutofspaceReducer.RAIL_PERIOD] and pushed the run past
     * [RockSpawner.ACTIVATE_AFTER_TICK].
     */
    init { RockSpawner.enabled = false }

    private val cfg = OutofspaceConfig(initialGrid = Grid(40, 28))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun assertBalanced(s: VesselState, what: String) {
        // Storage contents are part of inTransitMass -- the stockpile is a view over the storages,
        // not an account beside them -- so there is no separate "banked" term to add here.
        assertEquals(
            s.extractedMass + s.baselineCargoMass,
            s.inTransitMass + s.ventedMass + s.builtMass,
            "$what: extracted ${s.extractedMass} + baseline ${s.baselineCargoMass} != " +
                "aboard ${s.inTransitMass} + vented ${s.ventedMass} + built ${s.builtMass}",
        )
    }

    // ── Track ─────────────────────────────────────────────────────────────────

    /**
     * An extractor at (2,2) with a run of track from its output port to a **full** tank at [toX] + 1.
     *
     * The tank is what makes this a jam. Material is pulled toward a consumer, so a run that simply
     * stopped would not fill up — nothing would ever leave the extractor at all. A jam is now a
     * destination that has stopped accepting, which is both a truer picture of a factory backing up
     * and a more useful thing to be able to see.
     */
    private fun oreLine(grid: Grid, toX: Int): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 2)
        // Empty to begin with, and filled by the extractor. Starting it full would be quicker but the
        // conservation ledger counts everything aboard as extracted, and 20kg conjured into a tank is
        // exactly the sort of leak that ledger exists to catch.
        deck += Storage(grid.tile(toX + 1, 2), Direction.Right)
        // The plate is five tiles across, so the port is at x=4 and the run starts there.
        joinRow(grid, rails, 4, toX, 2)
        // Creative: one of these tears the tank out mid-run to watch the jam clear, and a marked
        // tank would stand there full instead.
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).copy(creative = true)
    }

    @Test
    fun `a jam fills the track from the far end backwards and stays visible`() {
        // A full tank at the end of the run. It should pack solid from the end nearest the tank.
        val grid = Grid(12, 5)
        var s = oreLine(grid, toX = 7)
        // Long enough to fill the tank and then back the line up behind it.
        s = run(s, ticksToMove(Storage.CAP + Extractor.BUFFER_CAP))

        val carried = (4..7).map { s.rail.massAt(grid.tile(it, 2)) }
        assertTrue(carried.all { it > 0L }, "every tile should be carrying something: $carried")
        assertTrue(
            (s.inStore(grid.tile(2, 2), BufferRole.Product)?.total ?: 0L) >= Extractor.BUFFER_CAP,
            "and the extractor should have stopped digging",
        )
        assertBalanced(s, "jammed line")
    }

    /**
     * From a save Stu sent: a line that jammed solid two tiles from a vent that would have taken
     * everything on it.
     *
     * The shape is a run with a **branch**. Material goes right along row 2 toward a tank, and the
     * branch turns up to a vent. Once the tank is full, everything should turn up the branch and go
     * overboard — the tank stops pulling, so the traffic goes where it can.
     *
     * It used not to. The tank's input tile was a consumer, consumers were terminal, and a packet
     * the full tank refused could not move anywhere at all — least of all back up a branch it had
     * already passed. The line seized with an open vent in plain sight.
     */
    @Test
    fun `a full tank is something the traffic goes round, not a wall across the line`() {
        val grid = Grid(12, 8)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 5)
        deck += Storage(grid.tile(8, 5), Direction.Right)          // in at (7, 5)
        deck += Vent(grid.tile(5, 2))                                       // in at its own tile
        joinRow(grid, rails, 4, 7, 5)
        joinCol(grid, rails, 5, 2, 5)   // the branch, up from the middle of the run to the vent

        // Long enough to eat the rock whole, which is more than twice what the tank holds.
        val s = run(
            VesselState(
                grid, deck,
                conduits = Conduits.ofRails(rails.toList()),
                bodies = feed,
                buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
            ),
            480*RAIL_PERIOD,
        )

        assertEquals(Storage.CAP, s.buffers.resourceAt(grid.tile(8, 5))?.total, "the tank filled")
        assertTrue(s.ventedMass > 0L, "and the rest went up the branch and overboard")
        assertBalanced(s, "line with a full tank and an open vent")
    }

    /**
     * The second save Stu sent: a line splitting to a vent and to a tank sent everything overboard.
     *
     * The vent happened to be the nearer of the two, and "move toward the nearest consumer" cannot
     * tell that apart from a fork. The whole point of a branch is that it branches.
     */
    @Test
    fun `a branch splits between its outputs instead of feeding the nearest`() {
        val grid = Grid(12, 10)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 5)
        deck += Vent(grid.tile(5, 2))                                       // two tiles up from the fork
        deck += Storage(grid.tile(9, 5), Direction.Right)          // four tiles along, in at (8, 5)
        joinRow(grid, rails, 4, 8, 5)
        joinCol(grid, rails, 5, 2, 5)

        val s = run(
            VesselState(
                grid, deck,
                conduits = Conduits.ofRails(rails.toList()),
                bodies = feed,
                buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
            ),
            120*RAIL_PERIOD,
        )

        assertTrue(s.ventedMass > 0L, "the vent took a share")
        val stored = s.buffers.resourceAt(grid.tile(9, 5))?.total ?: 0L
        assertTrue(stored > 0L, "and so did the tank, which used to get nothing at all")
        // Not an exact split: the tank stops pulling when it fills, and everything then goes
        // overboard. Both being fed while both can take is the property that matters.
        assertTrue(stored >= s.ventedMass / 2, "and the shares are comparable, not lopsided: $stored vs ${s.ventedMass}")
        assertBalanced(s, "forked line")
    }

    /**
     * The third save Stu sent: two storages, each recirculating on its own loop of track, and the
     * only difference between them was that something else also fed one of the loops. The fed one
     * stopped dead — its own material sat on the tile outside its output port and never moved,
     * while the untouched loop ran perfectly.
     *
     * A producer joining partway along a run does not merely add material to it. Depth is measured
     * from every source at once, so the newcomer resets depth to zero where it lands and inverts the
     * gradient over everything upstream of it. The two waves meet at a tile with nothing deeper
     * beside it, which therefore has no forward at all — and since a branch leading nowhere is not
     * worth entering, that emptiness spreads back up the line until the first producer has nowhere
     * to put anything either.
     *
     * Merging two feeds into one line is completely ordinary — it is what a bridge dropping onto a
     * main run *is* — so this is the case that says the forward rule cannot be the only rule.
     */
    @Test
    fun `a loop that something else also feeds still carries its own material`() {
        val grid = Grid(12, 10)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)

        // A storage recirculating on its own loop: out at (8, 5), round, and back in at (6, 5).
        // Empty to begin with: everything aboard counts as extracted, so seeding the tank would trip
        // the conservation ledger rather than test anything.
        deck += Storage(grid.tile(7, 5), Direction.Right)
        joinRow(grid, rails, 8, 9, 5)
        joinCol(grid, rails, 9, 5, 7)
        joinRow(grid, rails, 4, 9, 7)
        joinCol(grid, rails, 4, 5, 7)
        joinRow(grid, rails, 4, 6, 5)

        // ...and an extractor dropping onto that same loop, its port at (5, 7).
        val feed = feedExtractor(grid, deck, 3, 7)

        var s = run(
            VesselState(
                grid, deck,
                conduits = Conduits.ofRails(rails.toList()),
                bodies = feed,
                buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
            ),
            120*RAIL_PERIOD,
        )

        // The far arm is the stretch between the storage's output and where the extractor joins. It is
        // exactly what went quiet, so material standing on it is the whole assertion.
        val farArm = listOf(grid.tile(9, 5), grid.tile(9, 6), grid.tile(9, 7), grid.tile(8, 7))
        assertTrue(
            farArm.any { s.onRail(it) != null },
            "the storage's own material never got out onto the loop",
        )

        // ⚠️ **Movement, not occupancy** — and the difference is the whole of what this test got
        // wrong before. A loop that is working *fills*: every tile carries a lump, the extractor
        // can no longer force one on (a packet already travelling has priority over a source
        // feeding onto the same rail), and the storage takes delivery at its input port and sets
        // the same lump back down through its output inside the one rail step. So `contents` is
        // empty at the end of every tick the world ever runs, and the tiles all look identical
        // from one step to the next. Asserting on either reads a turning loop as a dead one.
        //
        // [Motion] is the mover's own record of what it just did, which is exactly the fact no
        // snapshot of the buffers can recover: `departures` names the packets it took off the rail
        // and handed over, and `arrivedFrom` names the tiles whose lump is new.
        //
        // Watched across several rail steps because either can legitimately be quiet for one —
        // a step where the storage's output had nowhere to set down hands nothing over.
        val handover = grid.tile(6, 5)
        var handedToStorage = false
        var loopTurned = false
        repeat(4) {
            s = run(s, RAIL_PERIOD)
            if (s.motion.departures.any { it.tile == handover }) handedToStorage = true
            if (farArm.any { s.motion.arrivedFrom(it) != null }) loopTurned = true
        }
        assertTrue(handedToStorage, "the loop never carried the extractor's material into the storage")
        assertTrue(loopTurned, "the loop stopped turning once it was full")
        assertBalanced(s, "merged loop")
    }

    /**
     * Ignored because I deleted debris from the sim.
     * This **may** pass again after we introduce machine removal as body fragments.
     */
    @Ignore
    @Test
    fun `a jam clears from the front when the blockage is removed`() {
        val grid = Grid(12, 5)
        var s = oreLine(grid, toX = 7)
        s = run(s, ticksToMove(Storage.CAP + Extractor.BUFFER_CAP))

        // Tear out the full tank and put a vent on the end of the run instead. The vent takes
        // anything, so the line drains from the front — the tile nearest the consumer moves first.
        s = run(s, 40, OutofspaceInput(listOf(
            Edit.Remove(grid.tile(8, 2)),
            fixturePlace(grid.tile(7, 2), Brush.Building(DeckMachineKind.Vent), Direction.Right),
        )))
        assertTrue(s.ventedMass > 0L, "material should have gone overboard")
        assertBalanced(s, "drained line")
    }

    @Test
    fun `track under no source carries nothing, however much is beside it`() {
        // A run that no output port feeds is not part of any network. It is just track.
        // (See also the companion below: track with no *consumer* is equally inert, for the
        // opposite reason.)
        val grid = Grid(12, 5)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 2)
        // Starts one tile past the extractor's output port, so nothing ever reaches it.
        joinRow(grid, rails, 5, 8, 2)
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
        s = run(s, 80)

        assertEquals(0L, (5..8).sumOf { s.rail.massAt(grid.tile(it, 2)) })
        assertBalanced(s, "orphan track")
    }

    @Test
    fun `a run with no consumer on the end of it never fills up`() {
        // The rule that replaced "material piles up at a dead end". Nothing pulls, so the extractor's
        // output has nowhere to be and it backs up in the extractor itself — where it is obvious — with
        // the track left clean. Under the old push model this line packed solid with stock the
        // player then had to dig back out of it.
        val grid = Grid(12, 5)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 2)
        joinRow(grid, rails, 4, 7, 2)
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
        s = run(s, 120)

        // One packet does leave the extractor: pushing out onto the tile under an output port is how
        // material enters a network at all, and that happens before anything asks where it is going.
        // It gets no further, which is the part that matters.
        assertEquals(
            0L,
            (5..7).sumOf { s.rail.massAt(grid.tile(it, 2)) },
            "nothing travelled: there is nothing to travel toward",
        )
        assertTrue(
            (s.inStore(grid.tile(2, 2), BufferRole.Product)?.total ?: 0L) >= Extractor.BUFFER_CAP,
            "and the backlog is where you can see it, in the extractor",
        )
        assertBalanced(s, "unconsumed line")
    }

    // ── Machines ──────────────────────────────────────────────────────────────

    @Test
    fun `an extractor eats its rock and then stops, because ore is not made any more`() {
        // The whole of H3 in one assertion, and the thing the miner could never do: run out.
        val grid = Grid(12, 5)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 2)
        deck += Vent(grid.tile(5, 2))   // takes everything, so the extractor never backs up
        joinRow(grid, rails, 4, 5, 2)
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
        s = run(s, ticksToMove(FEEDSTOCK_MASS))

        assertEquals(emptyList(), s.bodies, "the rock should be gone entirely")
        assertEquals(FEEDSTOCK_MASS, s.extractedMass, "and every gram of it should have become ore")
        assertBalanced(s, "extractor into a vent")

        // Nothing more arrives, however long it is left running.
        val after = run(s, 100)
        assertEquals(s.extractedMass, after.extractedMass, "an empty plate produces nothing")
    }

    @Test
    fun `what a storage holds is what the vessel can build with`() {
        val grid = Grid(10, 5)
        val ingot = SolidPacket(Mixture.of(Species.Iron to 1_000L, energy = 0))
        val deck = DeckArray(grid)
        // The tank faces open deck beyond it, so it fills rather than draining.
        deck += Storage(grid.tile(4, 2), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        rails[grid.tile(3, 2).index] = Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))                 // its input port
        // Creative: the point is that the stockpile is *where things are*, shown by taking the tank
        // away. Outside creative a delete marks the tank and it stands there, still holding the
        // ingot, which would be a different question.
        var s = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .copy(creative = true)
            .riding(grid.tile(3, 2), ingot.contents)
        s = run(s, RAIL_PERIOD)
        assertEquals(1_000L, s.buffers.resourceAt(grid.tile(4, 2))!!.total, "it landed in the tank")
        assertEquals(1_000L, s.stockpile.totalMass, "and the stockpile is that tank")

        // Take the tank away and the stockpile goes with it: availability is a fact about where
        // things are, not a number banked somewhere safe.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.tile(4, 2)))))
        assertEquals(0L, s.stockpile.totalMass)
    }

    /**
     * A belt that keeps moving, with a machine tapping it as material goes by.
     *
     * The machine faces **across** the line rather than along it, so its input port lands on a belt
     * tile while both of its outputs — product ahead, waste below — sit off the rail entirely. That
     * is what makes the belt a through-route: a machine straddling the line puts its own output back
     * onto the track behind its input, and material it refuses has nowhere to go but into the mouth
     * that just refused it.
     *
     * What is being tested is the split between the two halves of the rework. The flow graph does not
     * know what a concentrator eats — it says only which way material may travel, and the belt runs
     * left to right whatever is standing beside it. Whether *this* packet is taken is settled at the
     * tile, when it is offered. So ore is lifted off in passing and an ingot rides straight past to
     * the tank at the end, on the same belt, with nothing in the topology distinguishing them.
     */
    private fun tappedBelt(
        machine: (TileIndex) -> DeckMachine,
        carried: Packet,
        /**
         * Fills the machine's input to the brim, so the only thing it can do with a passer-by is
         * refuse it.
         *
         * ⚠️ **The processing chamber is filled too, and it has to be.** `refine` moves Input into
         * Inside *before* it consults activation, so a concentrator with an empty chamber swallows its
         * own input on the very first tick however hard it is wired shut — and then has room again.
         * A full chamber is what makes "full" a state rather than a moment.
         */
        stuffed: Boolean = false,
    ): VesselState {
        val grid = Grid(12, 6)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)

        // The belt: (1,2) through to (8,2), and a tank at the end taking delivery at (8,2).
        joinRow(grid, rails, 1, 8, 2)
        deck += Storage(grid.tile(9, 2), Direction.Right)

        // The machine sits below the belt facing down, so its input port is the belt tile (4,2)
        // above it and its outputs are at (4,4) and (3,3), where there is no track to receive them.
        deck += machine(grid.tile(4, 3))

        // The lump itself goes on the layer once the state exists, since the track no longer
        // carries its own load.
        var s = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .riding(grid.tile(1, 2), (carried as? SolidPacket)?.contents)
        if (stuffed) {
            val full = Mixture.of(Species.Iron to MACHINE_BUFFER_CAP, energy = 0)
            s = s.stocked(grid.tile(4, 3), full, BufferRole.Inside)
                .stocked(grid.tile(4, 3), full, BufferRole.Input)
        }
        return run(s, 12*RAIL_PERIOD)
    }

    /**
     * ⛔ **On a belt nothing feeds, a lump stops at the first consumer it reaches.** This fixture has
     * no producer at all — no extractor, no output port, just a lump set down on the track — so the
     * flow graph orients it by distance to the nearest consumer, and the concentrator tapping the line
     * at `(4,2)` is nearer than the tank at the end. The concentrator is full and refuses it, so there
     * it stays.
     *
     * ⚠️ **This used to assert the opposite**, that the lump rode past the full machine to the tank,
     * as the sim-level statement of "a tapped line is a through-route". That property is real and
     * still holds — but it is a property of track a *producer* grounds, where `leading` commits the
     * whole run end to end, and it is pinned at the graph level by
     * [FlowStandingLoadTest.a source defines the direction of travel, not the nearest sink]. On
     * producer-less track a consumer is now a terminus. Stu's call, 2026-08-20: a rail with nothing
     * feeding it is a degenerate case, and the consistent, intuitive answer there is the closest
     * sink, even when the closest sink happens to be full.
     *
     * ⚠️ **Its sibling below is unaffected** and is the one that still shows a machine taking
     * material off the line, since an *empty* concentrator at `(4,2)` absorbs the lump either way.
     */
    @Test
    fun `a lump a full concentrator will not take waits on a belt nothing feeds`() {
        val lump = SolidPacket(Mixture.of(Species.Iron to 1_000L, energy = 0))
        val s = tappedBelt(
            { tile -> Concentrator(tile, Direction.Down).withWiring(Wiring(mapOf(Action.Run to emptyList()))) },
            lump,
            stuffed = true,
        )

        assertEquals(
            MACHINE_BUFFER_CAP,
            s.inStore(grid43(s), BufferRole.Input)?.total,
            "the concentrator was full and took nothing more",
        )
        assertNull(
            s.buffers.resourceAt(s.grid.tile(9, 2))?.total,
            "and with the line pointed at the nearer consumer the tank never saw it",
        )
        assertEquals(
            1_000L,
            s.rail.resourceAt(s.grid.tile(4, 2))?.total,
            "the lump is standing at the machine that refused it",
        )
    }

    @Test
    fun `ore on the same belt is lifted off in passing`() {
        // The other half, on the identical layout: the belt has not changed shape, so the only thing
        // that decided this packet's fate is what the machine was willing to take.
        val ore = SolidPacket(Mixture.of(Species.Iron to 1_000L, energy = 0))
        val s = tappedBelt({ tile -> Concentrator(tile, Direction.Down) }, ore)
        // Asserted as conservation rather than as "the input buffer is not empty", which is a moment
        // and not a fact: the concentrator grinds at 125 g a tick, so whether the ore is still in the
        // mouth, half separated, or wholly turned into concentrate and tailings depends only on when
        // you happen to look. What must hold at any tick is that all of it is accounted for.
        val taken = massIn(s.deck[grid43(s)], grid43(s), s.grid, s.buffers)
        assertEquals(1_000L, taken, "the concentrator should have taken the ore off the belt")
        assertNull(
            s.buffers.resourceAt(s.grid.tile(9, 2)),
            "so nothing should have reached the tank",
        )
    }

    private fun grid43(s: VesselState): TileIndex = s.grid.tile(4, 3)

    // ── Edits ─────────────────────────────────────────────────────────────────

    @Test
    fun `placing never overwrites an existing machine`() {
        // Room for the tank's whole footprint: a warehouse is three tiles across, and a deck
        // machine that does not fit the grid is not a machine at all.
        val grid = Grid(5, 5)
        val at = grid.tile(2, 2)
        val deck = DeckArray(grid)
        val store = Storage(at, Direction.Right)
        deck += store
        val held = Mixture.of(Species.Iron to 999L, energy = 0)
        var s = VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .stocked(at, held)
        s = run(s, 1, OutofspaceInput(listOf(fixturePlace(at, Brush.Building(DeckMachineKind.Sensor), Direction.Right))))
        // Compared as the machine itself: its heat lives in the deck layer now, so a tick of
        // conduction moves the layer rather than the object and the object is unchanged.
        assertEquals(
            store,
            s.deck[at],
            "a stray click must not destroy a machine and its contents",
        )
        assertEquals(held, s.buffers.resourceAt(at), "nor empty its store")
    }

    @Test
    fun `rotating turns a machine clockwise and leaves its contents alone`() {
        val grid = Grid(10, 6)
        val stored = Mixture.of(Species.Iron to 100L, energy = 0)
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(4, 3), Direction.Right)
        var s = VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size)).stocked(grid.tile(4, 3), stored)
        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(grid.tile(4, 3)))))
        val tank = s.deck[grid.tile(4, 3)] as? Storage
        assertEquals(Direction.Down, tank!!.facing)
        assertEquals(100L, s.buffers.massAt(grid.tile(4, 3)))
    }

    @Test
    fun `edits apply in PlayerId order, not map order`() {
        val grid = Grid(2, 1)
        val deck = DeckArray(grid)
        val base = VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
        val a = mapOf(
            PlayerId(0) to OutofspaceInput(listOf(fixturePlace(TileIndex(0), Brush.Building(DeckMachineKind.Sensor), Direction.Right))),
            PlayerId(1) to OutofspaceInput(listOf(fixturePlace(TileIndex(0), Brush.Building(DeckMachineKind.Pump), Direction.Right))),
        )
        val b = mapOf(
            PlayerId(1) to OutofspaceInput(listOf(fixturePlace(TileIndex(0), Brush.Building(DeckMachineKind.Pump), Direction.Right))),
            PlayerId(0) to OutofspaceInput(listOf(fixturePlace(TileIndex(0), Brush.Building(DeckMachineKind.Sensor), Direction.Right))),
        )
        assertEquals(
            OutofspaceReducer.reduce(cfg, base, a)[TileIndex(0)],
            OutofspaceReducer.reduce(cfg, base, b)[TileIndex(0)],
            "player 0 wins the tile either way",
        )
    }

    // ── The invariant that matters ────────────────────────────────────────────

    /**
     * The tick is the unit of simulation, so the tick *rate* is a speed dial and nothing else.
     *
     * This is the test the previous design could not pass. It spent a fractional carry, a
     * sub-stepping loop and a whole test-clock helper trying to make the world come out the same per
     * *second* at any rate, and still leaked — concentrator purity moved from 65% to 79% across the
     * range, because the chunk it rounds is a chunk per tick. Stating every rate per tick makes the
     * question disappear rather than answering it: there is no second unit left to disagree with.
     *
     * A digest over the whole text of the world is deliberately blunt. Anything that comes out
     * different — a gram, a carry, a diverter cursor, a joule — fails it.
     */
    @Test
    fun `the tick rate changes how fast you watch, not what happens`() {
        fun play(hz: Int): String {
            val c = OutofspaceConfig(initialGrid = cfg.initialGrid, ticksPerSecond = hz)
            var s = starterVessel(c.initialGrid)
            repeat(200) { s = OutofspaceReducer.reduce(c, s, emptyMap()) }
            return Save.write(s)
        }
        assertEquals(play(4), play(60), "200 ticks is 200 ticks at any rate")
        assertEquals(play(4), play(1), "including a rate nobody would choose")
    }

    @Test
    fun `the world never loses a unit of mass`() {
        var s = workingVessel(cfg.initialGrid)
        repeat(360) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 97 == 0) assertBalanced(s, "tick ${s.tick}")
        }
        assertBalanced(s, "final")
        assertTrue(s.extractedMass > 50_000L, "the line should have moved real tonnage: ${s.extractedMass}")
    }

    @Test
    fun `species are conserved too, not merely total mass`() {
        var s = workingVessel(cfg.initialGrid)
        repeat(240) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        // Everything the extractors dug, versus everything that exists anywhere now. Vented material is
        // gone for good, so it is reconstructed from what the vents recorded... which they do not
        // itemise — so this checks the species balance of what remains against what was extracted,
        // allowing only for the vented total.
        // Everything on the track counts too -- it is a separate list, and forgetting it here once
        // made a perfectly healthy world look 5kg short.
        val onTrack = s.rails.indices.fold(Mixture.EMPTY) { acc, i -> acc + (s.onRail(TileIndex(i)) ?: Mixture.EMPTY) }
        // Both lists: what a warehouse holds is as much "in the world" as what a concentrator holds, and
        // warehouses stand on the deck. Walking a second list left the tanks out and the world
        // looked short by exactly what was banked in them.
        val inWorld = s.grid.tiles.fold(onTrack) { acc, tile ->
            val m = s.deck[tile]
            if (m == null || m.center != tile) acc else acc + contentsOf(m, tile, s.grid, s.buffers)
        }
        val accountedFor = inWorld.total + s.ventedMass + s.builtMass
        // Plus the stock the vessel started with, which was never dug up — see
        // [VesselState.baselineCargoMass]. A ship that builds its own track begins with iron in a
        // tank, and without this term that iron reads as ore nobody extracted.
        assertEquals(s.extractedMass + s.baselineCargoMass, accountedFor)

        // And no species appeared from nowhere: only what the ore body contains is present.
        // Read off the ore body rather than restated, so changing what the extractor mines is not
        // also a test edit. The claim is "nothing the ore body does not contain", and that is a
        // statement about the ore body — spelling it out again only creates a second thing to keep
        // in step.
        val fromOreBody = Species.ALL.filter { OutofspaceReducer.DEFAULT_ORE_BODY[it] > 0L }.toSet()
        val present = Species.ALL.filter { inWorld[it] > 0L }
        assertTrue(present.all { it in fromOreBody }, "unexpected species in the world: $present")
    }

    @Test
    fun `two runs of the same world are identical`() {
        fun digest(s: VesselState): String = buildString {
            append(s.tick).append('|').append(s.extractedMass).append('|').append(s.ventedMass)
            append('|').append(s.stockpile.toString())
        }
        assertEquals(
            digest(run(starterVessel(cfg.initialGrid), 600)),
            digest(run(starterVessel(cfg.initialGrid), 600)),
        )
    }

    @Test
    fun `packets on the track are always whole and never oversized`() {
        var s = workingVessel(cfg.initialGrid)
        repeat(160) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            for (i in s.rails.indices) {
                val mass = s.rail.massAt(TileIndex(i))
                if (mass == 0L) continue
                assertTrue(mass in 1L..Capacity.PACKET_MASS, "bad packet on the track: ${mass}g")
            }
        }
    }
}
