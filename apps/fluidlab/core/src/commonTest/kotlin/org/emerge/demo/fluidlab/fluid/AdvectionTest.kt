package org.emerge.demo.fluidlab.fluid

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.world.Grid
import org.emerge.demo.fluidlab.world.fluid.ApertureField
import org.emerge.demo.fluidlab.world.fluid.EdgeGrid
import org.emerge.demo.fluidlab.world.fluid.MomentumField
import org.emerge.demo.fluidlab.world.fluid.advectMass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conservative advection: gas goes where the velocity field points, and not one gram goes anywhere
 * else.
 *
 * The conservation tests here are the acceptance criterion for the whole scheme, not a nicety. The
 * standard advection step for a grid fluid sim is semi-Lagrangian, which is easier, more stable, and
 * loses mass continuously; the entire reason this is written in flux form is so that these assertions
 * can be exact equalities rather than tolerances. If any of them ever needs a tolerance, the scheme
 * has been replaced by a different one and the rocket at the end of the plan is no longer reachable.
 */
class AdvectionTest {

    private val grid = Grid(6, 4)
    private val edges = EdgeGrid(grid)
    private val open = ApertureField.allOpen(edges)

    private fun emptyAir() = LongArray(grid.size * Species.COUNT)

    private fun put(mass: LongArray, tile: Int, vararg parts: Pair<Species, Long>) {
        for ((s, mass) in parts) mass[tile * Species.COUNT + s.ordinal] += mass
    }

    private fun massAt(mass: LongArray, tile: Int): Long {
        var sum = 0L
        for (s in Species.ALL) sum += mass[tile * Species.COUNT + s.ordinal]
        return sum
    }

    private fun totalMass(mass: LongArray): Long {
        var sum = 0L
        for (g in mass) sum += g
        return sum
    }

    /** A uniform rightward field: every x-face carries [momentum], every y-face still. */
    private fun blowingRight(momentum: Long) = MomentumField.of(
        edges,
        LongArray(edges.xEdgeCount) { momentum },
        LongArray(edges.yEdgeCount),
    )

    @Test
    fun `gas moves the way the field points`() {
        val mass = emptyAir()
        val start = grid.index(1, 2)
        put(mass, start, Species.Nitrogen to 1000L)

        advectMass(edges, open, blowingRight(500L), mass)

        assertTrue(massAt(mass, start) < 1000L, "the source should have given something up")
        assertTrue(massAt(mass, grid.index(2, 2)) > 0L, "the tile downwind should have received it")
        assertEquals(0L, massAt(mass, grid.index(0, 2)), "nothing should go upwind")
    }

    @Test
    fun `every gram that leaves a tile arrives somewhere or is vented`() {
        val mass = emptyAir()
        for (x in 1 until grid.width - 1) {
            for (y in 1 until grid.height - 1) {
                put(
                    mass,
                    grid.index(x, y),
                    Species.Nitrogen to 755L,
                    Species.Oxygen to 232L,
                    Species.CarbonDioxide to 13L,
                )
            }
        }
        val baseline = totalMass(mass)

        // A field with a shear in it, so the pass is doing something less trivial than a uniform drift.
        val mx = LongArray(edges.xEdgeCount) { 300L }
        val my = LongArray(edges.yEdgeCount) { e -> if (edges.xOfYEdge(e) % 2 == 0) 200L else -200L }
        val field = MomentumField.of(edges, mx, my)

        var vented = 0L
        repeat(40) {
            vented += advectMass(edges, open, field, mass).ventedGrams
            assertEquals(baseline, totalMass(mass) + vented, "air ledger after this tick")
        }
        assertTrue(vented > 0L, "a field pointing off the grid should eventually vent something")
    }

    @Test
    fun `a shut face carries nothing`() {
        val mass = emptyAir()
        val start = grid.index(1, 2)
        put(mass, start, Species.Nitrogen to 1000L)

        val apertureX = IntArray(edges.xEdgeCount) { ApertureField.OPEN }
        apertureX[edges.rightEdgeOf(start)] = ApertureField.CLOSED
        val walled = ApertureField(edges, apertureX, IntArray(edges.yEdgeCount) { ApertureField.OPEN })

        advectMass(edges, walled, blowingRight(500L), mass)

        assertEquals(1000L, massAt(mass, start), "with its only outlet shut, nothing should leave")
    }

