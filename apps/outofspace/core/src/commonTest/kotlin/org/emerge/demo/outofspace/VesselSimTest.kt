package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.conservationOf
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.contentsOf
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tile world: belts, machines, jams and the whole-world conservation invariant.
 *
 * The headline assertion is [`nothing is created or destroyed`][`the world never loses a gram`]:
 * a miner is the only place matter legitimately enters and a vent the only place it leaves, so
 *
 *     mined == aboard + vented
 *
 * must hold on **every** tick. One assertion catches an entire category of logistics bug — a packet
 * duplicated on handoff, a jam that eats a slot, a buffer overwritten instead of merged.
 */
class VesselSimTest {

    private val cfg = OutofspaceConfig(grid = Grid(40, 28))

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
            s.minedGrams,
            s.inTransitGrams + s.ventedGrams,
            "$what: mined ${s.minedGrams} != aboard ${s.inTransitGrams} + vented ${s.ventedGrams}",
        )
    }

    // ── Track ─────────────────────────────────────────────────────────────────

    /**
     * A miner at (2,2) with a run of track from its output port to a **full** tank at [toX] + 1.
     *
     * The tank is what makes this a jam. Material is pulled toward a consumer, so a run that simply
     * stopped would not fill up — nothing would ever leave the miner at all. A jam is now a
     * destination that has stopped accepting, which is both a truer picture of a factory backing up
     * and a more useful thing to be able to see.
     */
    private fun minedLine(grid: Grid, toX: Int): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        machines[grid.index(2, 2)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        // Empty to begin with, and filled by the miner. Starting it full would be quicker but the
        // conservation ledger counts everything aboard as mined, and 20kg conjured into a tank is
        // exactly the sort of leak that ledger exists to catch.
        machines[grid.index(toX + 1, 2)] = Storage(Direction.Right)
        joinRow(grid, rails, 3, toX, 2)
        return VesselState(grid, machines.toList(), rails = rails.toList())
    }

    @Test
    fun `a jam fills the track from the far end backwards and stays visible`() {
        // A full tank at the end of the run. It should pack solid from the end nearest the tank.
        val grid = Grid(12, 5)
        var s = minedLine(grid, toX = 7)
        // Long enough to fill the 20kg tank at 1kg a second, and then back the line up behind it.
        s = run(s, 60 * 60)

        val carried = (3..7).map { s.railAt(grid.index(it, 2))?.held?.mass ?: 0L }
        assertTrue(carried.all { it > 0L }, "every tile should be carrying something: $carried")
        assertTrue(
            (s[grid.index(2, 2)] as Miner).buffer.mass >= Miner.BUFFER_CAP,
            "and the miner should have stopped digging",
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
        machines[grid.index(2, 5)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        machines[grid.index(8, 5)] = Storage(Direction.Right)          // in at (7, 5)
        machines[grid.index(5, 2)] = Vent()                            // in at its own tile
        joinRow(grid, rails, 3, 7, 5)
        joinCol(grid, rails, 5, 2, 5)   // the branch, up from the middle of the run to the vent

        // Long enough to fill the 20 kg tank several times over.
        val s = run(VesselState(grid, machines.toList(), rails = rails.toList()), 60 * 120)

        assertEquals(Storage.CAP, (s[grid.index(8, 5)] as Storage).contents?.mass, "the tank filled")
        assertTrue(s.ventedGrams > 0L, "and the rest went up the branch and overboard")
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
        machines[grid.index(2, 5)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        machines[grid.index(5, 2)] = Vent()                            // two tiles up from the fork
        machines[grid.index(9, 5)] = Storage(Direction.Right)          // four tiles along, in at (8, 5)
        joinRow(grid, rails, 3, 8, 5)
        joinCol(grid, rails, 5, 2, 5)

        val s = run(VesselState(grid, machines.toList(), rails = rails.toList()), 60 * 30)

        assertTrue(s.ventedGrams > 0L, "the vent took a share")
        val stored = (s[grid.index(9, 5)] as Storage).contents?.mass ?: 0L
        assertTrue(stored > 0L, "and so did the tank, which used to get nothing at all")
        // Not an exact split: the tank stops pulling when it fills, and everything then goes
        // overboard. Both being fed while both can take is the property that matters.
        assertTrue(stored >= s.ventedGrams / 2, "and the shares are comparable, not lopsided: $stored vs ${s.ventedGrams}")
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
        // Empty to begin with: everything aboard counts as mined, so seeding the tank would trip
        // the conservation ledger rather than test anything.
        machines[grid.index(7, 5)] = Storage(Direction.Right)
        joinRow(grid, rails, 8, 9, 5)
        joinCol(grid, rails, 9, 5, 7)
        joinRow(grid, rails, 4, 9, 7)
        joinCol(grid, rails, 4, 5, 7)
        joinRow(grid, rails, 4, 6, 5)

        // ...and a miner dropping onto that same loop, one tile in from the far corner.
        machines[grid.index(3, 7)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)

        val s = run(VesselState(grid, machines.toList(), rails = rails.toList()), 60 * 30)

        // The far arm is the stretch between the storage's output and where the miner joins. It is
        // exactly what went quiet, so material standing on it is the whole assertion.
        val farArm = listOf(grid.index(9, 5), grid.index(9, 6), grid.index(9, 7), grid.index(8, 7))
        assertTrue(
            farArm.any { s.rails[it]?.held != null },
            "the storage's own material never got out onto the loop",
        )
        assertTrue(
            ((s[grid.index(7, 5)] as Storage).contents?.mass ?: 0L) > 0L,
            "and the loop should have carried the miner's material round into the storage",
        )
        assertBalanced(s, "merged loop")
    }

    @Test
    fun `a jam clears from the front when the blockage is removed`() {
        val grid = Grid(12, 5)
        var s = minedLine(grid, toX = 7)
        s = run(s, 60 * 60)

        // Tear out the full tank and put a vent on the end of the run instead. The vent takes
        // anything, so the line drains from the front — the tile nearest the consumer moves first.
        s = run(s, 60 * 10, OutofspaceInput(listOf(
            Edit.Remove(grid.index(8, 2)),
            Edit.Place(grid.index(7, 2), MachineKind.Vent, Direction.Right),
        )))
        assertTrue(s.ventedGrams > 0L, "material should have gone overboard")
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
        machines[grid.index(2, 2)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        // Starts one tile past the miner's output port, so nothing ever reaches it.
        joinRow(grid, rails, 5, 8, 2)
        var s = VesselState(grid, machines.toList(), rails = rails.toList())
        s = run(s, 60 * 20)

        assertEquals(0L, (5..8).sumOf { s.railAt(grid.index(it, 2))?.held?.mass ?: 0L })
        assertBalanced(s, "orphan track")
    }

    @Test
    fun `a run with no consumer on the end of it never fills up`() {
        // The rule that replaced "material piles up at a dead end". Nothing pulls, so the miner's
        // output has nowhere to be and it backs up in the miner itself — where it is obvious — with
        // the track left clean. Under the old push model this line packed solid with stock the
        // player then had to dig back out of it.
        val grid = Grid(12, 5)
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        machines[grid.index(2, 2)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        joinRow(grid, rails, 3, 7, 2)
        var s = VesselState(grid, machines.toList(), rails = rails.toList())
        s = run(s, 60 * 30)

        // One packet does leave the miner: pushing out onto the tile under an output port is how
        // material enters a network at all, and that happens before anything asks where it is going.
        // It gets no further, which is the part that matters.
        assertEquals(
            0L,
            (4..7).sumOf { s.railAt(grid.index(it, 2))?.held?.mass ?: 0L },
            "nothing travelled: there is nothing to travel toward",
        )
        assertTrue(
            (s[grid.index(2, 2)] as Miner).buffer.mass >= Miner.BUFFER_CAP,
            "and the backlog is where you can see it, in the miner",
        )
        assertBalanced(s, "unconsumed line")
    }

    // ── Machines ──────────────────────────────────────────────────────────────

    @Test
    fun `a miner ships exactly one packet per second at 1kg per second`() {
        val grid = Grid(12, 5)
        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        machines[grid.index(2, 2)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        machines[grid.index(5, 2)] = Vent()
        joinRow(grid, rails, 3, 5, 2)
        var s = VesselState(grid, machines.toList(), rails = rails.toList())
        s = run(s, 60 * 10)
        // minedGrams counts at the shovel, so it is the whole 10kg regardless of where it sits now.
        assertEquals(10_000L, s.minedGrams, "10s of digging is 10kg")
        assertBalanced(s, "miner into a vent")
    }

    @Test
    fun `raw ore run straight into a smelter yields nothing but slag`() {
        // The default ore body is 41% iron: too dirty to smelt. This is the lesson the world teaches.
        val grid = Grid(16, 8)
        val machines = arrayOfNulls<Machine>(grid.size)
        machines[grid.index(2, 3)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        machines[grid.index(7, 3)] = Smelter(Direction.Right)     // covers x 5..9
        machines[grid.index(12, 3)] = Storage(Direction.Right)
        machines[grid.index(7, 6)] = Vent()   // under the smelter's slag port: where slag goes
        val rails = arrayOfNulls<Segment>(grid.size)
        // One run under the lot, from the miner's port to the tank's.
        joinRow(grid, rails, 3, 11, 3)
        joinCol(grid, rails, 7, 3, 6)
        var s = VesselState(grid, machines.toList(), rails = rails.toList())

        s = run(s, 60 * 60)
        assertTrue(s.ventedGrams > 0L, "slag should be pouring out the side")
        assertEquals(0L, s.stockpile[Form.IronIngot].total, "and no ingot should ever reach the store")
        assertBalanced(s, "ore straight to smelter")
    }

    @Test
    fun `a processor in front of the smelter is what makes ingots`() {
        val s = run(starterVessel(cfg.grid), 60 * 120)
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
        s = run(s, 60 * 5)
        val after = s[0] as Smelter
        assertEquals(Form.IronIngot, assertNotNull(after.refined).form)

        // Now feed it copper-dominant ore. It cannot make copper ingots while holding iron ones.
        val copperOre = Resource(Form.Ore, Mixture.of(Species.Copper to 2_000L, Species.Silica to 100L))
        var s2 = VesselState(grid, listOf(after.copy(input = copperOre), null, null))
        val heldBefore = (s2[0] as Smelter).refined!!.mass
        s2 = run(s2, 60 * 5)
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
        var s = VesselState(grid, m.toList(), rails = rails.toList())
        s = run(s, Bridge.STEP_TICKS)
        assertEquals(1_000L, (s[grid.index(4, 2)] as Storage).contents!!.mass, "it landed in the tank")
        assertEquals(1_000L, s.stockpile[Form.IronIngot].total, "and the stockpile is that tank")

        // Take the tank away and the stockpile goes with it: availability is a fact about where
        // things are, not a number banked somewhere safe.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.index(4, 2)))))
        assertEquals(0L, s.stockpile.totalGrams)
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
        var s = VesselState(grid, m.toList(), rails = rails.toList())
        s = run(s, Bridge.STEP_TICKS * 2)
        assertNotNull(s.railAt(grid.index(3, 2))?.held, "the ingot should still be waiting on the track")
    }

    // ── Edits ─────────────────────────────────────────────────────────────────

    @Test
    fun `placing never overwrites an existing machine`() {
        val grid = Grid(2, 1)
        val store = Storage(Direction.Right, contents = Resource(Form.IronIngot, Mixture.of(Species.Iron to 999L)))
        var s = VesselState(grid, listOf(store, null))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(0, MachineKind.Sensor, Direction.Right))))
        assertEquals(store, s[0], "a stray click must not destroy a machine and its contents")
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

    @Test
    fun `the world never loses a gram`() {
        var s = starterVessel(cfg.grid)
        repeat(60 * 90) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 97 == 0) assertBalanced(s, "tick ${s.tick}")
        }
        assertBalanced(s, "final")
        assertTrue(s.minedGrams > 50_000L, "the line should have moved real tonnage: ${s.minedGrams}")
    }

    @Test
    fun `species are conserved too, not merely total mass`() {
        var s = starterVessel(cfg.grid)
        repeat(60 * 60) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        // Everything the miners dug, versus everything that exists anywhere now. Vented material is
        // gone for good, so it is reconstructed from what the vents recorded... which they do not
        // itemise — so this checks the species balance of what remains against what was mined,
        // allowing only for the vented total.
        // Everything on the track counts too -- it is a separate list, and forgetting it here once
        // made a perfectly healthy world look 5kg short.
        val onTrack = s.rails.fold(Mixture.EMPTY) { acc, r -> acc + (r?.held?.contents ?: Mixture.EMPTY) }
        val inWorld = s.machines.fold(onTrack) { acc, m -> acc + contentsOf(m) }
        val accountedFor = inWorld.total + s.ventedGrams
        assertEquals(s.minedGrams, accountedFor)

        // And no species appeared from nowhere: only what the ore body contains is present.
        val fromOreBody = setOf(Species.Iron, Species.Silica, Species.Copper, Species.Titanium)
        val present = Species.ALL.filter { inWorld[it] > 0L }
        assertTrue(present.all { it in fromOreBody }, "unexpected species in the world: $present")
    }

    @Test
    fun `two runs of the same world are identical`() {
        fun digest(s: VesselState): String = buildString {
            append(s.tick).append('|').append(s.minedGrams).append('|').append(s.ventedGrams)
            append('|').append(s.stockpile.toString())
            for (m in s.machines) append('|').append(m?.toString() ?: "-")
        }
        assertEquals(
            digest(run(starterVessel(cfg.grid), 600)),
            digest(run(starterVessel(cfg.grid), 600)),
        )
    }

    @Test
    fun `packets on the track are always whole and never oversized`() {
        var s = starterVessel(cfg.grid)
        repeat(60 * 40) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            for (r in s.rails) {
                val p = r?.held ?: continue
                assertTrue(p.mass in 1L..Capacity.PACKET_GRAMS, "bad packet on the track: ${p.mass}g")
            }
        }
    }
}
