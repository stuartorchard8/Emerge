package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.applyPressureForce
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.tileMass
import org.emerge.demo.outofspace.world.tilePressure
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gas pushing itself down its own pressure gradient — the term that gives the sim a speed of
 * sound.
 *
 * Worth stating what its absence looked like, because it was not a slow version of the right
 * behaviour. A breached vessel measured `dAir = 0` for two hundred consecutive ticks: the pressure
 * field reached the momentum field only through the projection's divergence target, which is zero
 * wherever a cell matches its neighbours, so a uniform room never learned there was a hole in it and
 * vented *nothing*. The two properties below are the ones that failure violated and the ones any
 * rewrite has to keep: a gradient must produce motion, and a uniform room must not.
 */
class PressureForceTest {

    /** Roughly one tile's worth of hydrogen as a liquid at 25 K, in sim mass units. */
    private val TILE_OF_LIQUID_HYDROGEN = 64_000_000_000L


    private class Room(val w: Int, val h: Int) {
        val grid = Grid(w + 2, h + 2)
        val edges = EdgeGrid(grid)
        val apertures: ApertureField
        val masses = MassArray(grid.size)
        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)

        init {
            val deck = DeckArray(grid)
            for (x in 1..w) { deck += Hull(grid.tile(x, 1)); deck += Hull(grid.tile(x, h)) }
            for (y in 2 until h) { deck += Hull(grid.tile(1, y)); deck += Hull(grid.tile(w, y)) }
            apertures = ApertureField.derive(edges, StructureMap.derive(grid, deck))
            for (x in 2 until w) for (y in 2 until h) air(grid.tile(x, y))
        }

        fun air(tile: TileIndex, share: Long = 1L) {
            for (f in Fluid.ALL) masses[MassIndex(tile, f)] = Stuff.AMBIENT_AIR[f.species] * share
        }

        fun empty(tile: TileIndex) {
            for (f in Fluid.ALL) masses[MassIndex(tile, f)] = 0L
        }

        fun run() = applyPressureForce(
            edges, apertures, mx, my,
            tileMass(grid.size, masses),
            tilePressure(grid.size, masses),
        )

        fun totalX(): Long = mx.sum()
        fun totalY(): Long = my.sum()
    }

    @Test
    fun `uniform air feels no force at all`() {
        val room = Room(8, 8)
        room.run()

        // Not "small" — exactly zero. A room that hums is a room whose air slowly relocates, and the
        // rest state is the one thing here that has to be perfect rather than close.
        assertTrue(room.mx.all { it == 0L }, "a still room gained x-momentum")
        assertTrue(room.my.all { it == 0L }, "a still room gained y-momentum")
    }

    @Test
    fun `a sealed vessel cannot push itself however its pressure is arranged`() {
        val room = Room(8, 8)
        // A thoroughly uneven interior: a dense knot in one corner, a thin patch in the other.
        room.air(room.grid.tile(3, 3), share = 3)
        room.air(room.grid.tile(4, 3), share = 2)
        room.air(room.grid.tile(6, 6), share = 0)

        val result = room.run()

        // The impulses telescope along every row and column, so whatever the gas does internally it
        // does to itself. This is the guarantee the whole thrust ledger rests on.
        assertEquals(0L, room.totalX(), "sealed vessel gained net x-momentum")
        assertEquals(0L, room.totalY(), "sealed vessel gained net y-momentum")
        // And the hull reactions cancel too: nothing outside is pushing back.
        assertEquals(0L, result.vesselX, "sealed vessel was pushed in x")
        assertEquals(0L, result.vesselY, "sealed vessel was pushed in y")
    }

    /**
     * ⛔ **PARKED, and red — the angular twin of the test above, which is the blind spot itself.**
     *
     * The hull reactions telescope to zero as a *force* and do not as a **torque**: equal pushes on
     * opposite bulkheads cancel when you add them up and add when you cross them with where they
     * act. So the test above passes on a vessel that is quietly spinning itself, and passed for the
     * fifteen days between the transport solver being cut and `torqueAbout` being wired in here.
     *
     * Returned by the step that stops booking the hull for exchanges that never happen — reaction
     * only where mass genuinely crosses the vessel boundary. See [VesselState.angularBalance].
     */
    @Ignore
    @Test
    fun `a sealed vessel cannot spin itself either`() {
        val room = Room(8, 8)
        room.air(room.grid.tile(3, 3), share = 3)
        room.air(room.grid.tile(4, 3), share = 2)
        room.air(room.grid.tile(6, 6), share = 0)

        val result = room.run()

        assertEquals(0L, result.vesselX, "the linear half moved, so this is not the angular case")
        assertEquals(0L, result.torque, "a sealed vessel twisted itself")
    }

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

    @Test
    fun `gas beside a vacuum is pushed into it`() {
        val room = Room(8, 8)
        for (y in 2 until 8) room.empty(room.grid.tile(6, y))

        room.run()

        // The face between the last full column and the empty one must be moving toward the empty
        // side. Positive x is toward +x, and the vacuum is at x = 6.
        val face = room.edges.xEdge(6, 4)
        assertTrue(room.mx[face] > 0L, "gas was not pushed toward the vacuum: ${room.mx[face]}")
    }

    @Test
    fun `a breach pushes the vessel away from the hole`() {
        val room = Room(8, 8)

        // One hole in the left wall, and nothing else changed. The asymmetry is the whole point: a
        // sealed room's wall terms cancel exactly (the test above), so whatever survives here is
        // attributable to the single face that is no longer there.
        val x = room.apertures.copyX()
        val y = room.apertures.copyY()
        x[room.edges.xEdge(2, 4)] = ApertureField.OPEN
        val breached = ApertureField(room.edges, x, y)

        val result = applyPressureForce(
            room.edges, breached, room.mx, room.my,
            tileMass(room.grid.size, room.masses),
            tilePressure(room.grid.size, room.masses),
        )

        // Gas heads for the hole, which is toward -x.
        assertTrue(room.totalX() < 0L, "gas did not head for the breach: ${room.totalX()}")
        // And the ship goes the other way. This is the entire rocket, in its smallest form: the wall
        // term that used to cancel the one on the far side has been replaced by an opening.
        assertTrue(result.vesselX > 0L, "vessel was not pushed away from the breach: ${result.vesselX}")
    }
}
