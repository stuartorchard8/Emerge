package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.fluid.ApertureField
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.MomentumField
import org.emerge.demo.outofspace.world.fluid.advectMass
import org.emerge.demo.outofspace.world.fluid.advectMomentum
import org.emerge.demo.outofspace.world.fluid.tileMass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Momentum rides on the mass, and the books balance.
 *
 * The assertion this file exists for is `sealed vessel produces exactly zero net thrust, forever`.
 * Everything in the plan's end state hangs off it: thrust is the momentum crossing the boundary, so
 * if the interior can invent or mislay any, then a vessel welded shut will slowly wander off under
 * its own power and every thrust figure downstream is an artefact of the discretisation. It is also
 * the one bug that would be nearly impossible to find later, because a slow drift looks exactly like
 * a physics engine having character.
 */
class MomentumAdvectionTest {

    /** One tick of the transport half: mass moves, momentum goes with it. */
    private class Fluid(val grid: Grid) {
        val edges = EdgeGrid(grid)
        var apertures = ApertureField.allOpen(edges)
        val grams = LongArray(grid.size * Species.COUNT)
        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)
        var ventedGrams = 0L
        var escapedX = 0L
        var escapedY = 0L

        fun step() {
            val tileGrams = tileMass(grid.size, grams)
            val result = advectMass(edges, apertures, MomentumField.of(edges, mx, my), grams, Species.GASES, tileGrams)
            ventedGrams += result.ventedGrams
            val escape = advectMomentum(edges, mx, my, result.flux, tileGrams)
            escapedX += escape.x
            escapedY += escape.y
        }

        fun put(tile: Int, mass: Long) {
            grams[tile * Species.COUNT + Species.Nitrogen.ordinal] += mass
        }

        val totalGrams: Long get() = grams.sum()
        val totalX: Long get() = mx.sum()
        val totalY: Long get() = my.sum()
    }

    /** A hull box with a ring of space around it, and the apertures that go with it. */
    private fun sealedBox(w: Int, h: Int): Fluid {
        val grid = Grid(w + 2, h + 2)
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1..w) { machines[grid.index(x, 1)] = Hull(); machines[grid.index(x, h)] = Hull() }
        for (y in 1..h) { machines[grid.index(1, y)] = Hull(); machines[grid.index(w, y)] = Hull() }

        val fluid = Fluid(grid)
        fluid.apertures = ApertureField.derive(fluid.edges, StructureMap.derive(grid, machines.toList()))
        return fluid
    }

    @Test
    fun `a sealed vessel produces no thrust, however long it sloshes`() {
        val fluid = sealedBox(8, 6)
        // Fill the interior and set it moving diagonally, so both axes and both dual-face families
        // are doing work rather than one trivial drift.
        for (x in 2..7) {
            for (y in 2..5) {
                fluid.put(fluid.grid.index(x, y), 1000L)
                fluid.mx[fluid.edges.leftEdgeOf(fluid.grid.index(x, y))] = 200L
                fluid.my[fluid.edges.upEdgeOf(fluid.grid.index(x, y))] = -150L
            }
        }
        val startGrams = fluid.totalGrams
        val startX = fluid.totalX
        val startY = fluid.totalY

        repeat(60) { tick ->
            fluid.step()
            assertEquals(0L, fluid.ventedGrams, "tick $tick: a sealed box vents nothing")
            assertEquals(0L, fluid.escapedX, "tick $tick: nor does any momentum escape it")
            assertEquals(0L, fluid.escapedY, "tick $tick")
            assertEquals(startGrams, fluid.totalGrams, "tick $tick: mass")
            assertEquals(startX, fluid.totalX, "tick $tick: x-momentum")
            assertEquals(startY, fluid.totalY, "tick $tick: y-momentum")
        }
    }

    @Test
    fun `momentum that leaves the grid is accounted rather than dropped`() {
        val grid = Grid(6, 4)
        val fluid = Fluid(grid)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) fluid.put(grid.index(x, y), 1000L)
        }
        // Everything heading right, so it all eventually leaves through the rim.
        for (e in fluid.mx.indices) fluid.mx[e] = 300L

        val startX = fluid.totalX
        repeat(30) { tick ->
            fluid.step()
            assertEquals(startX, fluid.totalX + fluid.escapedX, "tick $tick: x-momentum ledger")
        }
        assertTrue(fluid.escapedX > 0L, "a field pointing off the grid should shed momentum")
    }

    @Test
    fun `momentum travels with the mass that carries it`() {
        val grid = Grid(8, 3)
        val fluid = Fluid(grid)
        val start = grid.index(1, 1)
        fluid.put(start, 1000L)
        // Give the blob and its surroundings a rightward push.
        for (e in fluid.mx.indices) fluid.mx[e] = 200L
        val downwindBefore = fluid.mx[fluid.edges.rightEdgeOf(grid.index(2, 1))]

        fluid.step()

        assertTrue(
            fluid.mx[fluid.edges.rightEdgeOf(grid.index(2, 1))] >= downwindBefore,
            "the face ahead of the moving gas should not have lost momentum to it",
        )
        assertTrue(fluid.grams[grid.index(2, 1) * Species.COUNT + Species.Nitrogen.ordinal] > 0L)
    }

    @Test
    fun `an empty grid invents no momentum`() {
        val fluid = Fluid(Grid(5, 5))
        for (e in fluid.mx.indices) fluid.mx[e] = 400L
        val startX = fluid.totalX

        repeat(10) { fluid.step() }

        // No mass means no mass flux, so nothing is carried anywhere and nothing escapes.
        assertEquals(startX, fluid.totalX)
        assertEquals(0L, fluid.escapedX)
        assertEquals(0L, fluid.totalGrams)
    }

    @Test
    fun `a face cannot give away more momentum than it holds`() {
        val fluid = sealedBox(6, 5)
        for (x in 2..5) for (y in 2..4) fluid.put(fluid.grid.index(x, y), 100L)

        // A deliberately violent field: every face at nearly the CFL limit, alternating in sign so
        // dual cells are drained from several sides at once.
        for (e in fluid.mx.indices) fluid.mx[e] = if (e % 2 == 0) 90L else -90L
        for (e in fluid.my.indices) fluid.my[e] = if (e % 3 == 0) 90L else -90L

        val startX = fluid.totalX
        val startY = fluid.totalY
        repeat(25) { tick ->
            fluid.step()
            assertEquals(startX, fluid.totalX + fluid.escapedX, "tick $tick: x")
            assertEquals(startY, fluid.totalY + fluid.escapedY, "tick $tick: y")
            for (g in fluid.grams) assertTrue(g >= 0L, "tick $tick: negative mass")
        }
    }
}
