package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.SLOTS
import org.emerge.demo.outofspace.world.diffuseFluid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rapid diffusion: the air model that replaces the momentum solver.
 *
 * These are the properties the model is *for*, and none of them is a magnitude: mass and energy are
 * conserved to the unit, no tile ever goes negative, a sealed box settles uniform rather than
 * biased, a hole in the rim books exactly what left, and — the property the remainder rotation
 * exists for — nothing gets stranded by rounding. There is deliberately no assertion here about how
 * fast anything happens; speed is the one thing [org.emerge.demo.outofspace.world.SLOTS] is
 * allowed to change, and pinning it would turn a tuning dial into a test to fight.
 */
class RapidDiffusionTest {

    private val grid = Grid(6, 5)
    private val edges = EdgeGrid(grid)

    /** A box with solid walls all round: every rim face shut, every interior face open. */
    private fun sealed(): ApertureField {
        val x = IntArray(edges.xEdgeCount) { if (edges.isXBoundary(it)) ApertureField.CLOSED else ApertureField.OPEN }
        val y = IntArray(edges.yEdgeCount) { if (edges.isYBoundary(it)) ApertureField.CLOSED else ApertureField.OPEN }
        return ApertureField(edges, x, y)
    }

    private fun emptyAir() = LongArray(grid.size * Species.COUNT)

    private fun put(grams: LongArray, tile: Int, species: Species, mass: Long) {
        grams[tile * Species.COUNT + species.ordinal] += mass
    }

    private fun massAt(grams: LongArray, tile: Int): Long {
        var sum = 0L
        for (s in Species.ALL) sum += grams[tile * Species.COUNT + s.ordinal]
        return sum
    }

    private fun total(values: LongArray): Long {
        var sum = 0L
        for (v in values) sum += v
        return sum
    }

    private fun assertNothingNegative(grams: LongArray, joules: LongArray? = null) {
        for (g in grams) assertTrue(g >= 0L, "a tile shed more than it held: $g")
        if (joules != null) for (j in joules) assertTrue(j >= 0L, "a tile shed more energy than it held: $j")
    }

    @Test
    fun `a sealed box conserves every gram and every joule`() {
        val grams = emptyAir()
        // Lumpy on purpose: one heavy corner, one light one, two species that do not mix evenly.
        put(grams, grid.index(0, 0), Species.Oxygen, 100_000L)
        put(grams, grid.index(5, 4), Species.Nitrogen, 37L)
        put(grams, grid.index(3, 2), Species.Oxygen, 4_321L)
        val joules = LongArray(grid.size)
        joules[grid.index(0, 0)] = 90_000_000L
        joules[grid.index(3, 2)] = 1_234_567L

        val startGrams = total(grams)
        val startJoules = total(joules)
        val apertures = sealed()

        repeat(120) { tick ->
            val step = diffuseFluid(edges, apertures, grams, joules)
            assertEquals(0L, step.ventedGrams, "a sealed box vented at tick $tick")
            assertEquals(0L, step.ventedJoules, "a sealed box vented energy at tick $tick")
            assertEquals(startGrams, total(grams), "mass drifted at tick $tick")
            assertEquals(startJoules, total(joules), "energy drifted at tick $tick")
            assertNothingNegative(grams, joules)
        }
    }

    @Test
    fun `a sealed box settles uniform, not biased toward the middle`() {
        // The fixed-divisor property, stated as the thing that would break without it: a corner tile
        // has two neighbours and a middle tile has four, so a per-degree divisor would leave the
        // middle holding about twice what the edges do at "steady state". That is the failure being
        // guarded, so the bound is set to discriminate *it* — a whole percent is orders of magnitude
        // away from the 2× a biased model produces, and far enough above the integer floor's residue
        // (a gram or two per tile; flat is a fixed point but so are its immediate neighbours) that
        // this is not a figure anyone will have to tune.
        val grams = emptyAir()
        val perTile = 8_000L
        put(grams, grid.index(0, 0), Species.Oxygen, perTile * grid.size)
        val apertures = sealed()

        repeat(400) { diffuseFluid(edges, apertures, grams, null) }

        val slack = perTile / 100
        for (tile in 0 until grid.size) {
            val held = massAt(grams, tile)
            assertTrue(
                held in (perTile - slack)..(perTile + slack),
                "tile ${grid.xOf(tile)},${grid.yOf(tile)} holds $held, not about $perTile",
            )
        }
    }

    @Test
    fun `temperature rides along with the mass that carries it`() {
        // Half the box hot, half cold, nothing free to leave: energy per gram has to end up the same
        // everywhere, because that is what "the joules go where the grams go" means over enough ticks.
        val grams = emptyAir()
        val joules = LongArray(grid.size)
        for (tile in 0 until grid.size) {
            put(grams, tile, Species.Oxygen, 1_000L)
            joules[tile] = if (grid.xOf(tile) < 3) 2_000_000L else 1_000_000L
        }
        val startJoules = total(joules)
        val apertures = sealed()

        repeat(400) { diffuseFluid(edges, apertures, grams, joules) }

        assertEquals(startJoules, total(joules))
        val hottest = joules.max()
        val coldest = joules.min()
        // Floor residue is the only thing allowed to separate them: a gram of air is a few joules per
        // kelvin, so a handful of joules is far below a kelvin and cannot be a gradient anyone sees.
        assertTrue(hottest - coldest <= grid.size, "heat stayed stratified: $coldest..$hottest")
    }

