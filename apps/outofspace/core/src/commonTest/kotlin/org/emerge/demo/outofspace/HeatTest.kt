package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Belt
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.HeatField
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structure and heat — the first half of the systems layer.
 *
 * The headline assertion is the thermal twin of the mass balance:
 *
 *     stored + radiated − generated == baseline
 *
 * on every tick. Energy is the stored quantity and temperature is derived from it, precisely so that
 * this can be checked exactly; a field of temperatures with no capacities behind it would create and
 * destroy energy every time two unlike tiles met, and nothing would ever notice.
 */
class HeatTest {

    private fun cfgFor(grid: Grid) = OutofspaceConfig(grid = grid)

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val cfg = cfgFor(state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun assertEnergyBalanced(s: VesselState, what: String) {
        assertEquals(
            s.baselineJoules,
            s.storedJoules + s.radiatedJoules - s.generatedJoules,
            "$what: stored ${s.storedJoules} + radiated ${s.radiatedJoules} - generated ${s.generatedJoules}",
        )
    }

    /** A hull box with a hollow middle, [w] x [h] outer. */
    private fun sealedRoom(w: Int, h: Int, fill: (Int, Int) -> Machine? = { _, _ -> null }): VesselState {
        val grid = Grid(w + 2, h + 2)   // a ring of open space around the box, so it is not clipped
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1..w) {
            machines[grid.index(x, 1)] = Hull()
            machines[grid.index(x, h)] = Hull()
        }
        for (y in 1..h) {
            machines[grid.index(1, y)] = Hull()
            machines[grid.index(w, y)] = Hull()
        }
        for (y in 2 until h) for (x in 2 until w) machines[grid.index(x, y)] = fill(x, y)
        return VesselState(grid, machines.toList())
    }

    // ── Structure ─────────────────────────────────────────────────────────────

    @Test
    fun `hull encloses an interior and everything else is outside`() {
        val s = sealedRoom(6, 6)
        val g = s.grid
        assertEquals(Structure.Interior, s.structure[g.index(3, 3)], "the middle is inside")
        assertEquals(Structure.Hull, s.structure[g.index(1, 3)], "the wall is wall")
        assertEquals(Structure.Vacuum, s.structure[g.index(0, 0)], "the corner is space")
    }

    @Test
    fun `a single missing hull tile turns the room back into outside`() {
        val sealed = sealedRoom(6, 6)
        val g = sealed.grid
        assertEquals(Structure.Interior, sealed.structure[g.index(3, 3)])

        val breached = run(sealed, 2, OutofspaceInput(listOf(Edit.Remove(g.index(3, 1)))))
        assertEquals(
            Structure.Vacuum,
            breached.structure[g.index(3, 3)],
            "space pours in through the hole; there is no separate notion of a leak",
        )
    }

    @Test
    fun `only hull seals - a wall of belts does not`() {
        val grid = Grid(5, 3)
        val machines = arrayOfNulls<Machine>(15)
        for (x in 0 until 5) {
            machines[grid.index(x, 0)] = Belt(Direction.Right)
            machines[grid.index(x, 2)] = Belt(Direction.Right)
        }
        val s = VesselState(grid, machines.toList())
        assertEquals(
            Structure.Vacuum,
            s.structure[grid.index(2, 1)],
            "a conveyor is machinery in a room, not a pressure vessel",
        )
    }

    // ── Conservation ──────────────────────────────────────────────────────────

