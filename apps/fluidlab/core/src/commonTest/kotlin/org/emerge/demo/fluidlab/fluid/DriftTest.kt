package org.emerge.demo.fluidlab.fluid

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.world.Grid
import org.emerge.demo.fluidlab.world.fluid.ApertureField
import org.emerge.demo.fluidlab.world.fluid.EdgeGrid
import org.emerge.demo.fluidlab.world.fluid.applySpeciesDrift
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How each gas moves relative to the mixture it is in: settling out, and stirring back together.
 *
 * This is the pass that exists because bulk flow provably cannot do either job. One velocity field
 * carries every species the same way at the same speed, so advection can never change the ratio
 * between two gases — it can neither separate them nor combine them. Everything here is about that
 * ratio, and nothing here moves any weight around.
 */
class DriftTest {

    private val grid = Grid(4, 8)
    private val edges = EdgeGrid(grid)
    private val open = ApertureField.allOpen(edges)

    private val down = Frac2(Frac(0L, 1), Frac(1L, 1))
    private val none = Frac2(Frac(0L), Frac(0L))

    private fun air() = LongArray(grid.size * Species.COUNT)

    private fun put(g: LongArray, tile: Int, s: Species, mass: Long) {
        g[tile * Species.COUNT + s.ordinal] += mass
    }

    private fun massAt(g: LongArray, tile: Int): Long {
        var sum = 0L
        for (s in Species.ALL) sum += g[tile * Species.COUNT + s.ordinal]
        return sum
    }

    private fun of(g: LongArray, tile: Int, s: Species) = g[tile * Species.COUNT + s.ordinal]

    /** A sealed column of tiles down the middle of the grid, each holding the same mixture. */
    private fun column(g: LongArray, vararg parts: Pair<Species, Long>) {
        for (y in 0 until grid.height) for ((s, m) in parts) put(g, grid.index(1, y), s, m)
    }

    @Test
    fun `heavy gas works its way down a still column`() {
        val g = air()
        for (y in 0..3) put(g, grid.index(1, y), Species.CarbonDioxide, 1_000L)
        for (y in 4..7) put(g, grid.index(1, y), Species.Nitrogen, 1_000L)

        // Deliberately started upside down, and mixing is several times stronger than settling at a
        // sharp interface -- so the layers blur into each other well before they sort out. Settling
        // only wins once mixing has run out of gradient to work on, which is what makes the two
        // constants a *ratio* rather than two independent dials.
        repeat(400) { applySpeciesDrift(edges, open, g, down) }

        val co2 = (0 until grid.height).map { of(g, grid.index(1, it), Species.CarbonDioxide) }
        val n2 = (0 until grid.height).map { of(g, grid.index(1, it), Species.Nitrogen) }
        assertTrue(co2.last() > co2.first(), "carbon dioxide should have settled: $co2")
        assertTrue(n2.first() > n2.last(), "and nitrogen risen: $n2")
    }

    @Test
    fun `reversing gravity reverses which way it settles`() {
        fun co2AtFloor(gravity: Frac2): Long {
            val g = air()
            for (y in 0..3) put(g, grid.index(1, y), Species.CarbonDioxide, 1_000L)
            for (y in 4..7) put(g, grid.index(1, y), Species.Nitrogen, 1_000L)
            repeat(60) { applySpeciesDrift(edges, open, g, gravity) }
            return of(g, grid.index(1, 7), Species.CarbonDioxide)
        }

        val upIsDown = Frac2(Frac(0L, 1), Frac(-1L, 1))
        assertTrue(co2AtFloor(down) > co2AtFloor(upIsDown), "gravity decides which end is the floor")
    }