    @Test
    fun `a breach books exactly what left the grid`() {
        // Sealed but for one face on the rim: the room plus the ledger has to equal what was there.
        val x = IntArray(edges.xEdgeCount) { if (edges.isXBoundary(it)) ApertureField.CLOSED else ApertureField.OPEN }
        val y = IntArray(edges.yEdgeCount) { if (edges.isYBoundary(it)) ApertureField.CLOSED else ApertureField.OPEN }
        y[edges.upEdgeOf(grid.index(2, 0))] = ApertureField.OPEN
        val apertures = ApertureField(edges, x, y)

        val grams = emptyAir()
        val joules = LongArray(grid.size)
        for (tile in 0 until grid.size) {
            put(grams, tile, Species.Oxygen, 900L)
            put(grams, tile, Species.Nitrogen, 2_100L)
            joules[tile] = 3_000_000L
        }
        val startGrams = total(grams)
        val startJoules = total(joules)

        var ventedGrams = 0L
        var ventedJoules = 0L
        repeat(60) { tick ->
            val step = diffuseFluid(edges, apertures, grams, joules)
            ventedGrams += step.ventedGrams
            ventedJoules += step.ventedJoules
            assertEquals(startGrams, total(grams) + ventedGrams, "mass unaccounted for at tick $tick")
            assertEquals(startJoules, total(joules) + ventedJoules, "energy unaccounted for at tick $tick")
            assertNothingNegative(grams, joules)
        }
        assertTrue(ventedGrams > 0L, "a hole in the hull vented nothing")
        assertTrue(ventedJoules > 0L, "the gas that left took no heat with it")
    }

    @Test
    fun `a few grams beside vacuum stay put, and that is the accepted cost`() {
        // The price of dropping the remainder rotation, pinned so that it is a stated property rather
        // than a surprise: below the divisor a tile's share floors to zero across every face, so a
        // breached room drains to a trace and stops. Asserted, not lamented — if this ever starts
        // passing, the model gained a way to move trace gas and the doc comment needs revisiting.
        val x = IntArray(edges.xEdgeCount) { ApertureField.CLOSED }
        val y = IntArray(edges.yEdgeCount) { ApertureField.CLOSED }
        val leaking = grid.index(2, 0)
        y[edges.upEdgeOf(leaking)] = ApertureField.OPEN
        val apertures = ApertureField(edges, x, y)

        val grams = emptyAir()
        put(grams, leaking, Species.Oxygen, 3L)

        var vented = 0L
        repeat(SLOTS * 8) { vented += diffuseFluid(edges, apertures, grams, null).ventedGrams }

        assertEquals(3L, massAt(grams, leaking), "a trace below the divisor should not have moved")
        assertEquals(0L, vented, "and so nothing should have left the grid")
    }

    @Test
    fun `heat drains in step with the gas carrying it`() {
        // Telescoping the joule split across the faces, stated as what it is for: the energy left in
        // a draining cell stays in proportion to the mass left in it, so the gas never separates from
        // its own heat. It used to be phrased as "an emptied tile keeps no joules", which stopped
        // being expressible when the remainder began staying home — nothing empties completely now.
        val x = IntArray(edges.xEdgeCount) { ApertureField.CLOSED }
        val y = IntArray(edges.yEdgeCount) { ApertureField.CLOSED }
        val source = grid.index(2, 0)
        y[edges.upEdgeOf(source)] = ApertureField.OPEN
        val apertures = ApertureField(edges, x, y)

        val grams = emptyAir()
        val startMass = 5_000L
        put(grams, source, Species.Oxygen, startMass)
        val joules = LongArray(grid.size)
        val startJoules = 40_000_000L
        joules[source] = startJoules

        var ventedJoules = 0L
        repeat(300) { ventedJoules += diffuseFluid(edges, apertures, grams, joules).ventedJoules }

        val left = massAt(grams, source)
        assertTrue(left in 1 until startMass, "the fixture did not drain: $left of $startMass")
        assertEquals(startJoules, joules[source] + ventedJoules, "energy went missing on the way out")
        // Same joules per gram as it started with — cross-multiplied so integer division cannot
        // manufacture the agreement. Slack of one gram's worth: that is the floor, and nothing more.
        val perGram = startJoules / startMass
        assertTrue(
            joules[source] in (left - 1) * perGram..(left + 1) * perGram,
            "the gas and its heat came apart: ${joules[source]} J on $left g, against $perGram J/g",
        )
    }

    @Test
    fun `a shut face passes nothing and a narrow one passes less than a wide one`() {
        // Aperture is an area, and the model has to keep treating it as one — a valve half open is
        // the same mechanism as a wall, not a special case beside it.
        fun crossedIn(aperture: Int): Long {
            val x = IntArray(edges.xEdgeCount) { ApertureField.CLOSED }
            val y = IntArray(edges.yEdgeCount) { ApertureField.CLOSED }
            x[edges.rightEdgeOf(grid.index(1, 2))] = aperture
            val grams = emptyAir()
            put(grams, grid.index(1, 2), Species.Oxygen, 100_000L)
            diffuseFluid(edges, ApertureField(edges, x, y), grams, null)
            return massAt(grams, grid.index(2, 2))
        }

        assertEquals(0L, crossedIn(ApertureField.CLOSED))
        val half = crossedIn(ApertureField.OPEN / 2)
        val whole = crossedIn(ApertureField.OPEN)
        assertTrue(half > 0L, "a half-open valve passed nothing")
        assertEquals(whole / 2, half, "a half-open face should pass half of what an open one does")
    }
}
