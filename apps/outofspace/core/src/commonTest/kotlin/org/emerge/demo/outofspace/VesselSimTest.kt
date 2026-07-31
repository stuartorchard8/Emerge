package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.conservationOf
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Belt
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Node
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
 *     mined == in-transit + banked + vented
 *
 * must hold on **every** tick. One assertion catches an entire category of logistics bug — a packet
 * duplicated on handoff, a jam that eats a slot, a buffer overwritten instead of merged.
 */
class VesselSimTest {

    private val cfg = OutofspaceConfig(grid = Grid(24, 12))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun assertBalanced(s: VesselState, what: String) {
        val banked = s.stockpile.totalGrams
        assertEquals(
            s.minedGrams,
            s.inTransitGrams + banked + s.ventedGrams,
            "$what: mined ${s.minedGrams} != transit ${s.inTransitGrams} + banked $banked + vented ${s.ventedGrams}",
        )
    }

    // ── Belts ─────────────────────────────────────────────────────────────────

    @Test
    fun `a packet advances along a belt one slot at a time`() {
        val grid = Grid(4, 1)
        val packet = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val belt = Belt(Direction.Right, listOf(null, null, null, packet))
        var s = VesselState(grid, listOf(belt, null, null, null))

        assertEquals(3, (s[0] as Belt).slots.indexOfFirst { it != null }, "starts in the tail slot")
        s = run(s, Belt.STEP_TICKS)
        assertEquals(2, (s[0] as Belt).slots.indexOfFirst { it != null })
        s = run(s, Belt.STEP_TICKS * 3)
        assertEquals(0, (s[0] as Belt).slots.indexOfFirst { it != null }, "reaches the head and waits there")
    }

    @Test
    fun `a belt hands its head packet to the next belt`() {
        val grid = Grid(3, 1)
        val packet = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        var s = VesselState(
            grid,
            listOf(Belt(Direction.Right, listOf(packet, null, null, null)), Belt(Direction.Right), null),
        )
        s = run(s, Belt.STEP_TICKS)
        assertNull((s[0] as Belt).slots[0], "the head packet left")
        // It arrives in the tail and is shifted along by the same advance, so assert on presence
        // rather than on a slot index.
        assertEquals(1_000L, (s[1] as Belt).slots.sumOf { it?.mass ?: 0L }, "and arrived in the next belt")
    }

    @Test
    fun `a belt facing nothing holds onto its packets rather than dropping them`() {
        val grid = Grid(2, 1)
        val packet = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        var s = VesselState(grid, listOf(Belt(Direction.Right, listOf(packet, null, null, null)), null))
        s = run(s, Belt.STEP_TICKS * 10)
        assertEquals(1_000L, s.inTransitGrams, "the packet is still on the belt, not on the floor")
    }

    @Test
    fun `a jam fills the belt from the head backwards and stays visible`() {
        // Four belts feeding a dead end: they should pack solid, head first.
        val grid = Grid(6, 1)
        val machines = arrayOfNulls<Machine>(6)
        machines[0] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        for (x in 1..4) machines[x] = Belt(Direction.Right)
        // index 5 left empty: nothing accepts, so the line backs up.
        var s = VesselState(grid, machines.toList())

        s = run(s, 60 * 30)
        val belts = (1..4).map { s[it] as Belt }
        assertTrue(belts.all { it.isFull }, "every belt should be packed: ${belts.map { it.occupancy }}")
        assertTrue((s[0] as Miner).buffer.mass >= Miner.BUFFER_CAP, "and the miner should have stopped digging")
        assertBalanced(s, "jammed line")
    }

    @Test
    fun `a jam clears from the front when the blockage is removed`() {
        val grid = Grid(6, 1)
        val machines = arrayOfNulls<Machine>(6)
        machines[0] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        for (x in 1..4) machines[x] = Belt(Direction.Right)
        var s = VesselState(grid, machines.toList())
        s = run(s, 60 * 30)

        // Drop a vent on the end; the line should drain.
        s = run(s, 60 * 10, OutofspaceInput(listOf(Edit.Place(5, MachineKind.Vent, Direction.Right))))
        assertTrue((s[4] as Belt).occupancy < 4, "the belt nearest the vent should have drained")
        assertTrue(s.ventedGrams > 0L, "and material should have gone overboard")
        assertBalanced(s, "drained line")
    }

    // ── Machines ──────────────────────────────────────────────────────────────

