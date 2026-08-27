package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.tilePressure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a cell's contents press with.
 *
 * ⛔ **Pressure no longer pushes the hull at all**, and this file is what is left of the one that
 * tested that it did. Gas leaning on a bulkhead it is sealed behind is internal to ship-plus-air;
 * only mass that leaves the vessel can move it, which [org.emerge.demo.outofspace.world.diffuseFluid]
 * books at the rim. What the pressure field still decides is where gas *goes*, and the law below is
 * the one that was wrong about it.
 */
class TilePressureTest {

    /** Roughly one tile's worth of hydrogen as a liquid at 25 K, in sim mass units. */
    private val TILE_OF_LIQUID_HYDROGEN = 64_000_000_000L

    /**
     * **Past saturation, more of a species is more puddle and not more pressure.**
     *
     * The law a condensed phase obeys, and the one the pressure field was breaking. `tilePressure`
     * used to charge a species' *whole* mass to the equation of state, so a tile that had collected
     * more of something than could be vapour at its temperature came out on Peng-Robinson's
     * compressed-liquid branch — where the pressure climbs steeply and without bound, because that
     * branch is answering "what if I squeeze a liquid", which is not what happened.
     *
     * ⛔ **And nothing could relieve it**, because [org.emerge.demo.outofspace.world.diffuseFluid]
     * moves `vapourMass` and correctly leaves condensate where it lies. Two functions disagreeing
     * about what a phase is: one said the puddle pushes, the other said the puddle cannot move.
     * Measured on a live save — one tile at **19.3x** liquid hydrogen's density reading 104,400,822,
     * which was **99.87% of the entire pressure field of the ship**, twenty tiles off the centre of
     * mass and spinning it up for free.
     *
     * Stated as a ratio rather than a magic number: whatever the saturation pressure of hydrogen at
     * 25 K is, twenty times the mass must not exert more of it than four times does.
     */
    @Test
    fun `piling more condensate into a tile does not raise its pressure`() {
        val hydrogen = Fluid.ALL.first { it.species == Species.Hydrogen }
        val grid = Grid(3, 3)
        val tile = grid.tile(1, 1)
        val cold = IntArray(grid.size) { 25 }

        fun pressureWith(mass: Long): Long {
            val masses = MassArray(grid.size)
            masses[MassIndex(tile, hydrogen)] = mass
            return tilePressure(grid.size, masses, cold)[tile.index]
        }

        // A tile of liquid hydrogen at 25 K is about 6.4 kg, so all three of these are past it.
        val four = pressureWith(4L * TILE_OF_LIQUID_HYDROGEN)
        val twenty = pressureWith(20L * TILE_OF_LIQUID_HYDROGEN)
        val hundred = pressureWith(100L * TILE_OF_LIQUID_HYDROGEN)

        assertTrue(four > 0L, "a tile with hydrogen in it exerts nothing at all: $four")
        assertEquals(four, twenty, "five times the puddle raised the pressure")
        assertEquals(four, hundred, "twenty-five times the puddle raised the pressure")
    }
}
