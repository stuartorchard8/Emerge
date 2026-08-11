package org.emerge.demo.fluidlab.fluid

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.world.AirField
import org.emerge.demo.fluidlab.world.Grid
import org.emerge.demo.fluidlab.world.Hull
import org.emerge.demo.fluidlab.world.Machine
import org.emerge.demo.fluidlab.world.StructureMap
import org.emerge.demo.fluidlab.world.fluid.ApertureField
import org.emerge.demo.fluidlab.world.fluid.EdgeGrid
import org.emerge.demo.fluidlab.world.fluid.MomentumField
import org.emerge.demo.fluidlab.world.fluid.MAX_SUB_STEPS
import org.emerge.demo.fluidlab.world.fluid.applyPressureForce
import org.emerge.demo.fluidlab.world.fluid.stepFluid
import org.emerge.demo.fluidlab.world.fluid.tileMass
import org.emerge.demo.fluidlab.world.fluid.tilePressure
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.math.abs
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

    private class Room(val w: Int, val h: Int) {
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

        fun air(tile: Int, share: Long = 1L) {
            for (s in Species.ALL) grams[tile * Species.COUNT + s.ordinal] = AirField.AMBIENT_AIR[s] * share
        }

        fun empty(tile: Int) {
            for (s in Species.ALL) grams[tile * Species.COUNT + s.ordinal] = 0L
        }

        fun run() = applyPressureForce(
            edges, apertures, mx, my,
            tileMass(grid.size, grams),
            tilePressure(grid.size, grams),
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
        room.air(room.grid.index(3, 3), share = 3)
        room.air(room.grid.index(4, 3), share = 2)
        room.air(room.grid.index(6, 6), share = 0)

        val result = room.run()

        // The impulses telescope along every row and column, so whatever the gas does internally it
        // does to itself. This is the guarantee the whole thrust ledger rests on.
        assertEquals(0L, room.totalX(), "sealed vessel gained net x-momentum")
        assertEquals(0L, room.totalY(), "sealed vessel gained net y-momentum")
        // And the hull reactions cancel too: nothing outside is pushing back.
        assertEquals(0L, result.vesselX, "sealed vessel was pushed in x")
        assertEquals(0L, result.vesselY, "sealed vessel was pushed in y")
    }

    @Test
    fun `gas beside a vacuum is pushed into it`() {
        val room = Room(8, 8)
        for (y in 2 until 8) room.empty(room.grid.index(6, y))

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
            tileMass(room.grid.size, room.grams),
            tilePressure(room.grid.size, room.grams),
        )

        // Gas heads for the hole, which is toward -x.
        assertTrue(room.totalX() < 0L, "gas did not head for the breach: ${room.totalX()}")
        // And the ship goes the other way. This is the entire rocket, in its smallest form: the wall
        // term that used to cancel the one on the far side has been replaced by an opening.
        assertTrue(result.vesselX > 0L, "vessel was not pushed away from the breach: ${result.vesselX}")
    }

    /**
     * The transport never steps over a tile — which is a claim about the *step*, not about this pass.
     *
     * This used to assert [MomentumField.isCflSafe] on the momentum field directly, because
     * [applyPressureForce] held every face to half a tile per tick with a hard clamp. Both the clamp
     * and that assertion have gone, and the reason is worth keeping: the clamp was not achieving this.
     * Measured on a breached hull with the clamp in place, the field reached three tiles per tick and
     * broke CFL on ninety ticks out of a hundred and twenty, because this pass is not the last one to
     * touch a face — [project] and [advectMomentum] both add momentum after it. Bounding one term of a
     * sum is not bounding the sum, and the assertion passed only because it was made on a fixture
     * where this pass really was the only one running.
     *
     * So the gas is now allowed to go as fast as the pressure says it does, and [stepFluid] cuts the
     * tick into as many pieces as the fastest face needs. What has to hold is that the distance
     * covered in one piece is under a tile, which is the actual CFL condition and is what this now
     * measures — through the real step, so that every pass that touches momentum is included.
     */
    @Test
    fun `the transport never steps over a tile, however fast the gas goes`() {
        val room = Room(8, 8)
        // A near-vacuum next to full air is the worst case: the impulse is density-independent, so the
        // velocity it implies on a nearly empty face is unbounded. This is the fixture that used to
        // measure eleven tiles per tick before anything bounded it at all.
        for (y in 2 until 8) for (x in 5 until 8) {
            for (s in Species.ALL) {
                room.grams[room.grid.index(x, y) * Species.COUNT + s.ordinal] = AirField.AMBIENT_AIR[s] / 500L
            }
        }

        // Repeatedly, because the failure this guards against is accumulation: a bounded push every
        // tick still runs away over dozens of ticks with only drag's thirty-second to bleed it off.
        var worst = 0
        repeat(50) {
            val step = stepFluid(
                room.edges, room.apertures, room.grams, room.mx, room.my, Frac2.zero,
            )
            if (step.subSteps > worst) worst = step.subSteps

            val tileGrams = tileMass(room.grid.size, room.grams)
            val field = MomentumField.of(room.edges, room.mx, room.my)
            for (e in 0 until room.edges.xEdgeCount) {
                val perStep = abs(field.velocityX(e, tileGrams).raw) / step.subSteps
                assertTrue(
                    perStep < MomentumField.SPEED_LIMIT_RAW,
                    "a face moved $perStep of a tile in one of ${step.subSteps} pieces",
                )
            }
            for (e in 0 until room.edges.yEdgeCount) {
                val perStep = abs(field.velocityY(e, tileGrams).raw) / step.subSteps
                assertTrue(
                    perStep < MomentumField.SPEED_LIMIT_RAW,
                    "a face moved $perStep of a tile in one of ${step.subSteps} pieces",
                )
            }
        }

        // And the bound was not what saved it. If this ever trips, the sub-stepping stopped keeping up
        // and the assertions above are passing on a clamp rather than on a solution.
        assertTrue(worst < MAX_SUB_STEPS, "the tick had to be cut into $worst pieces, which is the cap")
    }
}
