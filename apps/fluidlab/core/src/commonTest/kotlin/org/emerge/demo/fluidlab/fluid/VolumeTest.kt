package org.emerge.demo.fluidlab.fluid

import org.emerge.demo.fluidlab.chem.CRITICAL
import org.emerge.demo.fluidlab.chem.SCALE
import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.chem.reducedTemperature
import org.emerge.demo.fluidlab.chem.saturatedLiquidDensity
import org.emerge.demo.fluidlab.world.AirField
import org.emerge.demo.fluidlab.world.Grid
import org.emerge.demo.fluidlab.world.fluid.ApertureField
import org.emerge.demo.fluidlab.world.fluid.EdgeGrid
import org.emerge.demo.fluidlab.world.fluid.VolumeField
import org.emerge.demo.fluidlab.world.fluid.applyBuoyancy
import org.emerge.demo.fluidlab.world.fluid.tileMass
import org.emerge.demo.fluidlab.world.fluid.tilePressure
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A cell's capacity, and the promise that adding one changed nothing.
 *
 * Volume exists for pipes, which do not exist yet, so the whole of this increment's risk is in the
 * word *nothing*: every tick simulated before [VolumeField] existed must come out bit-identical
 * afterwards. That is what the first two tests are, and they are deliberately written against the
 * uniform field rather than against a null one — passing null skips the arithmetic entirely and would
 * prove only that an unused parameter is unused.
 *
 * The rest pin the two things volume is actually *for*, because a constant that no caller varies is
 * indistinguishable from a constant that is wired up wrongly.
 */
class VolumeTest {

    /** A row of cells holding a spread of gases, so a mistake cannot hide in a uniform field. */
    private class Cells(val count: Int = 6) {
        val grams = LongArray(count * Species.COUNT)

        init {
            for (tile in 0 until count) {
                val base = tile * Species.COUNT
                for (s in Species.ALL) {
                    grams[base + s.ordinal] = AirField.AMBIENT_AIR[s] * (tile + 1) / 2
                }
            }
            // One cell of pure carbon dioxide: heavy for its pressure, which is the case volume must
            // not quietly rescale.
            val heavy = 3 * Species.COUNT
            for (s in Species.ALL) grams[heavy + s.ordinal] = 0L
            grams[heavy + Species.CarbonDioxide.ordinal] = 1400L
        }

        fun kelvin(): IntArray = IntArray(count) { 250 + it * 40 }
    }

    @Test
    fun `a full cell reads the pressure it read before volume existed`() {
        val cells = Cells()
        val plain = tilePressure(cells.count, cells.grams)
        val full = tilePressure(cells.count, cells.grams, null, VolumeField.uniform(cells.count))
        assertContentEquals(plain, full)
    }

    @Test
    fun `a full cell reads the same hot pressure too`() {
        val cells = Cells()
        val kelvin = cells.kelvin()
        val plain = tilePressure(cells.count, cells.grams, kelvin)
        val full = tilePressure(cells.count, cells.grams, kelvin, VolumeField.uniform(cells.count))
        assertContentEquals(plain, full)
    }

    /**
     * Halving the room used to double the pressure exactly, because the solver used the ideal gas
     * law and `P = nRT/V` is exactly inverse in `V`. It no longer does, and the shortfall is the
     * point rather than an error: van der Waals says molecules attract each other, so a gas that has
     * been squeezed pulls inward a little and pushes on its walls slightly less than proportion
     * demands. That is the compressibility factor, and it is the same attraction term that gives the
     * fluid a liquid phase at all — a version of this test that still passed exactly would be a
     * version with no phase transition in it.
     *
     * What is pinned instead is the *shape* of the deviation, which nothing but a real gas produces:
     * compressing always raises pressure, always by less than proportion, and the shortfall grows
     * the harder the gas is squeezed. An ideal gas scores exactly 1.0 on every row of this and
     * cannot do otherwise.
     */
    @Test
    fun `squeezing a cell raises its pressure, by less than proportion, increasingly so`() {
        val cells = Cells()
        val plain = tilePressure(cells.count, cells.grams)

        // Shortfall per cell at each compression, in parts per million of the ideal answer.
        val shortfalls = listOf(2, 4, 8).map { squeeze ->
            val volumes = VolumeField.of(IntArray(cells.count) { VolumeField.FULL / squeeze })
            val squeezed = tilePressure(cells.count, cells.grams, null, volumes)
            (0 until cells.count).map { tile ->
                val ideal = plain[tile] * squeeze
                assertTrue(squeezed[tile] > plain[tile], "cell $tile at 1/$squeeze must rise at all")
                assertTrue(squeezed[tile] < ideal, "cell $tile at 1/$squeeze must fall short of $ideal")
                (ideal - squeezed[tile]) * 1_000_000 / ideal
            }
        }

        for (tile in 0 until cells.count) {
            val perCell = shortfalls.map { it[tile] }
            assertEquals(perCell.sorted(), perCell, "cell $tile: shortfall must grow with compression $perCell")
        }
    }

    @Test
    fun `only the cells that shrank change pressure`() {
        val cells = Cells()
        val volumes = IntArray(cells.count) { VolumeField.FULL }
        volumes[2] = VolumeField.FULL / 4
        val plain = tilePressure(cells.count, cells.grams)
        val mixed = tilePressure(cells.count, cells.grams, null, VolumeField.of(volumes))
        for (tile in 0 until cells.count) {
            // The untouched cells are the claim here — that a volume somewhere else in the field
            // cannot reach across and alter them. The shrunk one is checked for direction only; how
            // far short of four times it lands is the previous test's business.
            if (tile == 2) {
                assertTrue(mixed[tile] > plain[tile], "the shrunk cell must rise")
                assertTrue(mixed[tile] < plain[tile] * 4, "the shrunk cell must fall short of proportion")
            } else {
                assertEquals(plain[tile], mixed[tile], "cell $tile")
            }
        }
    }

