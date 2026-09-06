package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Ambient
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.SolarPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A panel with sky makes power; a panel without makes nothing.**
 *
 * Increment 1b of `PLAN_power_network.md`. ⭐ Nothing here is a rule of its own: exposure is
 * `StructureMap.openToSpace`, which already decides what a hot surface radiates at, and the light is
 * one number on [Ambient]. *The sun is anywhere outside the vessel* (Stu, 2026-09-06).
 */
class SolarPanelTest {

    private val grid = Grid(16, 8)
    private val panelAt = grid.tile(4, 4)

    /** A panel on a run of cable heading right, with [walls] optionally boxing it in. */
    private fun world(walls: Boolean = false, ambient: Ambient = Ambient.VACUUM): VesselState {
        val deck = DeckArray(grid)
        deck += SolarPanel(panelAt)
        // ⚠️ **All four**, and the first version of this fixture left one open so the cable could
        // run — which the panel then quite correctly collected through. A neighbour holding conduit
        // but no machine does not block passage, so space still reaches it. The cable stays; it is
        // the *hull* that has to be complete.
        if (walls) {
            for (dir in Direction.entries) {
                val next = grid.neighbour(panelAt, dir)
                if (next != TileIndex.NONE) deck += Hull(next)
            }
        }
        val power = arrayOfNulls<Segment>(grid.size)
        for (x in 4..5) power[grid.tile(x, 4).index] = Segment(Conduit.Power, material = Species.Copper)
        power[panelAt.index] = power[panelAt.index]!!.joinedTo(Direction.Right)
        power[grid.tile(5, 4).index] = power[grid.tile(5, 4).index]!!.joinedTo(Direction.Left)

        return VesselState(
            grid, deck,
            conduits = Conduits.empty(grid.size).with(Conduit.Power, power.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            ambient = ambient,
        )
    }

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    // ── It collects ──────────────────────────────────────────────────────────

    @Test
    fun `a panel facing space puts charge on the cable under it`() {
        val after = run(world(), 20)
        assertTrue(after.charge.total > 0L, "a panel in open space made nothing")
        assertTrue(after.charge[panelAt] > 0L, "the charge did not land on the panel's own tile")
    }

    /** ⭐ And it spreads: the cable beside it carries what the panel pushed. */
    @Test
    fun `the charge runs along the cable`() {
        val after = run(world(), 40)
        assertTrue(after.charge[grid.tile(5, 4)] > 0L, "the neighbouring cable stayed empty")
    }

    /**
     * ⭐ **Bury it and it makes nothing**, and nothing forbids that — it simply has no sky.
     *
     * This is the whole of "the sun is anywhere outside the vessel", asserted.
     */
    @Test
    fun `a panel walled in on every side makes nothing`() {
        val after = run(world(walls = true), 20)
        assertEquals(0L, after.charge.total, "a buried panel made power")
    }

    /** How far out the vessel is dims it, which is the only thing insolation says. */
    @Test
    fun `a vessel at a gas giant collects far less than one near the sun`() {
        val near = run(world(), 20).charge.total
        val far = run(world(ambient = Ambient.GAS_GIANT), 20).charge.total
        assertTrue(far in 1L until near, "gas giant collected $far against $near in open space")
    }

    // ── And the wire warms up, which is the point of increment 1a ─────────────

    /**
     * ⭐ **Emergent solar heating**: nobody wrote a rule that a panel warms the ship. Charge moves
     * down a resistance, and `I²R` is what that costs.
     */
    @Test
    fun `a run carrying a panel's output warms the ship`() {
        val idle = run(world(walls = true), 60).generatedEnergy
        val lit = run(world(), 60).generatedEnergy
        assertTrue(lit > idle, "a live run generated $lit against a dark one's $idle")
    }

    /** Charge only ever enters through a panel, so a world without one stays at zero for ever. */
    @Test
    fun `a cable with no panel on it never charges`() {
        val deck = DeckArray(grid)
        val power = arrayOfNulls<Segment>(grid.size)
        for (x in 4..6) power[grid.tile(x, 4).index] = Segment(Conduit.Power, material = Species.Copper)
        val bare = VesselState(
            grid, deck,
            conduits = Conduits.empty(grid.size).with(Conduit.Power, power.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        assertEquals(0L, run(bare, 30).charge.total, "charge appeared with nothing to make it")
    }
}
