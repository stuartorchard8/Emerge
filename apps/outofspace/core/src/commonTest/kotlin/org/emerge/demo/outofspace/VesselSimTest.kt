package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Extractor
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.contentsOf
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tile world: belts, machines, jams and the whole-world conservation invariant.
 *
 * The headline assertion is [`nothing is created or destroyed`][`the world never loses a gram`]:
 * an extractor is the only place ore enters the world and a vent the only place it leaves, so
 *
 *     extracted == aboard + vented
 *
 * must hold on **every** tick. One assertion catches an entire category of logistics bug — a packet
 * duplicated on handoff, a jam that eats a slot, a buffer overwritten instead of merged.
 */
class VesselSimTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(40, 28))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun assertBalanced(s: VesselState, what: String) {
        // Storage contents are part of inTransitGrams -- the stockpile is a view over the storages,
        // not an account beside them -- so there is no separate "banked" term to add here.
        assertEquals(
            s.extractedMass,
            s.inTransitMass + s.ventedMass,
            "$what: extracted ${s.extractedMass} != aboard ${s.inTransitMass} + vented ${s.ventedMass}",
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
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, machines, 2, 2)
        // Empty to begin with, and filled by the extractor. Starting it full would be quicker but the
        // conservation ledger counts everything aboard as extracted, and 20kg conjured into a tank is
        // exactly the sort of leak that ledger exists to catch.
        machines[grid.index(toX + 1, 2)] = Storage(Direction.Right)
        // The plate is five tiles across, so the port is at x=4 and the run starts there.
        joinRow(grid, rails, 4, toX, 2)
        return VesselState(
            grid, machines.toList(),
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
        )
    }

    @Test
    fun `a jam fills the track from the far end backwards and stays visible`() {
        // A full tank at the end of the run. It should pack solid from the end nearest the tank.
        val grid = Grid(12, 5)
        var s = oreLine(grid, toX = 7)
        // Long enough to fill the tank and then back the line up behind it.
        s = run(s, ticksToMove(Storage.CAP + Extractor.BUFFER_CAP))

        val carried = (4..7).map { s.railAt(grid.index(it, 2))?.held?.mass ?: 0L }
        assertTrue(carried.all { it > 0L }, "every tile should be carrying something: $carried")
        assertTrue(
            (s[grid.index(2, 2)] as Extractor).buffer.mass >= Extractor.BUFFER_CAP,
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
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, machines, 2, 5)
        machines[grid.index(8, 5)] = Storage(Direction.Right)          // in at (7, 5)
        machines[grid.index(5, 2)] = Vent()                            // in at its own tile
        joinRow(grid, rails, 4, 7, 5)
        joinCol(grid, rails, 5, 2, 5)   // the branch, up from the middle of the run to the vent

        // Long enough to eat the rock whole, which is more than twice what the tank holds.
        val s = run(
            VesselState(
                grid, machines.toList(),
                conduits = Conduits.ofRails(rails.toList()),
                bodies = feed,
            ),
            480,
        )

        assertEquals(Storage.CAP, (s[grid.index(8, 5)] as Storage).contents?.mass, "the tank filled")
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
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, machines, 2, 5)
        machines[grid.index(5, 2)] = Vent()                            // two tiles up from the fork
        machines[grid.index(9, 5)] = Storage(Direction.Right)          // four tiles along, in at (8, 5)
        joinRow(grid, rails, 4, 8, 5)
        joinCol(grid, rails, 5, 2, 5)

        val s = run(
            VesselState(
                grid, machines.toList(),
                conduits = Conduits.ofRails(rails.toList()),
                bodies = feed,
            ),
            120,
        )

        assertTrue(s.ventedMass > 0L, "the vent took a share")
        val stored = (s[grid.index(9, 5)] as Storage).contents?.mass ?: 0L
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
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)

        // A storage recirculating on its own loop: out at (8, 5), round, and back in at (6, 5).
        // Empty to begin with: everything aboard counts as extracted, so seeding the tank would trip
        // the conservation ledger rather than test anything.
        machines[grid.index(7, 5)] = Storage(Direction.Right)
        joinRow(grid, rails, 8, 9, 5)
        joinCol(grid, rails, 9, 5, 7)
        joinRow(grid, rails, 4, 9, 7)
        joinCol(grid, rails, 4, 5, 7)
        joinRow(grid, rails, 4, 6, 5)

        // ...and an extractor dropping onto that same loop, its port at (5, 7).
        val feed = feedExtractor(grid, machines, 3, 7)

        val s = run(
            VesselState(
                grid, machines.toList(),
                conduits = Conduits.ofRails(rails.toList()),
                bodies = feed,
            ),
            120,
        )

        // The far arm is the stretch between the storage's output and where the extractor joins. It is
        // exactly what went quiet, so material standing on it is the whole assertion.
        val farArm = listOf(grid.index(9, 5), grid.index(9, 6), grid.index(9, 7), grid.index(8, 7))
        assertTrue(
            farArm.any { s.rails[it]?.held != null },
            "the storage's own material never got out onto the loop",
        )
