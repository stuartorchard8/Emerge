package org.emerge.sim.core.physics.primitives

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accuracy contract for [Norm.fromAngle] and [Norm.asAngle].
 *
 * `Double` appears freely here and must never appear in what it is testing: the oracle is allowed to
 * be floating point precisely because it is the *reference*, and the point of the exercise is that
 * the implementation reaches it without borrowing its arithmetic. The tolerances are stated in raw
 * units rather than as a fraction, because a raw unit is the resolution of the type — a bound of one
 * says "correctly rounded" and a bound that has to be loosened is telling you something.
 *
 * The sampling is a fixed integer stride rather than a random spread so that a failure names the
 * same angle every run. ~40k samples across the two directions is a few milliseconds.
 */
class TrigTest {

    private val max = Int.MAX_VALUE.toLong()

    /** Every angle in the sweep, as a raw [Coord], covering the whole `Int` range including the wrap. */
    private fun sweep(steps: Int): List<Int> {
        val stride = (0x1_0000_0000uL / steps.toULong()).toLong()
        return (0 until steps).map { (Int.MIN_VALUE.toLong() + it * stride).toInt() }
    }

    @Test
    fun `fromAngle is correctly rounded against exact cosine and sine`() {
        var worst = 0.0
        var worstAt = 0
        for (raw in sweep(20_000)) {
            val n = Norm.fromAngle(Coord(raw))
            val radians = raw.toDouble() / max.toDouble() * PI
            val dx = abs(n.x.raw - cos(radians) * max)
            val dy = abs(n.y.raw - sin(radians) * max)
            val e = if (dx > dy) dx else dy
            if (e > worst) { worst = e; worstAt = raw }
        }
        // 0.53 measured; 1.0 is the "correctly rounded" line and the number worth defending.
        assertTrue(worst <= 1.0, "fromAngle off by $worst raw units at angle $worstAt")
    }

    @Test
    fun `fromAngle returns unit vectors`() {
        for (raw in sweep(20_000)) {
            val n = Norm.fromAngle(Coord(raw))
            val len = hypot(n.x.raw.toDouble(), n.y.raw.toDouble()) / max.toDouble()
            assertTrue(abs(len - 1.0) < 2e-9, "length ${len} at angle $raw")
        }
    }

    @Test
    fun `fromAngle is exact at the angles that land on whole raws`() {
        // Zero and ±π are the only cardinals the Coord scale can represent exactly: π/2 is
        // 1073741823.5 raw, so it has no exact answer to check against and is covered by the
        // rounding test above instead.
        assertEquals(Norm(Frac(max), Frac(0L)), Norm.fromAngle(Coord(0)))
        assertEquals(Norm(Frac(-max), Frac(0L)), Norm.fromAngle(Coord(Int.MAX_VALUE)))
        assertEquals(Norm(Frac(-max), Frac(0L)), Norm.fromAngle(Coord(-Int.MAX_VALUE)))
    }

    @Test
    fun `asAngle inverts fromAngle exactly`() {
        // Not "to within a raw" — exactly. A direction that survives a round trip unchanged is what
        // lets orientation be stored either way round without a representation slowly leaking angle.
        //
        // Int.MIN_VALUE is the sole exception, and it is a property of Coord rather than of Trig:
        // Coord scales as raw/Int.MAX_VALUE but wraps on Int overflow, so its angular period is 2^32
        // while a full turn is 2·Int.MAX_VALUE = 2^32 − 2. Those disagree by two raw units, and
        // Int.MIN_VALUE is the one raw that falls in the gap — an angle just past −π, with no
        // representable fixed point. asAngle returns the same *direction* (2147483646, i.e. +π), two
        // raw off the input. Measured: this is 1 mismatch in 200_000 swept angles and the only one.
        // Widening the sweep will not find another; fixing it would mean rescaling every angle in the
        // engine, which is not this change's business.
        for (raw in sweep(20_000)) {
            if (raw == Int.MIN_VALUE) continue
            val back = Norm.fromAngle(Coord(raw)).asAngle.raw
            assertEquals(raw, back, "round trip moved angle $raw to $back")
        }
    }

    @Test
    fun `asAngle matches exact atan2 at every magnitude`() {
        // Short vectors are the case the old Float path was worst at and the case a normalised
        // direction least resembles, so the magnitudes are swept alongside the angles.
        var worst = 0.0
        var worstAt = ""
        for (mag in longArrayOf(1L, 3L, 7L, 1000L, 1L shl 20, max)) {
            for (raw in sweep(1_500)) {
                val radians = raw.toDouble() / max.toDouble() * PI
                val x = (cos(radians) * mag).toLong()
                val y = (sin(radians) * mag).toLong()
                if (x == 0L && y == 0L) continue
                val got = Norm(Frac(x), Frac(y)).asAngle.raw.toDouble()
                val expected = atan2(y.toDouble(), x.toDouble()) / PI * max
                // ±π are the same angle; compare the shorter way round the circle.
                var d = abs(got - expected)
                if (d > max) d = abs(d - 2.0 * max)
                if (d > worst) { worst = d; worstAt = "($x, $y)" }
            }
        }
        assertTrue(worst <= 1.0, "asAngle off by $worst raw units at $worstAt")
    }

    @Test
    fun `asAngle handles the axes and the zero vector`() {
        assertEquals(0, Norm(Frac(1L), Frac(0L)).asAngle.raw)
        assertEquals(0, Norm(Frac(0L), Frac(0L)).asAngle.raw, "atan2(0, 0) is 0")
        assertEquals(Int.MAX_VALUE, Norm(Frac(-1L), Frac(0L)).asAngle.raw)
        // π/2 is 1073741823.5 raw, so it has no exact representation and either neighbour is a
        // correct answer. Assert that, rather than picking one and pretending it was determined.
        // What *is* determined, and worth pinning, is that the two are exact negatives of each other.
        val up = Norm(Frac(0L), Frac(1L)).asAngle.raw.toLong()
        val down = Norm(Frac(0L), Frac(-1L)).asAngle.raw.toLong()
        assertTrue(up == max / 2 || up == max / 2 + 1, "atan2(1, 0) gave $up, not π/2")
        assertEquals(-up, down, "atan2(-1, 0) is not the negative of atan2(1, 0)")
    }

    @Test
    fun `rotateBy agrees with rotateByAngle`() {
        // The two are one implementation now; this pins that they stay one, because a caller
        // switching from an angle to a direction must not be a behaviour change.
        val v = Frac2(Frac(max / 3), Frac(-max / 7))
        for (raw in sweep(2_000)) {
            val angle = Coord(raw)
            assertEquals(v.rotateByAngle(angle), v.rotateBy(Norm.fromAngle(angle)))
        }
    }

    @Test
    fun `rotateBy composes as rotation should`() {
        // Rotating by θ then by −θ returns the original, to within the rounding of two rotations.
        val v = Frac2(Frac(max / 2), Frac(max / 5))
        for (raw in sweep(2_000)) {
            val f = Norm.fromAngle(Coord(raw))
            val there = v.rotateBy(f)
            val back = there.rotateBy(Norm(f.x, -f.y))
            assertTrue(
                abs(back.x.raw - v.x.raw) <= 4 && abs(back.y.raw - v.y.raw) <= 4,
                "rotate by $raw and back gave $back, not $v",
            )
        }
    }
}
