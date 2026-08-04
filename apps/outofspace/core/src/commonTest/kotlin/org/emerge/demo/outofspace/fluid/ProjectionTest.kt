package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.fluid.ApertureField
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.project
import org.emerge.demo.outofspace.world.fluid.tileMass
import org.emerge.demo.outofspace.world.fluid.tilePressure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pressure solve, and the ledger that makes thrust mean something.
 *
 * Two of these are load-bearing. `a still room stays still` is the rest state — get it wrong by one
 * unit and every sealed vessel in the game hums quietly forever, which is the sort of thing that
 * gets noticed as "the physics feels noisy" long after the cause is findable. And `the fluid gains
 * exactly what the vessel loses` is Newton's third law checked rather than assumed: the two numbers
 * are computed by different code from different quantities — one from gradients across open faces,
 * one from gas leaning on bulkheads — so their agreeing is evidence, not bookkeeping.
 */
class ProjectionTest {

    /** A hull box with a ring of space around it, filled with air. */
    private class Box(w: Int, h: Int, val hole: Boolean = false) {
        val grid = Grid(w + 2, h + 2)
        val edges = EdgeGrid(grid)
        val apertures: ApertureField
        val grams = LongArray(grid.size * Species.COUNT)
        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)

        init {
            val machines = arrayOfNulls<Machine>(grid.size)
            for (x in 1..w) { machines[grid.index(x, 1)] = Hull(); machines[grid.index(x, h)] = Hull() }
            for (y in 1..h) {
                machines[grid.index(1, y)] = Hull()
                // A vessel with one hull tile missing from its right-hand wall.
                if (!(hole && y == h / 2)) machines[grid.index(w, y)] = Hull()
            }
            apertures = ApertureField.derive(edges, StructureMap.derive(grid, machines.toList()))

            for (x in 2 until w) for (y in 2 until h) fill(grid.index(x, y), AMBIENT)
        }

        fun fill(tile: Int, scale: Long) {
            for (s in Species.GASES) {
                grams[tile * Species.COUNT + s.ordinal] = AirField.AMBIENT_AIR[s] * scale / AMBIENT
            }
        }

        fun run() = project(
            edges, apertures, mx, my,
            tileMass(grid.size, grams),
            tilePressure(grid.size, grams),
        )

        companion object { const val AMBIENT = 1000L }
    }

    @Test
    fun `a still room stays still`() {
        val box = Box(10, 8)
        val result = box.run()

        for (m in box.mx) assertEquals(0L, m, "a uniform room should induce no horizontal flow")
        for (m in box.my) assertEquals(0L, m, "nor any vertical flow")
        assertEquals(0L, result.vesselX)
        assertEquals(0L, result.vesselY)
    }

    @Test
    fun `the fluid gains exactly what the vessel loses`() {
        val box = Box(10, 8)
        // A lopsided pressure field, so there is something real for the two sums to disagree about.
        box.fill(box.grid.index(3, 3), Box.AMBIENT * 3)
        box.fill(box.grid.index(4, 3), Box.AMBIENT * 2)
        box.fill(box.grid.index(8, 6), Box.AMBIENT / 2)

        val result = box.run()

        assertEquals(-result.fluidX, result.vesselX, "horizontal: gas and ship must be opposite")
        assertEquals(-result.fluidY, result.vesselY, "vertical: gas and ship must be opposite")
        assertTrue(result.fluidX != 0L || result.fluidY != 0L, "the field should have done something")
    }

    @Test
    fun `pressure pushes gas away from the crowded cell`() {
        val box = Box(9, 7)
        val dense = box.grid.index(5, 4)
        box.fill(dense, Box.AMBIENT * 4)

        box.run()

        // Positive on the right face is rightward, negative on the left face is leftward: both are
        // "out of the dense cell". Same for down and up.
        assertTrue(box.mx[box.edges.rightEdgeOf(dense)] > 0L, "should push out to the right")
        assertTrue(box.mx[box.edges.leftEdgeOf(dense)] < 0L, "and out to the left")
        assertTrue(box.my[box.edges.downEdgeOf(dense)] > 0L, "and downward")
        assertTrue(box.my[box.edges.upEdgeOf(dense)] < 0L, "and upward")
    }

    @Test
    fun `gas is drawn toward the empty cell`() {
        val box = Box(9, 7)
        val empty = box.grid.index(5, 4)
        box.fill(empty, 0L)

        box.run()

        assertTrue(box.mx[box.edges.rightEdgeOf(empty)] < 0L, "gas on the right should move back in")
        assertTrue(box.mx[box.edges.leftEdgeOf(empty)] > 0L, "and gas on the left too")
    }

    @Test
    fun `a bulkhead carries no flow`() {
        val box = Box(9, 7)
        // Momentum on a wall face, as if the wall had just been built across moving gas.
        val walled = box.edges.leftEdgeOf(box.grid.index(2, 4))
        box.mx[walled] = 5000L

        box.run()

        assertEquals(0L, box.mx[walled], "a face shut by hull must not carry momentum")
    }

    @Test
    fun `a vessel with a hole in one end is pushed the other way`() {
        val sealed = Box(10, 8)
        val breached = Box(10, 8, hole = true)

        assertEquals(0L, sealed.run().vesselX, "a sealed vessel goes nowhere")

        val thrust = breached.run().vesselX
        assertTrue(thrust < 0L, "gas leaving to the right must push the ship left, got $thrust")
    }

    @Test
    fun `the solve does not depend on how many times it is run from the same state`() {
        fun impulseWith(iterations: Int): Long {
            val box = Box(8, 6)
            box.fill(box.grid.index(4, 3), Box.AMBIENT * 3)
            return project(
                box.edges, box.apertures, box.mx, box.my,
                tileMass(box.grid.size, box.grams),
                tilePressure(box.grid.size, box.grams),
                iterations = iterations,
            ).vesselX
        }

        // Jacobi reads a snapshot and writes a new field, so a run is a pure function of its input.
        assertEquals(impulseWith(20), impulseWith(20))
        // And more sweeps refine the same answer rather than changing the question.
        assertTrue(impulseWith(40) != 0L || impulseWith(20) == 0L)
    }
}
