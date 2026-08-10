package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Pump
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.fluid.PIPE_VOLUME
import org.emerge.demo.outofspace.world.fluid.VolumeField
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pump: gas moved **uphill**, out of a room and into a pipe.
 *
 * That one word is the whole difference from a valve, and most of what is below is about pinning it.
 * A valve stops when the two sides agree; a pump keeps going past that until it stalls, and if it did
 * not, there would be no reason for it to exist.
 */
class PumpTest {

    private val grid = Grid(20, 12)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun hulled(): List<Machine?> {
        val m = arrayOfNulls<Machine>(grid.size)
        for (x in 0 until grid.width) {
            m[grid.index(x, 0)] = Hull()
            m[grid.index(x, grid.height - 1)] = Hull()
        }
        for (y in 0 until grid.height) {
            m[grid.index(0, y)] = Hull()
            m[grid.index(grid.width - 1, y)] = Hull()
        }
        return m.toList()
    }

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun pipeRun(state: VesselState, y: Int, fromX: Int, toX: Int): VesselState {
        var s = state
        for (x in fromX until toX) s = edit(s, Edit.Lay(grid.index(x, y), grid.index(x + 1, y), Conduit.Pipe))
        return s
    }

    private fun pipeMass(s: VesselState, tile: Int): Long {
        var sum = 0L
        for (sp in Species.ALL) sum += s.pipeAir.gramsOf(tile, sp)
        return sum
    }

    private fun roomMass(s: VesselState, tile: Int): Long {
        var sum = 0L
        for (sp in Species.ALL) sum += s.air.gramsOf(tile, sp)
        return sum
    }

    private fun assertBalanced(s: VesselState, what: String) {
        assertEquals(
            s.baselineAirGrams,
            s.atmosphereGrams + s.airVentedGrams,
            "$what: rooms plus pipes plus vented no longer accounts for the air the world started with",
        )
        assertEquals(
            s.baselineAirJoules,
            s.atmosphereJoules + s.airVentedJoules - s.solidToAirJoules,
            "$what: the heat the atmosphere is carrying no longer adds up",
        )
    }

    /** A pipe run with a pump on it at [pumpX], drawing from the room above (facing Up). */
    private fun pumped(pumpX: Int = 6, y: Int = 6, facing: Direction = Direction.Up): VesselState {
        var s = VesselState(grid, hulled(), gravity = VesselState.PLATING_ONE_G)
        s = pipeRun(s, y, 4, 15)
        return edit(s, Edit.Place(grid.index(pumpX, y), MachineKind.Pump, facing))
    }

    @Test
    fun `a pump fills the pipe it stands on from the room it faces`() {
        val start = pumped()
        val after = run(start, 200)

        assertTrue(after.pipeAir.totalGrams > 0L, "the pump moved nothing")
        assertTrue(pipeMass(after, grid.index(13, 6)) > 0L, "gas was pumped in but never ran along the pipe")
        assertBalanced(after, "a pump filling a pipe")
    }

    /**
     * The assertion that separates a pump from a fast valve.
     *
     * A valve settles at equal **pressure**, which for an eighth-of-a-tile cell means about an eighth
     * of the neighbouring room's mass. A pump has to beat that, and by roughly [Pump.STALL_RATIO]. If
     * this ever reads at the valve's equilibrium, whatever is in the tick is a hole, however it is
     * named.
     */
    @Test
    fun `a pump pushes past the pressure a valve would stop at`() {
        val after = run(pumped(), 600)

        val intake = grid.index(6, 5)
        val pipe = grid.index(6, 6)
        val room = roomMass(after, intake)
        val held = pipeMass(after, pipe)
        assertTrue(room > 0L, "the pump emptied the room entirely, which is not what this measures")

        val valveWould = room * PIPE_VOLUME / VolumeField.FULL
        assertTrue(
            held > valveWould * 2,
            "the pipe settled at ${held}g where a plain valve would have left about ${valveWould}g — " +
                "gas is not being pushed uphill, so this is a hole rather than a pump",
        )
        assertBalanced(after, "a pump against its stall")
    }

    /**
     * And it does not push for ever: [Pump.STALL_RATIO] is a real ceiling, not a slow approach to one.
     *
     * Checked by running a long time and then a lot longer, because "it stalls" and "it is still
     * climbing slowly" look identical at any single moment. Without a ceiling this is a machine that
     * compresses without limit.
     */
    @Test
    fun `a pump stalls rather than compressing without limit`() {
        // A single length of pipe with nowhere to run to, which is the only arrangement that can
        // show a stall at all. On a long run the pump never stalls and should not: what it pushes in
        // flows away down the network, the cell under it stays well below its ceiling, and the pump
        // keeps working until the whole run is full. That is the machine behaving correctly, and it
        // is also indistinguishable from a stall that does nothing.
        var s = VesselState(grid, hulled(), gravity = VesselState.PLATING_ONE_G)
        s = edit(s, Edit.Place(grid.index(6, 6), MachineKind.Pipe, Direction.Right))
        s = edit(s, Edit.Place(grid.index(6, 6), MachineKind.Pump, Direction.Up))
        val early = run(s, 400)
        val late = run(early, 1_200)

        val pipe = grid.index(6, 6)
        val a = pipeMass(early, pipe)
        val b = pipeMass(late, pipe)
        assertTrue(a > 0L, "nothing was pumped at all")
        assertTrue(
            b <= a * 12 / 10,
            "the pipe held ${a}g and then ${b}g twelve hundred ticks later — the pump is still " +
                "compressing, so the stall does nothing",
        )
        assertBalanced(late, "a stalled pump")
    }