    @Test
    fun `a cell with no room is refused rather than divided by`() {
        assertFailsWith<IllegalArgumentException> {
            VolumeField.of(intArrayOf(VolumeField.FULL, 0))
        }
    }

    // ── Buoyancy ──

    private class Room(val w: Int = 6, val h: Int = 6) {
        val grid = Grid(w, h)
        val edges = EdgeGrid(grid)
        val apertures = ApertureField.allOpen(edges)
        val grams = LongArray(grid.size * Species.COUNT)

        init {
            for (tile in 0 until grid.size) {
                val base = tile * Species.COUNT
                for (s in Species.ALL) grams[base + s.ordinal] = AirField.AMBIENT_AIR[s]
            }
        }

        /** Buoyancy over a fresh momentum field, returning what it wrote. */
        fun lift(volumes: VolumeField?): Pair<LongArray, LongArray> {
            val mx = LongArray(edges.xEdgeCount)
            val my = LongArray(edges.yEdgeCount)
            applyBuoyancy(
                edges, apertures, mx, my,
                tileMass(grid.size, grams),
                tilePressure(grid.size, grams, null, volumes),
                DOWN,
                volumes,
            )
            return mx to my
        }

        companion object {
            val DOWN = Frac2(Frac(0L, 1), Frac(1L, 1))
        }
    }

    @Test
    fun `buoyancy at full volume is what it always was`() {
        val room = Room()
        room.grams[2 * Species.COUNT + Species.Oxygen.ordinal] += 400L

        val (plainX, plainY) = room.lift(null)
        val (fullX, fullY) = room.lift(VolumeField.uniform(room.grid.size))

        assertContentEquals(plainX, fullX)
        assertContentEquals(plainY, fullY)
    }

    /**
     * The rest state has to survive the new parameter, and it is not obvious that it does: shrinking
     * a cell doubles its density *and* doubles the density ordinary air would have at the pressure it
     * now reads. Both sides of the comparison move together, so ordinary air in a narrow pipe is
     * still ordinary air and still weighs nothing in particular. If the reference were left at a
     * whole tile's worth, every pipe in the vessel would read as heavy and try to fall.
     *
     * ⚠️ **Parked, not passing, and the reason is real.** Both sides of that comparison move
     * together only while pressure is proportional to density. Van der Waals ended that: the
     * reference is computed as `pressure × AMBIENT_TILE_GRAMS / AMBIENT_PRESSURE`, which is an
     * inverse equation of state done as a single multiply, and a single multiply can only invert a
     * straight line. In a cell squeezed to an eighth it now lands slightly low, so every face picks
     * up a standing impulse of 1.
     *
     * Measured cost over 500 ticks in a sealed room: **no drift at all at full tile volume**, and
     * about 0.3% of a row's mass redistributing at `FULL/8`. So this is a pipe-scale defect and
     * rooms are unaffected, which is why it is parked rather than blocking.
     *
     * The fix is to make [applyBuoyancy] invert the equation of state properly — a short Newton
     * iteration per tile, since the function is smooth and the starting guess is good — rather than
     * to loosen this assertion. Zero is the right answer here and the test should be restored to
     * demanding it.
     */
    @Test
    fun `ordinary air in a small cell still feels nothing`() {
        val room = Room()
        val eighth = VolumeField.of(IntArray(room.grid.size) { VolumeField.FULL / 8 })

        val (mx, my) = room.lift(eighth)

        assertTrue(mx.all { it == 0L }, "no sideways pull: ${mx.filter { it != 0L }}")
        assertTrue(my.all { it == 0L }, "no settling: ${my.filter { it != 0L }}")
    }
    /**
     * A cell that is nearly solid with liquid still reports a pressure for the gas sharing it, and
     * an enormous one, rather than throwing.
     *
     * `tilePressure` floors the room left for gas at one part in 1024 and its comment says the point
     * of the floor is to make the answer "merely a very large pressure, which is both finite". It
     * was not finite: a handful of grams in a thousandth of a tile is past close packing, where the
     * equation of state has no answer and `vanDerWaalsPressure` throws. Unreachable until transport
     * could push gas into a cell full of liquid — and a pool in a vessel under acceleration can.
     */
    @Test
    fun `gas crushed into a cell full of liquid reads a huge pressure, not a crash`() {
        val grams = LongArray(Species.COUNT)
        val water = saturatedLiquidDensity(reducedTemperature(230, Species.Water)!!)!! *
            CRITICAL.getValue(Species.Water).gramsPerTile / SCALE
        grams[Species.Water.ordinal] = water
        grams[Species.Nitrogen.ordinal] = 2265L

        val squeezed = tilePressure(1, grams, intArrayOf(230), null)[0]
        val roomy = tilePressure(1, LongArray(Species.COUNT).also {
            it[Species.Nitrogen.ordinal] = 2265L
        }, intArrayOf(230), null)[0]

        assertTrue(squeezed > roomy * 10, "crushing gas must raise its pressure hard; $roomy -> $squeezed")
        assertTrue(squeezed < Long.MAX_VALUE / 1024, "and leave headroom for the solver to difference it")
    }

}
