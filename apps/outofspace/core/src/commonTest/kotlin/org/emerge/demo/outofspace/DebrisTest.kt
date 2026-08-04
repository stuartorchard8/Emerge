package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Debris
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.downDirection
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Taking a machine apart, and where its contents end up.
 *
 * Dismantling used to delete whatever a machine was holding, and the mass balance reported a leak —
 * correctly. The interesting part of the fix is that the invariant was *right* and the world was
 * wrong: the answer was to give the material somewhere to fall, not to exempt player edits from the
 * accounting. So the assertion that matters most here is the same one as everywhere else.
 */
class DebrisTest {

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val cfg = OutofspaceConfig(grid = state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private val ingots = Resource(Form.IronIngot, Mixture.of(Species.Iron to 9_000L))

    /**
     * A sealed box [w] x [h] with a hollow middle, so removals happen somewhere with a floor.
     *
     * Rooms here are generous because a tank is three tiles across: two of them need four tiles
     * between their centres to not overlap, and one needs a tile of clearance from the wall.
     */
    private fun room(w: Int, h: Int, fill: (Int, Int) -> Machine? = { _, _ -> null }): VesselState {
        val grid = Grid(w + 2, h + 2)
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1..w) { machines[grid.index(x, 1)] = Hull(); machines[grid.index(x, h)] = Hull() }
        for (y in 1..h) { machines[grid.index(1, y)] = Hull(); machines[grid.index(w, y)] = Hull() }
        for (y in 2 until h) for (x in 2 until w) machines[grid.index(x, y)] = fill(x, y)
        return VesselState(grid, machines.toList())
    }

    @Test
    fun `dismantling a full storage spills its contents instead of deleting them`() {
        val s0 = room(12, 12) { x, y -> if (x == 4 && y == 3) Storage(Direction.Right, ingots) else null }
        val g = s0.grid
        assertEquals(9_000L, s0.inTransitGrams, "the tank's contents are aboard to begin with")

        val s = run(s0, 1, OutofspaceInput(listOf(Edit.Remove(g.index(4, 3)))))
        assertEquals(9_000L, s.debrisGrams, "and still aboard afterwards, on the floor")
        assertEquals(9_000L, s.inTransitGrams, "which is the same total, differently placed")
    }

    @Test
    fun `spilled material falls until it reaches the deck`() {
        val s0 = room(12, 12) { x, y -> if (x == 4 && y == 3) Storage(Direction.Right, ingots) else null }
        val g = s0.grid

        // Removed near the top of a twelve-tile room, so it has a long way to fall.
        var s = run(s0, 1, OutofspaceInput(listOf(Edit.Remove(g.index(4, 3)))))
        assertTrue(s.debris.massAt(g.index(4, 3)) > 0L || s.debris.massAt(g.index(4, 4)) > 0L)

        s = run(s, 30)
        assertEquals(9_000L, s.debris.massAt(g.index(4, 11)), "it should be resting on the lowest floor")
        assertEquals(0L, s.debris.massAt(g.index(4, 3)), "and nothing left where it came from")
    }

