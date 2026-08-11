package org.emerge.demo.fluidlab.fluid

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.world.AirField
import org.emerge.demo.fluidlab.world.Grid
import org.emerge.demo.fluidlab.world.fluid.ApertureField
import org.emerge.demo.fluidlab.world.fluid.EdgeGrid
import org.emerge.demo.fluidlab.world.fluid.stepFluid
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The solver run over connectivity that is mostly *absent* — which is what a pipe network is.
 *
 * The vessel's atmosphere is a solid block of connected cells with a shell of closed faces round it,
 * and every fluid test until now has been that shape. A pipe layer is the opposite: a thin thread of
 * connected cells in a grid where almost every face is shut, because a pipe conducts only where the
 * player drew a link. §5b's claim is that this needs no new solver, and this file is where that claim
 * stops being a claim.
 *
 * Nothing here builds a pipe. It hands the existing solver an aperture field of the shape a pipe will
 * have and checks the two things that would make the plan wrong: that isolated gas is left alone
 * rather than quietly pumped or leaked, and that a thread of connected cells actually conducts.
 */
class SparseFieldTest {

    private val grid = Grid(10, 6)
    private val edges = EdgeGrid(grid)
    private val gravity = Frac2(Frac(0L), Frac(0L))

    /** Every face shut, then opened one at a time — the inverse of how the vessel is built. */
    private fun sealed(): Pair<IntArray, IntArray> =
        IntArray(edges.xEdgeCount) to IntArray(edges.yEdgeCount)

    private fun openBetween(x: IntArray, ax: Int, ay: Int, bx: Int, by: Int) {
        require(ay == by && bx == ax + 1) { "this helper joins horizontal neighbours only" }
        x[edges.xEdge(bx, ay)] = ApertureField.OPEN
    }

    private fun fill(grams: LongArray, tile: Int, scale: Long = 1L) {
        for (s in Species.ALL) {
            grams[tile * Species.COUNT + s.ordinal] = AirField.AMBIENT_AIR[s] * scale
        }
    }

    private fun massAt(grams: LongArray, tile: Int): Long {
        var sum = 0L
        for (s in Species.ALL) sum += grams[tile * Species.COUNT + s.ordinal]
        return sum
    }

    @Test
    fun `a cell with every face shut keeps what it holds`() {
        val (x, y) = sealed()
        val apertures = ApertureField(edges, x, y)
        val grams = LongArray(grid.size * Species.COUNT)
        val lone = grid.index(4, 3)
        fill(grams, lone, scale = 3)
        val before = massAt(grams, lone)

        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)
        var vented = 0L
        repeat(50) {
            vented += stepFluid(edges, apertures, grams, mx, my, gravity).ventedGrams
        }

        assertEquals(before, massAt(grams, lone), "isolated gas moved")
        assertEquals(0L, vented, "isolated gas escaped a sealed cell")
        assertTrue(mx.all { it == 0L } && my.all { it == 0L }, "a sealed cell acquired momentum")
    }

    @Test
    fun `a thread of linked cells conducts along itself and nowhere else`() {
        val (x, y) = sealed()
        // A run from (2,3) to (7,3): the shape of a pipe, drawn as apertures rather than as tiles.
        for (i in 2 until 7) openBetween(x, i, 3, i + 1, 3)
        val apertures = ApertureField(edges, x, y)

        val grams = LongArray(grid.size * Species.COUNT)
        val head = grid.index(2, 3)
        val tail = grid.index(7, 3)
        fill(grams, head, scale = 4)
        // A bystander directly beneath the run, to catch the solver leaking across a shut face.
        val bystander = grid.index(4, 4)
        fill(grams, bystander)
        val bystanderBefore = massAt(grams, bystander)
        val total = grams.sum()

        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)
        var vented = 0L
        repeat(200) {
            vented += stepFluid(edges, apertures, grams, mx, my, gravity).ventedGrams
        }

        assertTrue(massAt(grams, tail) > 0L, "nothing reached the far end of the run")
        assertEquals(bystanderBefore, massAt(grams, bystander), "gas crossed a closed face")
        assertEquals(0L, vented, "a sealed run vented")
        assertEquals(total, grams.sum(), "mass was not conserved along the run")
    }
}