    @Test
    fun `a pump with no pipe beneath it has nowhere to push`() {
        var s = VesselState(grid, hulled(), gravity = VesselState.PLATING_ONE_G)
        s = edit(s, Edit.Place(grid.index(6, 6), MachineKind.Pump, Direction.Up))
        val roomBefore = s.air.totalGrams

        val after = run(s, 200)

        assertEquals(0L, after.pipeAir.totalGrams, "gas was pumped into plumbing that is not there")
        assertEquals(roomBefore, after.air.totalGrams, "the room lost gas to nowhere")
        assertBalanced(after, "a pump with no pipe")
    }

    /**
     * Facing means intake, so turning a pump changes which room it empties.
     *
     * Built on two **sealed** chambers, which took a wrong turn to arrive at. The obvious version
     * puts the pump in one big room and watches the tile it faces, and it measures nothing: a room
     * is connected to itself, so the air the pump draws off one tile is replaced from the
     * neighbouring tiles within a tick or two and the difference never accumulates. It has to be a
     * chamber that cannot be refilled before draining it means anything.
     *
     * Comparing the two orientations rather than checking one, because a pump that ignored facing
     * entirely and always drew from some fixed neighbour would satisfy any single-orientation test
     * that only asked whether gas moved.
     */
    @Test
    fun `which room a pump empties is the one it faces`() {
        // A bulkhead across the middle with the pump set into it, so the only way out of either
        // chamber is through the machine.
        fun split(facing: Direction): VesselState {
            var s = VesselState(grid, hulled(), gravity = VesselState.PLATING_ONE_G)
            for (x in 1 until grid.width - 1) {
                if (x == 6) continue
                s = edit(s, Edit.Place(grid.index(x, 6), MachineKind.Hull, Direction.Right))
            }
            s = edit(s, Edit.Place(grid.index(6, 6), MachineKind.Pipe, Direction.Right))
            return run(edit(s, Edit.Place(grid.index(6, 6), MachineKind.Pump, facing)), 400)
        }

        fun chamber(s: VesselState, rows: IntRange): Long {
            var sum = 0L
            for (y in rows) for (x in 1 until grid.width - 1) sum += roomMass(s, grid.index(x, y))
            return sum
        }

        val up = split(Direction.Up)
        val down = split(Direction.Down)

        // Each chamber against ITSELF under the two orientations, never upper against lower: the
        // vessel has gravity and the air is stratified, so the lower chamber holds more whatever any
        // pump is doing, and comparing the two would measure that and call it facing.
        assertTrue(
            chamber(up, 1..5) < chamber(down, 1..5),
            "the upper chamber held as much with the pump facing away from it as facing into it, " +
                "so facing is being ignored",
        )
        assertTrue(
            chamber(down, 7..10) < chamber(up, 7..10),
            "the lower chamber held as much with the pump facing away from it as facing into it, " +
                "so facing is being ignored",
        )
    }

    /**
     * A pump's intake stops the gas it draws, and the ship feels it.
     *
     * This is the term that makes a pump usable as a thruster later, and the one that would be
     * silently absent if the intake simply deleted the momentum along with the gas. Measured against
     * an identical vessel with no pump in it, so the enormous background of ordinary hull forces
     * cancels rather than having to be predicted.
     */
    @Test
    fun `the momentum a pump takes out of the room is booked to the vessel`() {
        val idle = run(VesselState(grid, hulled(), gravity = VesselState.PLATING_ONE_G), 300)
        // Drawing sideways, so the intake removes momentum along x, where a still room has least of
        // its own and the pump's contribution is not buried under the settling of the air column.
        val working = run(pumped(facing = Direction.Left), 300)

        assertTrue(
            working.vesselImpulseX != idle.vesselImpulseX,
            "a running pump left the vessel's x impulse exactly as an empty hull did — the momentum " +
                "of the gas it drew in went nowhere, which is a leak out of the ledger",
        )
        assertBalanced(working, "a pump pushing on the ship")
    }

    @Test
    fun `a pump and its facing survive a save`() {
        val after = run(pumped(facing = Direction.Down), 50)
        val back = Save.read(Save.write(after))

        val pump = back.machines[grid.index(6, 6)] as? Pump
        assertTrue(pump != null, "the pump did not come back")
        assertEquals(Direction.Down, pump.facing, "it came back facing somewhere else")
        assertEquals(after.pipeAir, back.pipeAir, "what it had pumped came back as something else")
    }
}
