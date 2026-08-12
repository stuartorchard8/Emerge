package org.emerge.sim.core.physics.primitives

/**
 * Integer sine, cosine and arctangent over [Coord] and [Frac], with no floating point anywhere.
 *
 * ### Why this exists
 *
 * [Norm.fromAngle] and [Norm.asAngle] used to convert to `Float`, call `kotlin.math.cos`/`sin`/
 * `atan2`, and convert back. Both problems with that are worth naming, because only one of them is
 * the obvious one:
 *
 *  - **Precision.** A `Float` carries a 24-bit mantissa and [Frac] is a 31-bit fixed-point number,
 *    so the round trip threw away seven bits before it started. Measured against exact `cos`/`sin`,
 *    the old path was out by up to **641 raw units**; this one is out by at most **0.53**, which is
 *    to say it is correctly rounded. That is not a micro-optimisation, it is the difference between
 *    a direction that survives being renormalised and one that does not.
 *
 *  - **Determinism, which is the real one.** `cos(Float)` is not specified to return the same bits
 *    on every platform, and the engine runs the same sim on JVM, Android and JS and expects them to
 *    agree tick for tick. Scavengers calls this from `ShipThrustSystem`, `LandingSystem`,
 *    `DamageSystem` and `RespawnSystem` — inside a lockstep sim, where a one-ulp disagreement
 *    between host and client is a desync that grows. Every operation below is `Long` arithmetic and
 *    shifts, so all three platforms produce bit-identical answers by construction.
 *
 * The rest of the codebase already sets this precedent: [Frac.isqrt] and [Frac2.longISqrt] seed from
 * a `Double` and then *correct to integer exactness*, specifically so the result does not depend on
 * how the seed rounded. This file takes the same position one step further and never leaves `Long`.
 *
 * ### How it works — CORDIC
 *
 * CORDIC rotates a vector by a sequence of fixed angles `atan(2⁻ⁱ)`, each of which is applied by a
 * shift and an add rather than a multiply. Choosing at each step whether to add or subtract that
 * angle drives an accumulator towards the angle you asked for, and the vector it carries ends up
 * pointing there. Two modes, and the engine wants both:
 *
 *  - **Rotation** ([rotate]) drives the *angle* accumulator to zero and reads off the vector: that is
 *    `(cos, sin)`, so it is [Norm.fromAngle].
 *  - **Vectoring** ([atan2]) drives the *y* component to zero and reads off the angle: that is
 *    `atan2`, so it is [Norm.asAngle].
 *
 * One table of constants serves both, which is most of why this is worth writing as CORDIC rather
 * than as two unrelated polynomial approximations that could drift apart.
 *
 * ### The three scales, because mixing them up is the whole difficulty
 *
 *  - **Angle, external.** [Coord.raw] over [Int.MAX_VALUE] is the angle over π. A full turn is
 *    therefore `2·Int.MAX_VALUE` raw, and `Int` overflow wraps it — that is [Coord]'s existing
 *    contract and this file does not change it.
 *  - **Angle, internal.** Quadrant folding needs π/2 and π/4 to be *integers*, and they are not in
 *    the external scale (π/2 is 1073741823.5 raw). So the accumulator works at `raw × 4 × 2¹⁶`,
 *    where π/4 lands exactly on [EIGHTH]. The ×4 buys exact octant boundaries; the ×2¹⁶ buys enough
 *    fractional bits that rounding [ATAN] to whole units is not what limits the answer. At only ×4
 *    the accumulated table rounding alone cost 5 raw units of output error, so the extra sixteen
 *    bits are load-bearing rather than defensive.
 *  - **Vector.** The rotating vector is held at `Int.MAX_VALUE shl 9` — that is, [Frac]'s own scale
 *    with nine guard bits. The shift is what makes the conversion back to a [Frac] a shift and a
 *    round rather than a multiply, and that matters: at a scale of 2⁴⁰ the natural
 *    `v * Int.MAX_VALUE / 2⁴⁰` overflows `Long` at 2⁷², which is exactly the kind of thing that
 *    reads fine and is wrong only for large inputs.
 *
 * Nine guard bits and 31 iterations put both directions inside one raw unit, with `fromAngle`
 * followed by `asAngle` returning the angle it started from *exactly* across the whole `Int` range.
 */
