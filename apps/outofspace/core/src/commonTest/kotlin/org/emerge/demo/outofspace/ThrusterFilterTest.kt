package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What a motor will let in.**
 *
 * ⛔ **This is the answer to a question `fire()` carried as a TODO for most of the machine's life** —
 * *"a thruster fed gravel should arguably refuse to fire rather than throw it away"*. It is not a
 * rule at the door. A motor states an appetite, the network routes by it, and a belt of ore simply
 * goes past — which is `Demand.kt`'s own principle: kind comes back as something a sink **asks
 * for**, not something it happens to reject when it arrives.
 *
 * See `PLAN_fluid_thrusters.md` §3.4.
 */
class ThrusterFilterTest {

    private val grid = Grid(20, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private val row = 5
    private val source = grid.tile(3, row)
    private val motorAt = grid.tile(10, row)
    private val beyond = grid.tile(16, row)

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * A belt from a full tank, past a motor, to an empty one.
     *
     * The tank beyond is what makes a refusal *observable*: without somewhere further to go, a lump
     * a motor will not take simply stops, and "it stalled" and "it was refused" look identical.
     */
    private fun line(cargo: Mixture, filter: SpeciesFilter? = null): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }
        var s = VesselState(
            grid, deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = true)
        s = edit(s, fixturePlace(source, Brush.Building(DeckMachineKind.Warehouse), Direction.Right))
        s = edit(s, fixturePlace(motorAt, Brush.Building(DeckMachineKind.Thruster), Direction.Up))
        s = edit(s, fixturePlace(beyond, Brush.Building(DeckMachineKind.Warehouse), Direction.Right))
        for (x in 3 until 16) s = edit(s, fixtureLay(grid.tile(x, row), grid.tile(x + 1, row), Conduit.Rail))
        if (filter != null) {
            val m = s.deck[motorAt] as Thruster
            s.deck[motorAt] = m.withFilter(filter)
        }
        return s.stocked(source, cargo)
    }

    private fun chamber(s: VesselState): Long = s.inStore(motorAt, BufferRole.Input)?.total ?: 0L
    private fun arrived(s: VesselState): Long = s.inStore(beyond, BufferRole.Inside)?.total ?: 0L

    private fun ore(): Mixture =
        Mixture.of(Species.Forsterite to 4L * Capacity.PACKET_MASS, energy = 0L).atAmbient()

    private fun water(): Mixture =
        Mixture.of(Species.Water to 4L * Capacity.PACKET_MASS, energy = 0L).atAmbient()

    // ── Unlocked: any fluid, and no solid ────────────────────────────────────

    @Test
    fun `an unlocked motor takes a fluid`() {
        val after = run(line(water()), 600)
        assertTrue(chamber(after) > 0L, "a motor refused the propellant it is for")
    }

    @Test
    fun `and a belt of ore goes straight past it`() {
        // ⛔ The gravel question, answered. Not "it refuses at the door" — it never asks for it, so
        // the network carries the ore to the tank beyond and the motor is not in its way.
        val after = run(line(ore()), 600)

        assertEquals(0L, chamber(after), "the motor swallowed rock it can do nothing with")
        assertTrue(arrived(after) > 0L, "the ore stopped at the motor instead of going past it")
    }

    // ── Locked: one species ──────────────────────────────────────────────────

    @Test
    fun `a motor locked to one species refuses another fluid`() {
        val after = run(line(water(), SpeciesFilter(Species.Hydrogen, pure = null)), 600)

        assertEquals(0L, chamber(after), "a motor locked to hydrogen took water")
        assertTrue(arrived(after) > 0L, "the water stopped at the motor instead of going past it")
    }

    @Test
    fun `and takes the one it names`() {
        // The other half, or the test above passes for a motor that takes nothing at all.
        val after = run(line(water(), SpeciesFilter(Species.Water, pure = null)), 600)
        assertTrue(chamber(after) > 0L, "a motor locked to water refused water")
    }

    @Test
    fun `a lock survives a save`() {
        val locked = line(water(), SpeciesFilter(Species.Hydrogen, pure = true))
        val reloaded = org.emerge.demo.outofspace.world.Save.read(
            org.emerge.demo.outofspace.world.Save.write(locked),
        )
        val m = reloaded.deck[motorAt] as Thruster
        assertEquals(Species.Hydrogen, m.filter?.species, "the lock did not come back")
        assertEquals(true, m.filter?.pure, "the purity standard did not come back")
    }
}
