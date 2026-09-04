package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A room's gas becomes cargo, and rides a belt like anything else.**
 *
 * The pump is the only continuum-to-packet converter for a fluid, and it is the piece that makes the
 * whole rail-borne fluid direction work: everything downstream of it — demand, filters, bridges,
 * twenty-tonne tanks — already exists and does not care that the packet is a gas.
 *
 * ⚠️ **The crossing is what is worth pinning**, not the drawing. Matter leaves the air identity and
 * joins the cargo one, and neither notices by itself. See `PLAN_fluid_thrusters.md` §3.1.
 */
class PumpTest {

    private val grid = Grid(20, 12)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private val row = 6
    private val pumpAt = grid.tile(5, row)
    private val tankAt = grid.tile(14, row)

    private fun hulled(): DeckArray {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }
        return deck
    }

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * A sealed box, a pump facing the room above it, and a run of track from it to a tank.
     *
     * ⚠️ **The room is a collection bay, not a cabin, and that is what makes the pump run.** Its
     * throughput is bounded by how fast gas reaches the one tile it draws from, and a room is a
     * diffusive medium — measured, a pump in ordinary ambient air manages about **1.5 g a tick**
     * against a dial of 250, because it strips its intake tile and then waits. At forty atmospheres
     * it hits the dial exactly. That is the shape the machine is meant to have (see [Pump]) and
     * `a pump in thin air is limited by what reaches it` pins the other end of it.
     */
    private fun plant(withTank: Boolean = true, atmospheres: Long = 40L): VesselState {
        var s = VesselState(
            grid, hulled(),
            gravity = VesselState.PLATING_ONE_G,
            buffers = BufferLayer.forDeck(grid, hulled()),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = true)
        s = edit(s, fixturePlace(pumpAt, Brush.Building(DeckMachineKind.Pump), Direction.Up))
        if (withTank) s = edit(s, fixturePlace(tankAt, Brush.Building(DeckMachineKind.Storage), Direction.Right))
        for (x in 5 until 14) s = edit(s, fixtureLay(grid.tile(x, row), grid.tile(x + 1, row), Conduit.Rail))
        return if (atmospheres <= 1L) s else pressurised(s, atmospheres)
    }

    /** The same world with [times] as much air in every room — an asteroid off-gassing into a hold. */
    private fun pressurised(s: VesselState, times: Long): VesselState {
        val air = s.air.copyMass()
        val energy = s.air.copyEnergy()
        for (t in grid.tiles) {
            if (s.structure.blocksAir(t)) continue
            for (f in Fluid.ALL) {
                val held = air[t, f]
                if (held > 0L) air[t, f] = held * times
            }
            energy[t] = energy[t] * times
        }
        return VesselState(
            grid, s.deck,
            conduits = s.conduits, buffers = s.buffers, rail = s.rail,
            air = org.emerge.demo.outofspace.world.Stuff.from(air, energy),
            gravity = VesselState.PLATING_ONE_G, creative = true,
        )
    }

    private fun banked(s: VesselState): Long = s.inStore(pumpAt, BufferRole.Product)?.total ?: 0L
    private fun tank(s: VesselState): Long = s.inStore(tankAt, BufferRole.Inside)?.total ?: 0L

    private fun onBelts(s: VesselState): Long {
        var sum = 0L
        for (tile in grid.tiles) for (sp in Species.ALL) sum += s.rail.stuff[tile, sp]
        return sum
    }

    private fun assertBalanced(s: VesselState, what: String) {
        assertEquals(0L, s.airBalance, "$what: the air ledger is out")
        assertEquals(0L, s.massBalance, "$what: the cargo ledger is out")
    }

    // ── The draw ─────────────────────────────────────────────────────────────

    @Test
    fun `a pump turns the room it faces into cargo`() {
        // ⚠️ Measured as a delta, not against zero: the fixture builds through the reducer, so the
        // pump has already been running for the handful of ticks the edits took.
        val start = plant()
        val airBefore = start.atmosphereMass
        val bankedBefore = banked(start)

        val after = run(start, 40)

        assertTrue(banked(after) > bankedBefore || onBelts(after) > 0L, "the pump drew nothing at all")
        assertTrue(after.atmosphereMass < airBefore, "cargo appeared without the room losing any air")
        assertBalanced(after, "a pump drawing")
    }

    @Test
    fun `what the room lost is what the world gained, to the gram`() {
        val start = plant()
        val airBefore = start.atmosphereMass
        val cargoBefore = start.inTransitMass

        val after = run(start, 200)

        val lost = airBefore - after.atmosphereMass
        assertTrue(lost > 0L, "nothing crossed, so nothing is being measured")
        assertEquals(lost, after.inTransitMass - cargoBefore, "the cargo did not gain what the air lost")
        assertBalanced(after, "a crossing")
    }

    @Test
    fun `the gas arrives carrying its heat`() {
        // ⚠️ Silent when dropped: a store full of gas at zero joules reads AMBIENT_KELVIN through
        // `kelvinOf`, so cold cargo looks perfectly healthy while the room it came from is short.
        val after = run(plant(), 60)
        val held = after.inStore(pumpAt, BufferRole.Product) ?: after.inStore(tankAt, BufferRole.Inside)

        assertTrue(held != null && held.total > 0L, "fixture: something should have been drawn")
        assertTrue(held!!.energy > 0L, "the gas arrived with no heat in it")
    }

    // ── And onto the belt ────────────────────────────────────────────────────

    @Test
    fun `it ships whole packets down a belt into a tank`() {
        // The whole point of the direction: a fluid is cargo, and cargo already has a logistics
        // system. Nothing between the pump and the tank knows or cares that this is a gas.
        val after = run(plant(), 4000)

        assertTrue(tank(after) > 0L, "nothing reached the tank")
        assertTrue(
            tank(after) >= Capacity.PACKET_MASS,
            "less than one belt-load arrived, so packets are not being shipped whole: ${tank(after)}",
        )
        assertBalanced(after, "a tank filling from a pump")
    }

    @Test
    fun `a tank of gas keeps it, rather than breathing it back out`() {
        // ⛔ This is what step 4 bought and it is worth pinning here too: a hopper never off-gasses,
        // so a tonne of gas is a tonne of gas. Without that the whole direction is unreachable.
        val filled = run(plant(), 4000)
        val held = tank(filled)
        assertTrue(held > 0L, "fixture: the tank should have something in it")

        val later = run(filled, 2000)
        assertTrue(
            tank(later) >= held,
            "the tank leaked its gas back into the room: $held then ${tank(later)}",
        )
        assertBalanced(later, "a tank sitting on its gas")
    }

    @Test
    fun `a pump in thin air is limited by what reaches it, not by its own rate`() {
        // ⛔ **The property that makes an intake worth building well.** A pump draws from one tile,
        // and a room is diffusive — so in ordinary cabin air it strips that tile and then waits,
        // managing a fraction of a per cent of its rate. Measured at about 1.5 g a tick against 250.
        // A bay thick with an asteroid's off-gas is what a pump is *for*, and the comparison is what
        // says so.
        val thin = run(plant(atmospheres = 1L), 2000)
        val thick = run(plant(), 2000)

        assertTrue(
            banked(thick) + tank(thick) > (banked(thin) + tank(thin)) * 4L,
            "a dense bay did not out-pump a thin cabin: ${banked(thick) + tank(thick)} against " +
                "${banked(thin) + tank(thin)}",
        )
        assertBalanced(thin, "a pump in thin air")
    }

    @Test
    fun `a pump with nowhere to send it fills up and stops`() {
        val after = run(plant(withTank = false), 4000)

        assertTrue(banked(after) > 0L, "the pump banked nothing")
        assertTrue(
            banked(after) <= Pump.BUFFER_CAP,
            "the pump banked ${banked(after)} against a cap of ${Pump.BUFFER_CAP}",
        )
        assertBalanced(after, "a pump with no tank")
    }

    // ── Through a save ───────────────────────────────────────────────────────

    @Test
    fun `a gas packet survives a save round trip`() {
        // ⚠️ The one place the old code genuinely could not carry a fluid: the writer emits `F:` for
        // a `FluidPacket` and the reader dropped it on the floor. Nothing ever made one, so nothing
        // ever noticed — and a lost packet is a leak the ledger reports for ever.
        // ⚠️ **Caught mid-journey, not at a fixed tick.** A packet crosses nine tiles in about
        // seventy ticks, so "run 600 and look" lands on an empty belt as often as not — and an empty
        // belt makes this test pass while proving nothing.
        var filled = plant()
        repeat(4000) {
            if (onBelts(filled) > 0L) return@repeat
            filled = run(filled, 1)
        }
        assertTrue(onBelts(filled) > 0L, "fixture: nothing ever rode the belt")

        val reloaded = Save.read(Save.write(filled))

        assertEquals(onBelts(filled), onBelts(reloaded), "what was on the belt did not come back")
        assertEquals(filled.atmosphereMass, reloaded.atmosphereMass, "the air did not come back")
        assertBalanced(reloaded, "a reloaded world")
    }
}
