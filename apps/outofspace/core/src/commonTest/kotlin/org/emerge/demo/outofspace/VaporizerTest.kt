package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.machine.Vaporizer
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vaporizer turns a solid into a gas, which means it is the one machine in the game that moves
 * mass **between two ledgers**, and both of them have to be told.
 *
 * It went the whole of its life telling neither: ore left [VesselState.inTransitMass] with nothing
 * booked overboard, and gas arrived in the atmosphere with nothing booked as injected, so a world
 * with a running vaporizer in it had a `massBalance` drifting one way and an `airBalance` drifting
 * the other. Neither instrument had a test pointed at this machine, and an instrument nobody points
 * at anything is one you eventually learn to ignore — which is the whole argument for the stores in
 * the first place. Found while building [org.emerge.demo.outofspace.world.machine.Thruster], which does the
 * same conversion and had to get it right.
 */
class VaporizerTest {

    /** Solid in, gas out: what leaves the hopper turns up in the room, unit for unit. */
    @Test
    fun `ore becomes atmosphere`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithVaporizer(cfg.initialGrid))
        val before = controller.state

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        val spent = before.inTransitMass - s.inTransitMass
        assertTrue(spent > 0L, "it vaporised nothing, so this proved nothing")
        assertEquals(
            spent,
            s.atmosphereMass - before.atmosphereMass,
            "what left the hopper is not what arrived in the room",
        )
    }

    /**
     * And both ledgers stay closed while it does it — the assertion this machine never had.
     *
     * Checked every tick rather than at the end, because the tick a ledger first parts company on
     * is most of the diagnosis. `massBalance` opens at whatever the fixture handed over rather than
     * at zero, since nothing here mined the ore; what must be true is that it does not *move*.
     */
    @Test
    fun `vaporising keeps both ledgers closed`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithVaporizer(cfg.initialGrid))
        val opening = controller.state.let { it.inTransitMass + it.ventedMass - it.extractedMass }

        repeat(TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(
                opening,
                s.inTransitMass + s.ventedMass - s.extractedMass,
                "tick ${s.tick}: the solid ledger moved while the vaporizer ran",
            )
            assertEquals(0L, s.airBalance, "tick ${s.tick}: gas arrived from nowhere")
        }
    }

    /** A hull box with a fuelled vaporizer amidships and an ordinary atmosphere around it. */
    private fun hullWithVaporizer(grid: Grid): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        machines[grid.tile(BAY_X, BAY_Y).index] = Vaporizer(
            facing = Direction.Right,
            // A volatile, so what comes out is a gas anybody would recognise as one.
            input = Resource(Form.Ore, Mixture.of(Species.Water to 4L * Capacity.PACKET_MASS, energy = 0)),
        )
        return VesselState(grid = grid, machines = machines.toList(), deck = deck)
    }

    private companion object {
        init { RockSpawner.enabled = false }

        /** Ten ticks is plenty: the leak, if there is one, is there on the first. */
        const val TICKS = 10

        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_TOP = 6
        const val HULL_BOTTOM = 26
        const val BAY_X = 17
        const val BAY_Y = 16
    }
}
