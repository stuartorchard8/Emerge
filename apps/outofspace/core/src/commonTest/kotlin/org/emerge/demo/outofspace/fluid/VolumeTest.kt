package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.fluid.ApertureField
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.VolumeField
import org.emerge.demo.outofspace.world.fluid.applyBuoyancy
import org.emerge.demo.outofspace.world.fluid.tileMass
import org.emerge.demo.outofspace.world.fluid.tilePressure
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
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
                for (s in Species.GASES) {
                    grams[base + s.ordinal] = AirField.AMBIENT_AIR[s] * (tile + 1) / 2
                }
            }
            // One cell of pure carbon dioxide: heavy for its pressure, which is the case volume must
            // not quietly rescale.
            val heavy = 3 * Species.COUNT
            for (s in Species.GASES) grams[heavy + s.ordinal] = 0L
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

    @Test
    fun `halving the room doubles the pressure`() {
        val cells = Cells()
        val half = VolumeField.of(IntArray(cells.count) { VolumeField.FULL / 2 })
        val plain = tilePressure(cells.count, cells.grams)
        val squeezed = tilePressure(cells.count, cells.grams, null, half)
        for (tile in 0 until cells.count) {
            assertEquals(plain[tile] * 2, squeezed[tile], "cell $tile")
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
            val expected = if (tile == 2) plain[tile] * 4 else plain[tile]
            assertEquals(expected, mixed[tile], "cell $tile")
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
                for (s in Species.GASES) grams[base + s.ordinal] = AirField.AMBIENT_AIR[s]
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
     */
    @Test
    fun `ordinary air in a small cell still feels nothing`() {
        val room = Room()
        val eighth = VolumeField.of(IntArray(room.grid.size) { VolumeField.FULL / 8 })

        val (mx, my) = room.lift(eighth)

        assertTrue(mx.all { it == 0L }, "no sideways pull: ${mx.filter { it != 0L }}")
        assertTrue(my.all { it == 0L }, "no settling: ${my.filter { it != 0L }}")
    }
}