    @Test
    fun `a heap keeps its forms apart rather than blending them`() {
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 2_000L, Species.Silica to 2_000L))
        val s0 = room(12, 12) { x, y ->
            when {
                x == 4 && y == 3 -> Storage(Direction.Right, ingots)
                x == 8 && y == 3 -> Storage(Direction.Right, ore)
                else -> null
            }
        }
        val g = s0.grid
        val s = run(s0, 30, OutofspaceInput(listOf(
            Edit.Remove(g.index(4, 3)),
            Edit.Remove(g.index(8, 3)),
        )))
        assertEquals(listOf(Form.IronIngot), s.debris[g.index(4, 11)].map { it.form })
        assertEquals(listOf(Form.Ore), s.debris[g.index(8, 11)].map { it.form })
        // The ore is still a mixture, and still the mixture it was.
        assertEquals(2_000L, s.debris.mixtureAt(g.index(8, 11))[Species.Silica])
    }

    @Test
    fun `two piles landing on the same tile merge by form`() {
        // Same column, four tiles apart so the two footprints do not overlap.
        val s0 = room(12, 12) { x, y ->
            when {
                x == 4 && y == 3 -> Storage(Direction.Right, ingots)
                x == 4 && y == 7 -> Storage(Direction.Right, ingots)
                else -> null
            }
        }
        val g = s0.grid
        val s = run(s0, 30, OutofspaceInput(listOf(
            Edit.Remove(g.index(4, 3)),
            Edit.Remove(g.index(4, 7)),
        )))
        assertEquals(18_000L, s.debris.massAt(g.index(4, 11)), "both loads ended up in one heap")
        assertEquals(1, s.debris[g.index(4, 11)].size, "as a single entry, being the same form")
    }

    @Test
    fun `material spilled outside the hull goes overboard rather than lying in space`() {
        val grid = Grid(5, 3)
        val machines = arrayOfNulls<Machine>(grid.size)
        machines[grid.index(2, 1)] = Storage(Direction.Right, ingots)
        val s0 = VesselState(grid, machines.toList())

        val s = run(s0, 3, OutofspaceInput(listOf(Edit.Remove(grid.index(2, 1)))))
        assertEquals(0L, s.debrisGrams, "there is no deck out there to land on")
        assertEquals(9_000L, s.ventedGrams, "so it left by the only route matter ever leaves")
    }

    @Test
    fun `debris falls through machinery rather than piling on top of it`() {
        // A belt spanning the column the heap falls down. Rubble on the deck under a conveyor is
        // rubble on the deck; blocking it would leave piles hanging where a machine happened to be.
        val s0 = room(12, 12) { x, y ->
            when {
                x == 4 && y == 3 -> Storage(Direction.Right, ingots)
                x == 4 && y == 8 -> Sensor(Direction.Right)
                else -> null
            }
        }
        val g = s0.grid
        val s = run(s0, 30, OutofspaceInput(listOf(Edit.Remove(g.index(4, 3)))))
        assertEquals(9_000L, s.debris.massAt(g.index(4, 11)), "it reached the floor past the belt")
    }

    @Test
    fun `sideways gravity makes heaps settle against a wall`() {
        // Nothing about settling may assume down is +y. This is the same guard stratification has.
        val sideways = Frac2(Frac(1L, 1), Frac(0L, 1))
        val s0 = room(12, 8) { x, y -> if (x == 4 && y == 4) Storage(Direction.Right, ingots) else null }
            .copy(gravity = sideways)
        val g = s0.grid
        val s = run(s0, 30, OutofspaceInput(listOf(Edit.Remove(g.index(4, 4)))))
        assertEquals(9_000L, s.debris.massAt(g.index(11, 4)), "it slid to the far wall, not the floor")
    }

    /**
     * A gravity off the axis is **rounded** to the axis it leans toward, and only a genuine tie has
     * no answer.
     *
     * This asserts on [downDirection] rather than on a world, and that is the point rather than a
     * shortcut. It used to run a room under an exactly diagonal gravity and check that nothing moved,
     * and that world no longer exists: as of increment G the gravity a room is run under is the
     * plating *plus the thrust*, and a roomful of air leaning on its walls is enough to knock any
     * hand-typed vector a hair off the diagonal. The rule being tested was never about the room
     * anyway — it is about what a vector means to a heap that has four directions to choose from.
     *
     * A pull that is 99 parts down and one part right means down. Answering "nowhere" to that would
     * not be caution, it would freeze every pile aboard any vessel whose engine is lit. What has no
     * answer is a *tie* — an exact diagonal, where down and right are equally right — and that is
     * still null, because picking one would be a guess rather than a rounding.
     */
    @Test
    fun `an off-axis gravity rounds to the axis it leans toward`() {
        val one = Frac(1L, 1).raw
        fun at(x: Long, y: Long) = downDirection(Frac2(Frac(x), Frac(y)))

        assertEquals(Direction.Down, at(one / 100L, one), "a hair off vertical is still down")
        assertEquals(Direction.Up, at(-one / 100L, -one))
        assertEquals(Direction.Right, at(one, one / 100L), "and a hair off horizontal is still sideways")
        assertEquals(Direction.Left, at(-one, one / 100L))
        // The two cases with nothing to round toward.
        assertNull(at(one, one), "an exact diagonal is a tie, not a direction")
        assertNull(at(0L, 0L), "and weightlessness is not a direction either")
    }

    @Test
    fun `the world still never loses a gram when the player takes it apart`() {
        var s = starterVessel(Grid(40, 28))
        val cfg = OutofspaceConfig(grid = s.grid)
        s = run(s, 80)

        // Rip out every machine on one row of the working line, mid-flow.
        val y = 12   // the row the starter vessel's main line runs along
        val edits = (3..30).map { Edit.Remove(s.grid.index(it, y)) }
        s = OutofspaceReducer.reduce(cfg, s, mapOf(PlayerId(0) to OutofspaceInput(edits)))

        repeat(120) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            assertEquals(
                s.minedGrams,
                s.inTransitGrams + s.ventedGrams,
                "tick ${s.tick}: dismantling must move mass, not destroy it",
            )
        }
        assertTrue(s.debrisGrams > 0L, "and there should be a mess on the deck to show for it")
    }

    @Test
    fun `two runs of a world being dismantled are identical`() {
        fun digest(): String {
            var s = starterVessel(Grid(40, 28))
            val cfg = OutofspaceConfig(grid = s.grid)
            s = run(s, 300)
            val y = 12   // the row the starter vessel's main line runs along
            val edits = (3..30).map { Edit.Remove(s.grid.index(it, y)) }
            s = OutofspaceReducer.reduce(cfg, s, mapOf(PlayerId(0) to OutofspaceInput(edits)))
            s = run(s, 120)
            return buildString {
                append(s.debris.toString())
                for (t in s.debris.tiles()) append('|').append(t).append(':').append(s.debris[t])
            }
        }
        assertEquals(digest(), digest())
    }

    @Test
    fun `a pile stops falling into a tile that is already full`() {
        val huge = Resource(Form.IronIngot, Mixture.of(Species.Iron to Debris.TILE_CAP))
        val s0 = room(12, 12) { x, y ->
            when {
                x == 4 && y == 3 -> Storage(Direction.Right, ingots)
                x == 4 && y == 7 -> Storage(Direction.Right, huge)
                else -> null
            }
        }
        val g = s0.grid
        val s = run(s0, 30, OutofspaceInput(listOf(
            Edit.Remove(g.index(4, 3)),
            Edit.Remove(g.index(4, 7)),
        )))
        assertEquals(Debris.TILE_CAP, s.debris.massAt(g.index(4, 11)), "the deck tile took its fill")
        assertEquals(9_000L, s.debris.massAt(g.index(4, 10)), "and the rest rests on top of it")
    }
}