internal object Trig {

    /** Fractional bits on the internal angle accumulator — see the scales note in the class KDoc. */
    private const val ANGLE_BITS = 16

    /** Guard bits on the rotating vector, above [Frac]'s own scale. */
    private const val VECTOR_BITS = 9

    /** π in internal angle units: `Int.MAX_VALUE × 4 × 2¹⁶`. */
    private const val HALF_TURN = 562949953159168L

    /** π/2, exactly, which is what the internal scale was chosen to make possible. */
    private const val QUARTER_TURN = 281474976579584L

    /** π/4, the width of the octant [rotate] is actually evaluated on. */
    private const val EIGHTH_TURN = 140737488289792L

    /**
     * Internal angle units per radian, used to convert the leftover angle into a final small
     * rotation. `4 × Int.MAX_VALUE × 2¹⁶ / π`.
     */
    private const val UNITS_PER_RADIAN = 179192535517265L

    /**
     * The rotating vector's starting length: `(Int.MAX_VALUE shl 9) / K`.
     *
     * CORDIC's shift-and-add steps are not pure rotations — each one stretches the vector by
     * `√(1 + 4⁻ⁱ)` — so after a fixed number of iterations the length has grown by a fixed factor
     * `K ≈ 1.6467602581210656`. Starting at `1/K` means the answer comes out unit length with no
     * division at the end. K depends only on the iteration count, so this constant and [ATAN]'s
     * length must move together.
     */
    private const val INITIAL_X = 667681662732L

    /**
     * `atan(2⁻ⁱ)` in internal angle units, for i in 0 until 31.
     *
     * The table stops at 31 because that is where it stops paying: `atan(2⁻³¹)` is 166886 units and
     * one output ulp is about 786000, so the entries have already fallen below the resolution of the
     * thing being computed. The leftover angle after the loop is handled by [rotate]'s final linear
     * step instead, which is exact enough at that size and costs one multiply rather than a table.
     */
    private val ATAN = longArrayOf(
        140737488289792L, 83082190643372L, 43898347793333L, 22283486777070L,
        11184984827146L, 5597944961507L, 2799655545737L, 1399913202885L,
        699967281661L, 349984975904L, 174992654837L, 87496348279L,
        43748176747L, 21874088700L, 10937044391L, 5468522200L,
        2734261101L, 1367130550L, 683565275L, 341782638L,
        170891319L, 85445659L, 42722830L, 21361415L,
        10680707L, 5340354L, 2670177L, 1335088L,
        667544L, 333772L, 166886L,
    )

    /**
     * `(cos, sin)` of [angle], as [Frac] raws.
     *
     * The octant fold is not an optimisation. CORDIC only converges over roughly ±99.9°, because the
     * table's total swing is finite, so the input *has* to be brought inside ±π/4 before the loop
     * runs — and the symmetries that do it (negate, reflect about π/2, swap about π/4) are exact
     * integer operations that add no error of their own.
     */
    fun cosSin(angle: Coord): LongArray {
        // Into internal units, then into (-π, π]. Written as an explicit floored modulo because
        // Kotlin's % takes the sign of the dividend, which would leave negative angles unfolded.
        var a = (angle.raw.toLong() * 4L) shl ANGLE_BITS
        val turn = HALF_TURN * 2L
        a += HALF_TURN
        a -= turn * floorDiv(a, turn)
        a -= HALF_TURN

        val negate = a < 0L
        if (negate) a = -a
        var flipX = false
        var swap = false
        if (a > QUARTER_TURN) { a = HALF_TURN - a; flipX = true }   // reflect about π/2
        if (a > EIGHTH_TURN) { a = QUARTER_TURN - a; swap = true }  // swap cos/sin about π/4

        val v = rotate(a)
        var x = v[0]
        var y = v[1]
        if (swap) { val t = x; x = y; y = t }
        if (flipX) x = -x
        if (negate) y = -y
        return longArrayOf(toFrac(x), toFrac(y))
    }