    @Test
    fun `a miner ships exactly one packet per second at 1kg per second`() {
        val grid = Grid(3, 1)
        var s = VesselState(
            grid,
            listOf(Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY), Belt(Direction.Right), Vent()),
        )
        s = run(s, 60 * 10)
        // minedGrams counts at the shovel, so it is the whole 10kg regardless of where it sits now.
        assertEquals(10_000L, s.minedGrams, "10s of digging is 10kg")
        assertBalanced(s, "miner into a vent")
    }

    @Test
    fun `raw ore run straight into a smelter yields nothing but slag`() {
        // The default ore body is 41% iron: too dirty to smelt. This is the lesson the world teaches.
        val grid = Grid(5, 2)
        val machines = arrayOfNulls<Machine>(10)
        machines[0] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        machines[1] = Belt(Direction.Right)
        machines[2] = Smelter(Direction.Right)
        machines[3] = Node()
        machines[2 + 5] = Vent()   // below the smelter: where slag goes
        var s = VesselState(Grid(5, 2), machines.toList())

        s = run(s, 60 * 60)
        assertTrue(s.ventedGrams > 0L, "slag should be pouring out the side")
        assertEquals(0L, s.stockpile.totalGrams, "and nothing should reach the bank")
        assertBalanced(s, "ore straight to smelter")
    }

    @Test
    fun `a processor in front of the smelter is what makes ingots`() {
        val s = run(starterVessel(cfg.grid), 60 * 120)
        val ironIngots = s.stockpile[Form.IronIngot]
        assertTrue(ironIngots.total > 0L, "the full line should bank iron: ${s.stockpile}")
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
    fun `the node banks what reaches it and the vent throws it away`() {
        val grid = Grid(3, 1)
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        var s = VesselState(grid, listOf(Belt(Direction.Right, listOf(ingot, null, null, null)), Node(), null))
        s = run(s, Belt.STEP_TICKS)
        assertEquals(1_000L, s.stockpile[Form.IronIngot].total)
        assertEquals(1_000L, (s[1] as Node).absorbedGrams)
    }

    @Test
    fun `machines refuse a second form rather than mixing their input buffer`() {
        val grid = Grid(2, 1)
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 500L))
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        var s = VesselState(
            grid,
            listOf(Belt(Direction.Right, listOf(ingot, null, null, null)), Processor(Direction.Right, input = ore)),
        )
        s = run(s, Belt.STEP_TICKS)
        assertNotNull((s[0] as Belt).slots[0], "the ingot should still be waiting on the belt")
    }

    // ── Edits ─────────────────────────────────────────────────────────────────

    @Test
    fun `placing never overwrites an existing machine`() {
        val grid = Grid(2, 1)
        val node = Node(absorbedGrams = 999L)
        var s = VesselState(grid, listOf(node, null))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(0, MachineKind.Belt, Direction.Right))))
        assertEquals(node, s[0], "a stray click must not destroy a machine and its contents")
    }

    @Test
    fun `rotating turns a belt clockwise and leaves its cargo alone`() {
        val grid = Grid(2, 1)
        val packet = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 100L)))
        var s = VesselState(grid, listOf(Belt(Direction.Right, listOf(null, null, null, packet)), null))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(0))))
        val belt = s[0] as Belt
        assertEquals(Direction.Down, belt.facing)
        assertEquals(100L, belt.slots.sumOf { it?.mass ?: 0L })
    }

    @Test
    fun `edits apply in PlayerId order, not map order`() {
        val grid = Grid(2, 1)
        val base = VesselState(grid, listOf(null, null))
        val a = mapOf(
            PlayerId(0) to OutofspaceInput(listOf(Edit.Place(0, MachineKind.Belt, Direction.Right))),
            PlayerId(1) to OutofspaceInput(listOf(Edit.Place(0, MachineKind.Node, Direction.Right))),
        )
        val b = mapOf(
            PlayerId(1) to OutofspaceInput(listOf(Edit.Place(0, MachineKind.Node, Direction.Right))),
            PlayerId(0) to OutofspaceInput(listOf(Edit.Place(0, MachineKind.Belt, Direction.Right))),
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
        val inWorld = s.machines.fold(Mixture.EMPTY) { acc, m -> acc + contentsOf(m) }
        val banked = s.stockpile.entries().fold(Mixture.EMPTY) { acc, (_, m) -> acc + m }
        val accountedFor = inWorld.total + banked.total + s.ventedGrams
        assertEquals(s.minedGrams, accountedFor)

        // And no species appeared from nowhere: only what the ore body contains is present.
        val fromOreBody = setOf(Species.Iron, Species.Silica, Species.Copper, Species.Titanium)
        val present = Species.ALL.filter { (inWorld + banked)[it] > 0L }
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
    fun `packets on belts are always whole and never oversized`() {
        var s = starterVessel(cfg.grid)
        repeat(60 * 40) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            for (m in s.machines) {
                if (m is Belt) for (p in m.slots) if (p != null) {
                    assertTrue(p.mass in 1L..Capacity.PACKET_GRAMS, "bad packet on a belt: ${p.mass}g")
                }
            }
        }
    }
}
