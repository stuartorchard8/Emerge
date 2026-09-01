package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.rotScale
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 1 of `PLAN_rigid_bodies.md`: the vessel frame is deleted and everything is stored in world
 * coordinates, so there has to be one transform between the world and a body's own grid that both
 * sides agree on exactly.
 *
 * Two things are being guarded here and only one of them is the arithmetic. The other is the range:
 * this exists so that coordinates can be **world** coordinates, which are large, and the obvious
 * implementation of a rotation overflows a `Long` four tiles from the origin. A test that only ever
 * transformed points near the origin would pass on an implementation that fails everywhere a ship
 * has actually been.
 */
class PoseTest {

    // ── The multiply everything is made of ────────────────────────────────────

    /**
     * **The range test, and the reason [rotScale] exists.**
     *
     * `value * fracRaw / FRAC_ONE` written the obvious way overflows past 4.3 tiles. Here the point
     * is 4000 tiles out and turned by a full sweep of angles; every result must stay on the circle
     * it started on. A wrapped intermediate would fling it somewhere absurd, and — this being the
     * failure mode the codebase keeps meeting — it would do so silently.
     */
    @Test
    fun `a rotation holds its radius four thousand tiles from the origin`() {
        val far = 4_000L * Flight.PER_TILE
        // Squaring the radius directly would itself overflow, which is the same hazard one level up,
        // so the comparison is made in tiles.
        val expected = 4_000.0

        for (angle in ANGLES) {
            val p = Pose(0L, 0L, angle)
            val wx = p.toWorldX(far, 0L)
            val wy = p.toWorldY(far, 0L)
            val radius = kotlin.math.sqrt(
                (wx.toDouble() / Flight.PER_TILE) * (wx.toDouble() / Flight.PER_TILE) +
                    (wy.toDouble() / Flight.PER_TILE) * (wy.toDouble() / Flight.PER_TILE),
            )
            assertTrue(
                kotlin.math.abs(radius - expected) < 0.01,
                "at ${angle.raw} a point 4000 tiles out landed at radius $radius",
            )
        }
    }

    /** Signs, both of them, and the two exact values a fraction has. */
    @Test
    fun `rotScale carries the sign of the product`() {
        val one = Flight.FRAC_ONE
        assertEquals(1_000L, rotScale(1_000L, one), "one times a thing is the thing")
        assertEquals(-1_000L, rotScale(1_000L, -one), "a negative fraction flips it")
        assertEquals(-1_000L, rotScale(-1_000L, one), "so does a negative value")
        assertEquals(1_000L, rotScale(-1_000L, -one), "and two negatives do not")
        assertEquals(0L, rotScale(0L, one), "nothing scaled is nothing")
        assertEquals(0L, rotScale(1_000L, 0L), "and scaled by nothing is nothing")
    }

    // ── The transform ─────────────────────────────────────────────────────────

    /**
     * `local → world → local` returns where it started.
     *
     * The tolerance is **measured, not chosen**: a Python model emulating Kotlin's truncating
     * division put the worst round trip over the whole grid at 2705 sub-units, so 4000 is that with
     * headroom. It is 4e-6 of a tile, three orders of magnitude finer than the millitiles a contact
     * is resolved in. A failure by a small margin here means the transcription is wrong.
     */
    @Test
    fun `a point survives the round trip through any pose`() {
        for (angle in ANGLES) {
            val p = Pose(37L * Flight.PER_TILE, -900L * Flight.PER_TILE, angle)
            for ((lx, ly) in POINTS) {
                val wx = p.toWorldX(lx, ly)
                val wy = p.toWorldY(lx, ly)
                assertTrue(
                    kotlin.math.abs(p.toLocalX(wx, wy) - lx) <= ROUND_TRIP &&
                        kotlin.math.abs(p.toLocalY(wx, wy) - ly) <= ROUND_TRIP,
                    "at ${angle.raw}, ($lx,$ly) came back as (${p.toLocalX(wx, wy)},${p.toLocalY(wx, wy)})",
                )
            }
        }
    }

    /** An unturned pose is a pure translation, and exactly so — every old save is this pose. */
    @Test
    fun `a zero angle is exactly a translation`() {
        val p = Pose(5L * Flight.PER_TILE, -3L * Flight.PER_TILE, Coord(0))

        assertEquals(12L * Flight.PER_TILE, p.toWorldX(7L * Flight.PER_TILE, 99L))
        assertEquals(-3L * Flight.PER_TILE + 99L, p.toWorldY(7L * Flight.PER_TILE, 99L))
        assertEquals(7L * Flight.PER_TILE, p.toLocalX(12L * Flight.PER_TILE, -3L * Flight.PER_TILE + 99L))
        assertEquals(99L, p.toLocalY(12L * Flight.PER_TILE, -3L * Flight.PER_TILE + 99L))
    }