    @Test
    fun `energy is conserved on every tick of a working vessel`() {
        var s = starterVessel(Grid(28, 20))
        val cfg = cfgFor(s.grid)
        repeat(60 * 60) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 83 == 0) assertEnergyBalanced(s, "tick ${s.tick}")
        }
        assertEnergyBalanced(s, "final")
        assertTrue(s.generatedJoules > 0L, "the smelter should have produced waste heat")
        assertTrue(s.radiatedJoules > 0L, "and the hull should have shed some of it")
    }

    @Test
    fun `breaching a hull radiates the room's heat rather than deleting it`() {
        val sealed = sealedRoom(8, 8)
        val g = sealed.grid
        val warm = run(sealed, 60)
        assertEnergyBalanced(warm, "before the breach")
        val storedBefore = warm.storedJoules

        val breached = run(warm, 5, OutofspaceInput(listOf(Edit.Remove(g.index(4, 1)))))
        // The walls keep their heat -- steel has far more thermal mass than air, so most of a
        // vessel's stored energy is in its hull. What the breach empties is the *interior*.
        for (y in 2 until 8) for (x in 2 until 8) {
            assertEquals(0L, breached.heat.joulesAt(g.index(x, y)), "interior tile ($x,$y) should be empty")
        }
        assertTrue(breached.storedJoules < storedBefore, "and the total dropped")
        assertEnergyBalanced(breached, "after the breach")
    }

    // ── Behaviour ─────────────────────────────────────────────────────────────

    @Test
    fun `a smelter warms its own tile first and its neighbours after`() {
        // It needs somewhere to put both output streams or it stalls on the output cap after four
        // kilograms and never produces enough heat to measure -- which is what happened first time.
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 200_000L))
        val room = sealedRoom(9, 9) { x, y ->
            when {
                x == 5 && y == 5 -> Smelter(Direction.Right, input = ore)
                x == 6 && y == 5 -> Vent()      // refined leaves forward
                x == 5 && y == 6 -> Vent()      // slag leaves clockwise of forward
                else -> null
            }
        }
        val g = room.grid
        val s = run(room, 60 * 30)

        val atSmelter = s.kelvinAt(g.index(5, 5))
        val twoAway = s.kelvinAt(g.index(3, 5))
        val farCorner = s.kelvinAt(g.index(2, 8))

        assertTrue(atSmelter > HeatField.AMBIENT_KELVIN + 15, "the furnace tile should be hot: ${atSmelter}K")
        assertTrue(atSmelter > twoAway, "hottest at the source: $atSmelter vs $twoAway")
        assertTrue(twoAway >= farCorner, "and cooler with distance: $twoAway vs $farCorner")
    }

    @Test
    fun `heat never overshoots into an oscillation`() {
        // One very hot tile beside cold ones. A flux computed without an equalising cap would send
        // more energy than the gap holds and the two tiles would swap hot and cold every tick.
        val room = sealedRoom(6, 6)
        val g = room.grid
        val joules = room.heat.copyJoules()
        joules[g.index(3, 3)] = 4_000L * HeatField.INTERIOR_CAPACITY   // 4000K in one tile
        var s = room.copy(heat = HeatField.of(joules), baselineJoules = HeatField.of(joules).totalJoules)

        var previousPeak = Int.MAX_VALUE
        repeat(240) {
            s = OutofspaceReducer.reduce(cfgFor(s.grid), s, emptyMap())
            val peak = (0 until s.grid.size).maxOf { i -> if (s.structure.isVacuum(i)) 0 else s.kelvinAt(i) }
            assertTrue(peak <= previousPeak, "the hottest tile got hotter with no source: $peak > $previousPeak")
            previousPeak = peak
        }
        assertEnergyBalanced(s, "after settling")
    }

    @Test
    fun `a sealed room with no heat source cools toward space`() {
        var s = sealedRoom(6, 6)
        val g = s.grid
        val startK = s.kelvinAt(g.index(3, 3))
        s = run(s, 60 * 120)
        val endK = s.kelvinAt(g.index(3, 3))
        assertTrue(endK < startK, "it should have cooled: $startK -> $endK")
        assertTrue(endK >= HeatField.SPACE_KELVIN, "but never below space itself: ${endK}K")
        assertEnergyBalanced(s, "after cooling")
    }

    @Test
    fun `machines outside the hull dump their heat straight to space`() {
        val grid = Grid(5, 3)
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 20_000L))
        val machines = arrayOfNulls<Machine>(15)
        machines[grid.index(2, 1)] = Smelter(Direction.Right, input = ore)
        var s = VesselState(grid, machines.toList())
        s = run(s, 60 * 10)

        assertEquals(Structure.Vacuum, s.structure[grid.index(2, 1)], "nothing encloses it")
        assertEquals(0L, s.storedJoules, "so it stores nothing")
        assertTrue(s.generatedJoules > 0L && s.radiatedJoules == s.generatedJoules, "it all went to space")
        assertEnergyBalanced(s, "bare machine")
    }

    @Test
    fun `placing hull is an ordinary build action`() {
        val grid = Grid(4, 3)
        var s = VesselState(grid, List(grid.size) { null })
        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(grid.index(1, 1), MachineKind.Hull, Direction.Right))))
        assertTrue(s[grid.index(1, 1)] is Hull)
        assertEquals(Structure.Hull, s.structure[grid.index(1, 1)])
    }

    @Test
    fun `two runs of a heated world are identical`() {
        fun digest(s: VesselState) = buildString {
            append(s.storedJoules).append('|').append(s.radiatedJoules).append('|').append(s.generatedJoules)
            for (i in 0 until s.grid.size) append(s.kelvinAt(i)).append(',')
        }
        val grid = Grid(24, 16)
        assertEquals(digest(run(starterVessel(grid), 900)), digest(run(starterVessel(grid), 900)))
    }

    @Test
    fun `structure derivation is not fooled by a hull that only half encloses`() {
        val grid = Grid(6, 5)
        val machines = arrayOfNulls<Machine>(30)
        // Three walls and an open side: still outside.
        for (x in 1..4) machines[grid.index(x, 1)] = Hull()
        for (y in 1..3) { machines[grid.index(1, y)] = Hull(); machines[grid.index(4, y)] = Hull() }
        val s = VesselState(grid, machines.toList())
        assertEquals(Structure.Vacuum, s.structure[grid.index(2, 2)], "an open-bottomed box is not a room")
        assertEquals(0, s.structure.interiorCount)
    }
}
