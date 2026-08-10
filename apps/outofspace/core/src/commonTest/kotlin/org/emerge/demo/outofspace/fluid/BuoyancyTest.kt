package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.fluid.ApertureField
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.applyBuoyancy
import org.emerge.demo.outofspace.world.fluid.tileMass
import org.emerge.demo.outofspace.world.fluid.tilePressure
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gravity on the gas, and the sorting that used to be done by hand.
 *
 * This is the pass that lets `stratifyColumns` be deleted rather than ported. The old function
 * swapped heavy gas downward a pair at a time and could only work when "down" was a grid axis; this
 * one is a force along whatever vector gravity is, so the sorting is a consequence rather than a
 * rule. The test that matters most is the boring one — uniform air must feel *nothing*, or the whole
 * atmosphere slowly slides to the floor.
 */
class BuoyancyTest {

    private class Room(w: Int, h: Int) {
        val grid = Grid(w + 2, h + 2)
        val edges = EdgeGrid(grid)
        val apertures: ApertureField
        val grams = LongArray(grid.size * Species.COUNT)
        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)

        init {
            val machines = arrayOfNulls<Machine>(grid.size)
            for (x in 1..w) { machines[grid.index(x, 1)] = Hull(); machines[grid.index(x, h)] = Hull() }
            for (y in 1..h) { machines[grid.index(1, y)] = Hull(); machines[grid.index(w, y)] = Hull() }
            apertures = ApertureField.derive(edges, StructureMap.derive(grid, machines.toList()))
            for (x in 2 until w) for (y in 2 until h) air(grid.index(x, y))
        }

        fun air(tile: Int) {
            for (s in Species.ALL) grams[tile * Species.COUNT + s.ordinal] = AirField.AMBIENT_AIR[s]
        }

        /** Replace a tile's air with a single gas, at the same pressure as everything else. */
        fun pureGas(tile: Int, species: Species) {
            val target = tilePressure(grid.size, grams)[grid.index(3, 3)]
            for (s in Species.ALL) grams[tile * Species.COUNT + s.ordinal] = 0L
            grams[tile * Species.COUNT + species.ordinal] = target * species.molarMass / 1000L
        }

        fun run(gravity: Frac2 = DOWN) = applyBuoyancy(
            edges, apertures, mx, my,
            tileMass(grid.size, grams),
            tilePressure(grid.size, grams),
            gravity,
        )

        companion object {
            val DOWN = Frac2(Frac(0L, 1), Frac(1L, 1))
            val NONE = Frac2(Frac(0L), Frac(0L))
        }
    }

    @Test
    fun `uniform air does not sink`() {
        val room = Room(10, 8)
        room.run()

        for (m in room.my) assertEquals(0L, m, "ordinary air weighs exactly what its pressure implies")
        for (m in room.mx) assertEquals(0L, m)
    }

    @Test
    fun `carbon dioxide sinks`() {
        val room = Room(10, 8)
        val heavy = room.grid.index(5, 4)
        room.pureGas(heavy, Species.CarbonDioxide)

        room.run()

        // +y is down. A face carrying the heavy parcel should be pushed toward the floor.
        assertTrue(room.my[room.edges.downEdgeOf(heavy)] > 0L, "should be pulled down")
        assertTrue(room.my[room.edges.upEdgeOf(heavy)] > 0L, "on both of its horizontal faces")
    }

    @Test
    fun `nitrogen rises, because it is lighter than the air it sits in`() {
        val room = Room(10, 8)
        val light = room.grid.index(5, 4)
        room.pureGas(light, Species.Nitrogen)

        room.run()

        assertTrue(room.my[room.edges.downEdgeOf(light)] < 0L, "lighter than ambient means upward")
    }

    @Test
    fun `no gravity means no settling`() {
        val room = Room(10, 8)
        room.pureGas(room.grid.index(5, 4), Species.CarbonDioxide)

        val result = room.run(Room.NONE)

        for (m in room.my) assertEquals(0L, m)
        assertEquals(0L, result.vesselY)
    }

    @Test
    fun `gravity along x sorts sideways, with no vertical component at all`() {
        val room = Room(10, 8)
        room.pureGas(room.grid.index(5, 4), Species.CarbonDioxide)

        room.run(Frac2(Frac(1L, 1), Frac(0L)))

        for (m in room.my) assertEquals(0L, m, "gravity with no y component moves nothing vertically")
        assertTrue(room.mx.any { it != 0L }, "but it should move things horizontally")
    }

    @Test
    fun `a bulkhead is not pushed through`() {
        val room = Room(10, 8)
        room.pureGas(room.grid.index(2, 2), Species.CarbonDioxide)

        room.run()

        val wall = room.edges.upEdgeOf(room.grid.index(2, 2))
        assertEquals(0L, room.my[wall], "the face against the ceiling hull must stay still")
    }

    @Test
    fun `what the gas gains, the deck holding it up loses`() {
        val room = Room(10, 8)
        room.pureGas(room.grid.index(5, 4), Species.CarbonDioxide)
        room.pureGas(room.grid.index(6, 4), Species.CarbonDioxide)

        val result = room.run()

        assertEquals(-room.my.sum(), result.vesselY, "the reaction must match what was applied")
        assertTrue(result.vesselY != 0L, "and there should be something to react to")
    }
}