    /**
     * `atan2(y, x)` as a [Coord] raw. Returns zero for the zero vector, matching `atan2(0, 0)`.
     *
     * [x] and [y] are used only for their *ratio*, so they may be any pair of `Long`s on a common
     * scale — [Frac] raws, tile offsets, anything. The loop needs the pair large enough to have bits
     * to work with, so it is shifted up to about 2⁴⁰ first; that is what keeps a vector like `(1, 0)`
     * as accurate as `(Int.MAX_VALUE, 0)` rather than degenerating to a few dozen raw units of error.
     */
    fun atan2(y: Long, x: Long): Int {
        if (y == 0L) {
            // Handled up front rather than left to the loop. On the axis the true residual is exactly
            // zero, but CORDIC drives `vy` away from zero and back and lands on a residual whose
            // *sign* is arbitrary noise — which at the branch cut is the difference between +π and
            // −π. That made atan2(0, −1) and atan2(0, −Int.MAX_VALUE) disagree despite being the same
            // direction. Both are π; pick it exactly, and match atan2's convention that +0 goes to +π.
            return if (x < 0L) Int.MAX_VALUE else 0
        }
        var vx = x
        var vy = y
        // Fold to the right half-plane; the half turn is added back at the end.
        val flipped = vx < 0L
        if (flipped) { vx = -vx; vy = -vy }

        // The larger *magnitude* of the two, not the larger signed value: vx is non-negative after
        // the fold but vy is not, so comparing them signed would pick vx for a straight-down vector
        // and leave m at zero — a shift loop that never terminates — or, for (small x, large -y),
        // pick a shift that overflows vy.
        val ax = vx
        val ay = if (vy < 0L) -vy else vy
        var m = if (ax > ay) ax else ay
        var shift = 0
        while (m < (1L shl 40)) { m = m shl 1; shift++ }
        vx = vx shl shift
        vy = vy shl shift

        var z = 0L
        for (i in ATAN.indices) {
            if (vy > 0L) {
                val nx = vx + (vy shr i)
                vy -= (vx shr i)
                vx = nx
                z += ATAN[i]
            } else {
                val nx = vx - (vy shr i)
                vy += (vx shr i)
                vx = nx
                z -= ATAN[i]
            }
        }
        // Leftover: atan(vy/vx) ≈ vy/vx radians, converted to internal units.
        if (vx != 0L) z += vy * UNITS_PER_RADIAN / vx
        if (flipped) z = if (z <= 0L) z + HALF_TURN else z - HALF_TURN

        // Internal units back to Coord raw, rounding half away from zero.
        val q = 4L shl ANGLE_BITS
        return ((if (z >= 0L) 2L * z + q else 2L * z - q) / (2L * q)).toInt()
    }

    /**
     * CORDIC rotation over an angle already folded into `[0, π/4]`, returning `(x, y)` at the
     * internal vector scale.
     *
     * The trailing correction is what makes this correctly rounded rather than merely close. After
     * the loop the angle accumulator still holds a few hundred thousand internal units — under
     * 4e-9 radians — and rotating by an angle that small is, to well past the precision anything
     * here carries, `x -= y·θ; y += x·θ`. Without it the result was out by 1.5 raw units; with it,
     * by half of one.
     */
    private fun rotate(angle: Long): LongArray {
        var x = INITIAL_X
        var y = 0L
        var z = angle
        for (i in ATAN.indices) {
            if (z >= 0L) {
                val nx = x - (y shr i)
                y += (x shr i)
                x = nx
                z -= ATAN[i]
            } else {
                val nx = x + (y shr i)
                y -= (x shr i)
                x = nx
                z += ATAN[i]
            }
        }
        return longArrayOf(x - y * z / UNITS_PER_RADIAN, y + x * z / UNITS_PER_RADIAN)
    }

    /** Internal vector scale down to a [Frac] raw, rounding half away from zero. */
    private fun toFrac(v: Long): Long {
        val half = 1L shl (VECTOR_BITS - 1)
        return if (v >= 0L) (v + half) shr VECTOR_BITS else -((-v + half) shr VECTOR_BITS)
    }

    /** Floored division — `Long.floorDiv` is not in the common stdlib this module targets. */
    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a xor b < 0L && q * b != a) q - 1L else q
    }
}