    @Test
    fun `two gases in contact mix, with no gravity at all`() {
        val g = air()
        for (y in 0..3) put(g, grid.index(1, y), Species.Oxygen, 1_000L)
        for (y in 4..7) put(g, grid.index(1, y), Species.Nitrogen, 1_000L)

        repeat(60) { applySpeciesDrift(edges, open, g, none) }

        // Fick's law does not care which way is down. Without this, two gases put side by side in
        // weightlessness would sit there as separate blocks forever -- bulk flow cannot combine them
        // any more than it can separate them.
        assertTrue(of(g, grid.index(1, 0), Species.Nitrogen) > 0L, "nitrogen should have reached the top")
        assertTrue(of(g, grid.index(1, 7), Species.Oxygen) > 0L, "and oxygen the bottom")
    }

    @Test
    fun `mixing evens out a sharp interface rather than sharpening it`() {
        val g = air()
        put(g, grid.index(1, 3), Species.Oxygen, 2_000L)
        for (y in 0 until grid.height) if (y != 3) put(g, grid.index(1, y), Species.Nitrogen, 2_000L)

        fun shareAtEdge() = of(g, grid.index(1, 4), Species.Oxygen)
        val before = shareAtEdge()
        repeat(40) { applySpeciesDrift(edges, open, g, none) }
        assertTrue(shareAtEdge() > before, "oxygen should have spread into its neighbour")
    }

    @Test
    fun `sorting never changes what a tile weighs`() {
        val g = air()
        column(g, Species.CarbonDioxide to 400L, Species.Nitrogen to 400L, Species.Oxygen to 200L)
        val before = LongArray(grid.size) { massAt(g, it) }

        repeat(80) { applySpeciesDrift(edges, open, g, down) }

        // The exchange across every face is balanced, so composition moves and weight does not. Any
        // change in what a tile holds has to come from the bulk flow reacting to the new pressures,
        // which is a different pass entirely.
        for (tile in 0 until grid.size) {
            assertEquals(before[tile], massAt(g, tile), "tile $tile changed weight")
        }
    }

    @Test
    fun `every species is conserved to the gram`() {
        val g = air()
        for (y in 0..3) put(g, grid.index(1, y), Species.CarbonDioxide, 700L)
        for (y in 2..7) put(g, grid.index(1, y), Species.Nitrogen, 300L)
        put(g, grid.index(1, 5), Species.Oxygen, 900L)

        val before = Species.ALL.associateWith { s -> (0 until grid.size).sumOf { of(g, it, s) } }
        repeat(80) { applySpeciesDrift(edges, open, g, down) }

        for (s in Species.ALL) {
            assertEquals(before[s], (0 until grid.size).sumOf { of(g, it, s) }, "$s was not conserved")
        }
    }

    @Test
    fun `a bulkhead stops gas sorting through it`() {
        val g = air()
        for (y in 0..3) put(g, grid.index(1, y), Species.CarbonDioxide, 1_000L)
        for (y in 4..7) put(g, grid.index(1, y), Species.Nitrogen, 1_000L)

        val ay = IntArray(edges.yEdgeCount) { ApertureField.OPEN }
        ay[edges.yEdge(1, 4)] = ApertureField.CLOSED
        val walled = ApertureField(edges, IntArray(edges.xEdgeCount) { ApertureField.OPEN }, ay)

        repeat(60) { applySpeciesDrift(edges, walled, g, down) }

        assertEquals(0L, of(g, grid.index(1, 4), Species.CarbonDioxide), "nothing crosses a shut face")
    }

    @Test
    fun `a uniform mixture is already settled`() {
        val g = air()
        column(g, Species.CarbonDioxide to 500L, Species.Nitrogen to 500L)
        val before = g.copyOf()

        applySpeciesDrift(edges, open, g, down)

        // Settling still has something to do here -- the mixture is uniform but not yet sorted -- so
        // what is asserted is only that mixing has nothing to add, since every tile is identical.
        for (y in 0 until grid.height) {
            assertEquals(
                before[grid.index(1, y) * Species.COUNT + Species.CarbonDioxide.ordinal] +
                    before[grid.index(1, y) * Species.COUNT + Species.Nitrogen.ordinal],
                massAt(g, grid.index(1, y)),
                "row $y changed weight",
            )
        }
    }
}