    /** The origin of a pose is where the pose says it is, whatever the angle does. */
    @Test
    fun `the local origin sits at the pose`() {
        for (angle in ANGLES) {
            val p = Pose(-11L * Flight.PER_TILE, 400L * Flight.PER_TILE, angle)
            assertEquals(p.x, p.toWorldX(0L, 0L), "origin x at ${angle.raw}")
            assertEquals(p.y, p.toWorldY(0L, 0L), "origin y at ${angle.raw}")
        }
    }

    /**
     * A quarter turn, spelled out, because every property above holds equally for a turn that goes
     * the wrong way. +y is down, so a positive angle is clockwise: a point to the *right* of the
     * origin must swing to *below* it.
     */
    @Test
    fun `a positive angle turns clockwise`() {
        val p = Pose(0L, 0L, Coord(Int.MAX_VALUE / 2))
        val wx = p.toWorldX(Flight.PER_TILE, 0L)
        val wy = p.toWorldY(Flight.PER_TILE, 0L)

        assertTrue(kotlin.math.abs(wx) < Flight.PER_TILE / 1000L, "a quarter turn leaves nothing on x: $wx")
        assertTrue(wy > Flight.PER_TILE * 99L / 100L, "the right must swing to the bottom, not the top: $wy")
    }

    // ── Turning about the centre of mass ──────────────────────────────────────

    /**
     * **The one that matters for the sim.** A body spins about its centre of mass, not about its
     * grid origin, so turning the pose has to move the origin to keep that centre still.
     *
     * ⚠️ The pivot is whatever [MassDistribution.comX] says and cannot be anything else — see
     * [Pose.turned]. What is checked here is that the centre named by the distribution is the point
     * that does not move, which is the whole contract.
     */
    @Test
    fun `turning about a pivot leaves the pivot where it was`() {
        val pivotX = 48L * Flight.PER_TILE
        val pivotY = 30L * Flight.PER_TILE

        for (angle in ANGLES) {
            val before = Pose(1_000L * Flight.PER_TILE, -20L * Flight.PER_TILE, Coord(0))
            val wasX = before.toWorldX(pivotX, pivotY)
            val wasY = before.toWorldY(pivotX, pivotY)

            val after = before.turned(angle, about(pivotX, pivotY))

            assertEquals(angle.raw, after.ang.raw, "the turn must actually happen")
            assertTrue(
                kotlin.math.abs(after.toWorldX(pivotX, pivotY) - wasX) <= PIVOT &&
                    kotlin.math.abs(after.toWorldY(pivotX, pivotY) - wasY) <= PIVOT,
                "turning ${angle.raw} about the centre moved it by " +
                    "(${after.toWorldX(pivotX, pivotY) - wasX}, ${after.toWorldY(pivotX, pivotY) - wasY})",
            )
        }
    }

    /** Turning about the origin is the degenerate case, and must not move the origin at all. */
    @Test
    fun `turning about the origin does not move the pose`() {
        val before = Pose(7L * Flight.PER_TILE, 9L * Flight.PER_TILE, Coord(0))
        val after = before.turned(Coord(Int.MAX_VALUE / 3), about(0L, 0L))

        assertEquals(before.x, after.x)
        assertEquals(before.y, after.y)
    }

    /** Not turning is not moving — the identity every still body takes every tick. */
    @Test
    fun `turning by nothing changes nothing`() {
        val before = Pose(7L * Flight.PER_TILE, 9L * Flight.PER_TILE, Coord(123_456))
        assertEquals(before, before.turned(Coord(0), about(48L * Flight.PER_TILE, 30L * Flight.PER_TILE)))
    }

    /** A distribution that exists only to name a centre — the pivot these tests want to turn about. */
    private fun about(x: Long, y: Long) = MassDistribution(
        mass = 1L,
        comMilliX = x / Rotation.PER_MILLI_TILE,
        comMilliY = y / Rotation.PER_MILLI_TILE,
        comX = x,
        comY = y,
        gyrationSq = 0L,
    )

    private companion object {
        /**
         * Measured worst cases from the Python model (60 000 samples each), with headroom. See
         * [rotScale]'s KDoc — these are derived, and a near miss means the code is wrong.
         */
        const val ROUND_TRIP = 4_000L

        /** The pivot accumulates two transforms and two turns, so it is allowed twice as much. */
        const val PIVOT = 8_000L

        /** A turn's worth, including both axes, both branch cuts, and two awkward small values. */
        val ANGLES = listOf(
            0, 1, -7, Int.MAX_VALUE / 4, Int.MAX_VALUE / 2, Int.MAX_VALUE,
            -Int.MAX_VALUE / 3, -Int.MAX_VALUE / 2, Int.MAX_VALUE / 3,
        ).map { Coord(it) }

        /** Local points: the origin's neighbourhood, both signs, and the far corner of a big grid. */
        val POINTS = listOf(
            0L to 0L,
            Flight.PER_TILE to 0L,
            0L to Flight.PER_TILE,
            -96L * Flight.PER_TILE to 60L * Flight.PER_TILE,
            96L * Flight.PER_TILE to -60L * Flight.PER_TILE,
            48L * Flight.PER_TILE to 30L * Flight.PER_TILE,
        )
    }
}