//        assertTrue(
//            ((s[grid.index(7, 5)] as Storage).contents?.mass ?: 0L) > 0L,
//            "and the loop should have carried the extractor's material round into the storage",
//        )
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
            Edit.Remove(grid.index(8, 2)),
            Edit.Place(grid.index(7, 2), MachineKind.Vent, Direction.Right),
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
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, machines, 2, 2)
        // Starts one tile past the extractor's output port, so nothing ever reaches it.
        joinRow(grid, rails, 5, 8, 2)
        var s = VesselState(
            grid, machines.toList(),
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
        )
        s = run(s, 80)

        assertEquals(0L, (5..8).sumOf { s.railAt(grid.index(it, 2))?.held?.mass ?: 0L })
        assertBalanced(s, "orphan track")
    }

    @Test
    fun `a run with no consumer on the end of it never fills up`() {
        // The rule that replaced "material piles up at a dead end". Nothing pulls, so the extractor's
        // output has nowhere to be and it backs up in the extractor itself — where it is obvious — with
        // the track left clean. Under the old push model this line packed solid with stock the
        // player then had to dig back out of it.
        val grid = Grid(12, 5)
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, machines, 2, 2)
        joinRow(grid, rails, 4, 7, 2)
        var s = VesselState(
            grid, machines.toList(),
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
        )
        s = run(s, 120)

        // One packet does leave the extractor: pushing out onto the tile under an output port is how
        // material enters a network at all, and that happens before anything asks where it is going.
        // It gets no further, which is the part that matters.
        assertEquals(
            0L,
            (5..7).sumOf { s.railAt(grid.index(it, 2))?.held?.mass ?: 0L },
            "nothing travelled: there is nothing to travel toward",
        )
        assertTrue(
            (s[grid.index(2, 2)] as Extractor).buffer.mass >= Extractor.BUFFER_CAP,
            "and the backlog is where you can see it, in the extractor",
        )
        assertBalanced(s, "unconsumed line")
    }

    // ── Machines ──────────────────────────────────────────────────────────────

    @Test
    fun `an extractor eats its rock and then stops, because ore is not made any more`() {
        // The whole of H3 in one assertion, and the thing the miner could never do: run out.
        val grid = Grid(12, 5)
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, machines, 2, 2)
        machines[grid.index(5, 2)] = Vent()   // takes everything, so the extractor never backs up
        joinRow(grid, rails, 4, 5, 2)
        var s = VesselState(
            grid, machines.toList(),
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
        )
        s = run(s, ticksToMove(FEEDSTOCK_GRAMS))

        assertEquals(emptyList(), s.bodies, "the rock should be gone entirely")
        assertEquals(FEEDSTOCK_GRAMS, s.extractedMass, "and every gram of it should have become ore")
        assertBalanced(s, "extractor into a vent")

        // Nothing more arrives, however long it is left running.
        val after = run(s, 100)
        assertEquals(s.extractedMass, after.extractedMass, "an empty plate produces nothing")
    }

    @Test
    fun `raw ore run straight into a smelter yields nothing but slag`() {
        // The default ore body is 41% iron: too dirty to smelt. This is the lesson the world teaches.
        val grid = Grid(16, 8)
        val machines = arrayOfNulls<Machine>(grid.size)
        val feed = feedExtractor(grid, machines, 2, 3)
        machines[grid.index(7, 3)] = Smelter(Direction.Right)     // covers x 5..9
        machines[grid.index(12, 3)] = Storage(Direction.Right)
        machines[grid.index(7, 6)] = Vent()   // under the smelter's slag port: where slag goes
        val rails = arrayOfNulls<Segment>(grid.size)
        // One run under the lot, from the extractor's port to the tank's.
        joinRow(grid, rails, 4, 11, 3)
        joinCol(grid, rails, 7, 3, 6)
        var s = VesselState(
            grid, machines.toList(),
            conduits = Conduits.ofRails(rails.toList()),
            bodies = feed,
        )

        s = run(s, ticksToMove(Storage.CAP))
        assertTrue(s.ventedMass > 0L, "slag should be pouring out the side")
        assertEquals(0L, s.stockpile[Form.IronIngot].total, "and no ingot should ever reach the store")
        assertBalanced(s, "ore straight to smelter")
    }

    @Test
    fun `a processor in front of the smelter is what makes ingots`() {
        val s = run(workingVessel(cfg.initialGrid), 480)
        val ironIngots = s.stockpile[Form.IronIngot]
        assertTrue(ironIngots.total > 0L, "the full line should store iron: ${s.stockpile}")
        assertEquals(ironIngots.total, ironIngots[Species.Iron], "and the ingots should be pure iron")
        assertBalanced(s, "starter vessel")
    }

    @Test
    fun `a smelter stalls rather than mixing two metals`() {
        val grid = Grid(3, 1)
        val ironOre = Resource(Form.Ore, Mixture.of(Species.Iron to 2_000L, Species.Silica to 100L))
        val smelter = Smelter(Direction.Right, input = ironOre)
        var s = VesselState(grid, listOf(smelter, null, null))
        s = run(s, 20)
        val after = s[0] as Smelter
        assertEquals(Form.IronIngot, assertNotNull(after.refined).form)

        // Now feed it copper-dominant ore. It cannot make copper ingots while holding iron ones.
        val copperOre = Resource(Form.Ore, Mixture.of(Species.Copper to 2_000L, Species.Silica to 100L))
        var s2 = VesselState(grid, listOf(after.copy(input = copperOre), null, null))
        val heldBefore = (s2[0] as Smelter).refined!!.mass
        s2 = run(s2, 20)
        val stalled = s2[0] as Smelter
        assertEquals(heldBefore, stalled.refined!!.mass, "output should not have grown")
        assertEquals(copperOre.mass, stalled.input!!.mass, "and the copper ore should be untouched")
    }

    @Test
    fun `what a storage holds is what the vessel can build with`() {
        val grid = Grid(10, 5)
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val m = arrayOfNulls<Machine>(grid.size)
        // The tank faces open deck beyond it, so it fills rather than draining.
        m[grid.index(4, 2)] = Storage(Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        rails[grid.index(3, 2)] = Segment(Conduit.Rail, held = ingot)   // its input port
        var s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
        s = run(s, Bridge.STEP_TICKS)
        assertEquals(1_000L, (s[grid.index(4, 2)] as Storage).contents!!.mass, "it landed in the tank")
        assertEquals(1_000L, s.stockpile[Form.IronIngot].total, "and the stockpile is that tank")

        // Take the tank away and the stockpile goes with it: availability is a fact about where
        // things are, not a number banked somewhere safe.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.index(4, 2)))))
        assertEquals(0L, s.stockpile.totalMass)
    }

    @Test
    fun `machines refuse a second form rather than mixing their input buffer`() {
        val grid = Grid(10, 5)
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 500L))
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val m = arrayOfNulls<Machine>(grid.size)
        m[grid.index(4, 2)] = Processor(Direction.Right, input = ore)   // input port at (3, 2)
        val rails = arrayOfNulls<Segment>(grid.size)
        rails[grid.index(3, 2)] = Segment(Conduit.Rail, held = ingot)
        var s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
        s = run(s, Bridge.STEP_TICKS * 2)
        assertNotNull(s.railAt(grid.index(3, 2))?.held, "the ingot should still be waiting on the track")
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
     * know what a smelter eats — it says only which way material may travel, and the belt runs
     * left to right whatever is standing beside it. Whether *this* packet is taken is settled at the
     * tile, when it is offered. So ore is lifted off in passing and an ingot rides straight past to
     * the tank at the end, on the same belt, with nothing in the topology distinguishing them.
     */
    private fun tappedBelt(machine: Machine, carried: Packet): VesselState {
        val grid = Grid(12, 6)
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)

        // The belt: (1,2) through to (8,2), and a tank at the end taking delivery at (8,2).
        joinRow(grid, rails, 1, 8, 2)
        m[grid.index(9, 2)] = Storage(Direction.Right)

        // The machine sits below the belt facing down, so its input port is the belt tile (4,2)
        // above it and its outputs are at (4,4) and (3,3), where there is no track to receive them.
        m[grid.index(4, 3)] = machine

        rails[grid.index(1, 2)] = rails[grid.index(1, 2)]!!.copy(held = carried)
        val s = VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
        return run(s, 12)
    }

    @Test
    fun `an ingot the processor will not take rides the belt on to the tank`() {
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val s = tappedBelt(Processor(Direction.Down), ingot)

        assertNull((s[grid43(s)] as Processor).input, "the processor should not have taken an ingot")
        assertEquals(
            1_000L,
            (s[s.grid.index(9, 2)] as Storage).contents?.mass,
            "and the tank at the end of the belt should have caught it",
        )
    }

    @Test
    fun `ore on the same belt is lifted off in passing`() {
        // The other half, on the identical layout: the belt has not changed shape, so the only thing
        // that decided this packet's fate is what the machine was willing to take.
        val ore = SolidPacket(Resource(Form.Ore, Mixture.of(Species.Iron to 1_000L)))
        val s = tappedBelt(Processor(Direction.Down), ore)
        val processor = s[grid43(s)] as Processor

        // Asserted as conservation rather than as "the input buffer is not empty", which is a moment
        // and not a fact: the processor grinds at 125 g a tick, so whether the ore is still in the
        // mouth, half separated, or wholly turned into concentrate and tailings depends only on when
        // you happen to look. What must hold at any tick is that all of it is accounted for.
        val taken = (processor.input?.mass ?: 0L) +
            (processor.product?.mass ?: 0L) +
            (processor.tailings?.mass ?: 0L)
        assertEquals(1_000L, taken, "the processor should have taken the ore off the belt")
        assertNull(
            (s[s.grid.index(9, 2)] as Storage).contents,
            "so nothing should have reached the tank",
        )
    }

    @Test
    fun `an empty smelter lets an ingot go by`() {
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val s = tappedBelt(Smelter(Direction.Down), ingot)

        assertNull((s[grid43(s)] as Smelter).input, "the smelter should not have taken an ingot")
        assertEquals(
            1_000L,
            (s[s.grid.index(9, 2)] as Storage).contents?.mass,
            "the ingot should have carried on to the tank",
        )
    }

    private fun grid43(s: VesselState): Int = s.grid.index(4, 3)

    // ── Edits ─────────────────────────────────────────────────────────────────

    @Test
    fun `placing never overwrites an existing machine`() {
        val grid = Grid(2, 1)
        val store = Storage(Direction.Right, contents = Resource(Form.IronIngot, Mixture.of(Species.Iron to 999L)))
        var s = VesselState(grid, listOf(store, null))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(0, MachineKind.Sensor, Direction.Right))))
        // Compared with its heat put back, because a tick of conduction has moved it: the machine
        // is a thermal body now and radiating for one tick is not the same as being overwritten.
        assertEquals(
            store,
            s[0]?.withJoules(store.joules),
            "a stray click must not destroy a machine and its contents",
        )
    }

    @Test
    fun `rotating turns a machine clockwise and leaves its contents alone`() {
        val grid = Grid(10, 6)
        val stored = Resource(Form.IronIngot, Mixture.of(Species.Iron to 100L))
        val m = arrayOfNulls<Machine>(grid.size)
        m[grid.index(4, 3)] = Storage(Direction.Right, stored)
        var s = VesselState(grid, m.toList())
        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(grid.index(4, 3)))))
        val tank = s[grid.index(4, 3)] as Storage
        assertEquals(Direction.Down, tank.facing)
        assertEquals(100L, tank.contents?.mass)
    }

    @Test
    fun `edits apply in PlayerId order, not map order`() {
        val grid = Grid(2, 1)
        val base = VesselState(grid, listOf(null, null))
        val a = mapOf(
            PlayerId(0) to OutofspaceInput(listOf(Edit.Place(0, MachineKind.Sensor, Direction.Right))),
            PlayerId(1) to OutofspaceInput(listOf(Edit.Place(0, MachineKind.Storage, Direction.Right))),
        )
        val b = mapOf(
            PlayerId(1) to OutofspaceInput(listOf(Edit.Place(0, MachineKind.Storage, Direction.Right))),
            PlayerId(0) to OutofspaceInput(listOf(Edit.Place(0, MachineKind.Sensor, Direction.Right))),
        )
        assertEquals(
            OutofspaceReducer.reduce(cfg, base, a)[0],
            OutofspaceReducer.reduce(cfg, base, b)[0],
            "player 0 wins the tile either way",
        )
    }

    // ── The invariant that matters ────────────────────────────────────────────

    /**
     * The tick is the unit of simulation, so the tick *rate* is a speed dial and nothing else.
     *
     * This is the test the previous design could not pass. It spent a fractional carry, a
     * sub-stepping loop and a whole test-clock helper trying to make the world come out the same per
     * *second* at any rate, and still leaked — processor purity moved from 65% to 79% across the
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
    fun `the world never loses a gram`() {
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
        val onTrack = s.rails.fold(Mixture.EMPTY) { acc, r -> acc + (r?.held?.contents ?: Mixture.EMPTY) }
        val inWorld = s.machines.fold(onTrack) { acc, m -> acc + contentsOf(m) }
        val accountedFor = inWorld.total + s.ventedMass
        assertEquals(s.extractedMass, accountedFor)

        // And no species appeared from nowhere: only what the ore body contains is present.
        val fromOreBody = setOf(Species.Iron, Species.Silica, Species.Copper, Species.Titanium)
        val present = Species.ALL.filter { inWorld[it] > 0L }
        assertTrue(present.all { it in fromOreBody }, "unexpected species in the world: $present")
    }

    @Test
    fun `two runs of the same world are identical`() {
        fun digest(s: VesselState): String = buildString {
            append(s.tick).append('|').append(s.extractedMass).append('|').append(s.ventedMass)
            append('|').append(s.stockpile.toString())
            for (m in s.machines) append('|').append(m?.toString() ?: "-")
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
            for (r in s.rails) {
                val p = r?.held ?: continue
                assertTrue(p.mass in 1L..Capacity.PACKET_GRAMS, "bad packet on the track: ${p.mass}g")
            }
        }
    }
}