    @Test
    fun `a half-open face carries half as much`() {
        fun carriedThrough(aperture: Int): Long {
            val mass = emptyAir()
            val start = grid.index(1, 2)
            put(mass, start, Species.Nitrogen to 1000L)
            val apertureX = IntArray(edges.xEdgeCount) { ApertureField.CLOSED }
            apertureX[edges.rightEdgeOf(start)] = aperture
            val field = ApertureField(edges, apertureX, IntArray(edges.yEdgeCount) { ApertureField.CLOSED })
            advectMass(edges, field, blowingRight(500L), mass)
            return massAt(mass, grid.index(2, 2))
        }

        assertEquals(carriedThrough(ApertureField.OPEN) / 2, carriedThrough(ApertureField.OPEN / 2))
    }

    @Test
    fun `a tile drained from all four sides at once empties and no more`() {
        val mass = emptyAir()
        val tile = grid.index(3, 2)
        put(mass, tile, Species.Nitrogen to 100L)

        // Every face pointing away from the tile, fast enough that each alone would take most of it.
        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)
        mx[edges.leftEdgeOf(tile)] = -45L
        mx[edges.rightEdgeOf(tile)] = 45L
        my[edges.upEdgeOf(tile)] = -45L
        my[edges.downEdgeOf(tile)] = 45L

        val result = advectMass(edges, open, MomentumField.of(edges, mx, my), mass)

        assertEquals(0L, massAt(mass, tile), "an over-subscribed tile empties")
        assertEquals(100L, totalMass(mass) + result.ventedGrams, "and gives away exactly what it had")
        for (g in mass) assertTrue(g >= 0L, "no tile may go negative")
    }

    @Test
    fun `a draught carries the room's mix, not the good bits off the top`() {
        val mass = emptyAir()
        val start = grid.index(1, 2)
        put(mass, start, Species.Nitrogen to 800L, Species.Oxygen to 200L)

        // Half speed, so only half the tile moves and the split is actually being tested.
        advectMass(edges, open, blowingRight(250L), mass)

        val downwind = grid.index(2, 2)
        val n = mass[downwind * Species.COUNT + Species.Nitrogen.ordinal]
        val o = mass[downwind * Species.COUNT + Species.Oxygen.ordinal]
        assertTrue(n > 0L && o > 0L, "both species should have travelled")
        // 4:1 in the source, so 4:1 in what moved -- give or take the one gram integers cost.
        assertTrue(n in (4 * o - 2)..(4 * o + 2), "moved $n N to $o O, expected about 4:1")
    }

    @Test
    fun `nothing blows in from space`() {
        val mass = emptyAir()
        // A field pointing inward everywhere, and a completely empty grid.
        val result = advectMass(edges, open, blowingRight(500L), mass)

        assertEquals(0L, totalMass(mass))
        assertEquals(0L, result.ventedGrams)
    }

    @Test
    fun `gas driven off the edge is vented rather than lost`() {
        val mass = emptyAir()
        val lastColumn = grid.index(grid.width - 1, 2)
        put(mass, lastColumn, Species.Nitrogen to 1000L)

        val result = advectMass(edges, open, blowingRight(500L), mass)

        assertTrue(result.ventedGrams > 0L, "the rim face should have carried gas out of the world")
        assertEquals(1000L, totalMass(mass) + result.ventedGrams)
    }

    @Test
    fun `the recorded flux is what actually moved`() {
        val mass = emptyAir()
        val start = grid.index(1, 2)
        put(mass, start, Species.Nitrogen to 1000L)

        val before = massAt(mass, start)
        val result = advectMass(edges, open, blowingRight(500L), mass)

        // Downwind is the only open outlet in a still-in-y field, so the flux across that one face
        // must account for the whole change.
        assertEquals(before - massAt(mass, start), result.flux.xAt(edges.rightEdgeOf(start)))
    }
}
