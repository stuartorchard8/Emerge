package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Airlock
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.sim.core.PlayerId
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The airlock: a hull tile whose solidity is a signal.
 *
 * The point of it is thrust. Venting a room used to mean deleting a hull tile and building it back,
 * which is a fine way to prove the fluid solver pushes the ship but a hopeless way to *fly* one —
 * you cannot steer by editing. An airlock puts that same hole under wiring, so the thing that opens
 * it can be a signal, and a signal can come from somewhere other than the player's hands.
 *
 * **Nothing here pins a number.** Every case asserts a relationship — sealed holds, open leaks, wider
 * leaks faster, the exhaust pushes the other way — because the throughput of a given door at a given
 * pressure is the fluid solver's business and will move whenever it is tuned. A test that pinned it
 * would fail on every unrelated improvement and teach us nothing when it did.
 */
class AirlockTest {

    private fun cfgFor(grid: Grid) = OutofspaceConfig(initialGrid = grid)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = cfgFor(state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap<PlayerId, OutofspaceInput>()) }
        return s
    }

    /** Wired to the constant at [permille], which is the simplest possible "somebody is holding the button". */
    private fun held(permille: Int) =
        Wiring(mapOf(Action.Run to listOf(Trigger(SignalSource.Always, permille))))

    /**
     * A sealed hull box with one tile of its **right** wall replaced by [door].
     *
     * Freefall, not plating: the thrust case reads the vessel's momentum, and under gravity the air
     * would be pushing on the floor the whole time and burying the signal in it.
     *
     * The door is on the right wall specifically so that gas leaving it travels +x, and the ship must
     * therefore go −x. An axis the room is not symmetric about would let a sign error pass.
     */
    private fun roomWithDoor(door: Machine?, w: Int = 8, h: Int = 8): VesselState {
        val grid = Grid(w + 2, h + 2)
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1..w) {
            machines[grid.index(x, 1)] = Hull()
            machines[grid.index(x, h)] = Hull()
        }
        for (y in 1..h) {
            machines[grid.index(1, y)] = Hull()
            machines[grid.index(w, y)] = Hull()
        }
        if (door != null) machines[grid.index(w, h / 2)] = door
        return VesselState(grid, machines.toList())
    }

    /** The tile just inside the door — the one whose containment the flood fill has to get right. */
    private fun insideTheDoor(s: VesselState): Int = s.grid.index(s.grid.width - 3, (s.grid.height - 2) / 2)

    // ── Sealed ────────────────────────────────────────────────────────────────

    @Test
    fun `an unsignalled airlock is a wall`() {
        val start = roomWithDoor(Airlock())
        val after = run(start, 60)

        assertEquals(0L, after.airVentedGrams, "a shut door vents nothing")
        assertEquals(start.atmosphereGrams, after.atmosphereGrams, "and the room keeps all of its air")
    }

    /**
     * The stronger version of the case above: a shut airlock is not merely *nearly* a wall, it is
     * indistinguishable from one. Compared against the same room built with plain hull rather than
     * against a remembered figure, so it stays true if the solver changes.
     */
    @Test
    fun `a shut airlock behaves exactly as the hull it replaces`() {
        val withDoor = run(roomWithDoor(Airlock()), 60)
        val withWall = run(roomWithDoor(Hull()), 60)

        assertEquals(withWall.atmosphereGrams, withDoor.atmosphereGrams)
        assertEquals(withWall.airVentedGrams, withDoor.airVentedGrams)
    }

    @Test
    fun `a shut airlock keeps the room inside`() {
        val s = run(roomWithDoor(Airlock()), 1)
        assertEquals(Structure.Interior, s.structure[insideTheDoor(s)])
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    @Test
    fun `a signalled airlock lets the air out`() {
        val start = roomWithDoor(Airlock(wiring = held(SignalField.FULL)))
        val after = run(start, 60)

        assertTrue(
            after.atmosphereGrams < start.atmosphereGrams,
            "an open door should have drained the room: ${start.atmosphereGrams} -> ${after.atmosphereGrams}",
        )
        assertTrue(after.airVentedGrams > 0L, "and the gas that left should be booked as vented")
    }

    /**
     * The ledger, which is the thing that catches a hole in the *model* rather than in the door: air
     * may leave, but it may not cease to exist.
     */
    @Test
    fun `venting through an airlock keeps the air ledger balanced`() {
        val s = run(roomWithDoor(Airlock(wiring = held(SignalField.FULL))), 60)
        assertEquals(0L, s.airBalance, "aboard ${s.atmosphereGrams} + vented ${s.airVentedGrams}")
    }

    /**
     * Opening the door genuinely connects the room to space — see the note on `airlockOpenness`. This
     * is the case that would fail if the airlock were implemented as a solid tile with open faces,
     * which is the tempting shortcut: the gas would still leave, but the room would go on claiming to
     * be enclosed while it did, and the machines in it would never start radiating.
     */
    @Test
    fun `an open airlock puts the room outside`() {
        val s = run(roomWithDoor(Airlock(wiring = held(SignalField.FULL))), 1)
        assertEquals(Structure.Vacuum, s.structure[insideTheDoor(s)])
    }

    // ── Graded ────────────────────────────────────────────────────────────────

    /**
     * Half a signal is half a door. The aperture field has always been an area rather than a flag,
     * and this is the first thing that uses it as one — so it is worth an assertion that the grading
     * survives the trip from wiring, through the openness array, to the faces.
     */
    @Test
    fun `a wider signal vents faster`() {
        val ajar = run(roomWithDoor(Airlock(wiring = held(SignalField.FULL / 4))), 30).airVentedGrams
        val wide = run(roomWithDoor(Airlock(wiring = held(SignalField.FULL))), 30).airVentedGrams

        assertTrue(ajar > 0L, "a quarter-open door still leaks")
        assertTrue(wide > ajar, "and a fully open one leaks faster: $wide vs $ajar")
    }

    // ── Thrust ────────────────────────────────────────────────────────────────

    /**
     * The whole reason the machine exists: gas leaving through the right-hand wall pushes the ship
     * left. Only the sign and the direction are asserted — the magnitude belongs to the solver, and
     * to the rock density question that is still open at the time of writing.
     */
    @Ignore("no thrust between the cut-over and blocked-flux thrust — extraction plan step 6")
    @Test
    fun `venting out of one side drives the vessel the other way`() {
        val s = run(roomWithDoor(Airlock(wiring = held(SignalField.FULL))), 60)

        assertTrue(s.vesselImpulseX < 0L, "exhaust went +x, so the ship should go -x: ${s.vesselImpulseX}")
        assertTrue(
            s.vesselImpulseY == 0L || kotlin.math.abs(s.vesselImpulseY) < kotlin.math.abs(s.vesselImpulseX),
            "a door on the x wall should not drive the ship sideways: ${s.vesselImpulseY}",
        )
    }
}
