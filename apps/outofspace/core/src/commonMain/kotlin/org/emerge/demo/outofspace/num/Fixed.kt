package org.emerge.demo.outofspace.num

/**
 * `numerator × scale / denominator`, computed so that the **mass unit cannot overflow it**.
 *
 * Steps 4 and 4b of `PLAN_unit_rescale.md`. Nearly every tight expression in this game has one
 * shape: a ratio of two quantities *in the same unit*, multiplied by a third thing. Written the
 * obvious way — `a * scale / b` — the product is quadratic in the mass unit and overflows a `Long`
 * long before the unit gets small enough to be useful. Written as a ratio first, the unit cancels
 * and the expression stops caring what a gram is worth.
 *
 * ### Why the usual fix does not work at these sites
 *
 * The standard repair is to split whole part and remainder, as `massPerTileOf` used to. It is
 * worth almost nothing here, and the reason is worth stating because it is not obvious: splitting
 * turns the worst intermediate from `a × scale` into `b × scale`, and at every one of these sites
 * `a` and `b` carry the mass unit *together*. The whole gain is whatever their ratio is — a factor
 * of the top speed for a velocity, of the pressure ratio for a potential. Never an order of
 * magnitude, and never enough.
 *
 * ### What actually works: split, then widen
 *
 * The common case is the split: `n/d × scale + (n%d) × scale/d`, where the remainder is smaller
 * than `d` and so `remainder × scale` cannot overflow as long as `d ≤ Long.MAX / scale`. That is
 * every call at today's mass unit, and it is **exact**.
 *
 * When the denominator is larger than that, the arithmetic moves to a 128-bit intermediate
 * ([mulDivTowardZero]) rather than losing bits to get there. It used to shift `n` and `d` down
 * together until the product fit, which is scale-invariant and cheap and was wrong in a way that
 * matters:
 *
 * ⚠️ **Shifting the numerator is an error of ±2^k in the numerator's own units**, not a relative
 * error. At a microgram unit a tile's mass needs ~18 bits of reduction, so a result derived from
 * a *small* numerator against a *large* denominator came back with an absolute slop of hundreds of
 * thousands of units. For a velocity that is noise. For [org.emerge.demo.outofspace.chem.apportion]
 * it is matter: differencing a running total whose steps are wrong by ±2^k hands a trace species
 * more than the mixture contains, and `Mixture.minus` then fails loudly (`subtracting more Osmium
 * than present: 376 - 1772`) a long way downstream of here. Exactness is not a luxury at these
 * sites — the split is a conservation law.
 *
 * ### The three properties callers are entitled to rely on
 *
 * 1. **Monotonic** and non-decreasing in [numerator], for fixed [denominator] and [scale] — trivial
 *    now that the result is exact. [org.emerge.demo.outofspace.chem.apportion] rests its entire
 *    conservation argument on this.
 * 2. **Exact at the ends**: `scaledRatio(0, d, s) == 0` and `scaledRatio(d, d, s) == s`.
 * 3. **Exact everywhere else too**: the result is `numerator × scale / denominator` truncated
 *    toward zero, with no intermediate rounding of any kind.
 *
 * ⚠️ **The whole part is still a plain multiply.** `n / d * scale` overflows if the ratio itself is
 * enormous — a gram of hull carrying a ship's momentum. That was true of every form this replaced
 * and is not a regression, but it is the one case this does not cover: it bounds the *unit*, not
 * the *physics*.
 */
fun scaledRatio(numerator: Long, denominator: Long, scale: Long): Long {
    // ⚠️ `scale` is guarded because the reduction below divides by it. A zero scale is not
    // hypothetical: `vanDerWaalsPressure` passes `8 × temperatureR`, and a reduced temperature
    // rounds to zero for any gas cold enough — which is what a tile of air becomes the moment
    // anything upstream gets its mass unit wrong. The answer is zero either way, so the guard costs
    // nothing and turns a crash a long way from its cause into an ordinary result.
    if (denominator <= 0L || numerator == 0L || scale <= 0L) return 0L
    // Below this, `remainder × scale` cannot overflow, because the remainder is smaller than `d`.
    if (fitsSplit(numerator, denominator, scale)) {
        // Exact, and for both signs: Kotlin truncates toward zero and `%` takes the dividend's sign,
        // so the whole part and the remainder always agree about which way they lean.
        return numerator / denominator * scale + numerator % denominator * scale / denominator
    }
    return mulDiv(numerator, scale, denominator, round = false)
}

/**
 * [scaledRatio], rounded to the **nearest** integer rather than truncated toward zero — halves away
 * from zero, so it is symmetric about the origin and `−f(x) == f(−x)` still holds.
 *
 * ### Why a second rounding rule earns its place
 *
 * Truncation is the right default: it is what integer division does, it never overshoots, and for a
 * quantity that is *consumed* — mass taken off a pile, energy moved between tiles — never
 * overshooting is the safety property that matters.
 *
 * A **rotation** is the case where it is wrong, and wrong in a way that compounds. `R(θ)` applied to
 * a vector is four of these, and truncation pulls every one of them toward zero, so a turned vector
 * comes back systematically *shorter* than it went in. Applied once that is a unit or two. Applied
 * to a running total, once per tick, for as long as the ship is turning, it is a drift: the momentum
 * ledger on a rotating starter vessel walked monotonically to 112 over forty ticks and stopped the
 * tick the rotation did, because the error has a sign and the sign never changes. Rounding to
 * nearest makes the same error a coin flip, and a coin flip does not accumulate — it random-walks at
 * √n instead of marching at n.
 *
 * See [org.emerge.demo.outofspace.world.rotScale], which is the reason this exists.
 */
fun scaledRatioRounded(numerator: Long, denominator: Long, scale: Long): Long {
    if (denominator <= 0L || numerator == 0L || scale <= 0L) return 0L
    if (fitsSplit(numerator, denominator, scale)) {
        val whole = numerator / denominator * scale
        val part = numerator % denominator * scale
        var q = part / denominator
        val r = part % denominator
        // `2×|r| ≥ d` said without the doubling, which would overflow for a denominator past 2^62.
        val magnitude = if (r < 0L) -r else r
        if (magnitude >= denominator - magnitude) q += if (r < 0L) -1L else 1L
        return whole + q
    }
    return mulDiv(numerator, scale, denominator, round = true)
}

/**
 * Whether the split form above is safe — i.e. whether `(n % d) × scale` and `n / d × scale` both fit.
 *
 * ### Why the denominator alone was the wrong question
 *
 * `d ≤ Long.MAX / scale` is *sufficient*, since the remainder is smaller than `d`. It is nowhere
 * near necessary, and the difference turned out to be most of a tick. Every call to
 * [org.emerge.demo.outofspace.chem.reducedDensity] is `scaledRatio(mass, massPerTile, SCALE)`, and
 * once the mass unit became the microgram a critical mass per tile is ~3×10¹¹ — past `MAX / SCALE`,
 * so the guard sent **every one of them** into [mulDiv]'s bit-at-a-time 128-bit division. A tile of
 * air weighs ~10⁹ though, so `mass × SCALE` was never anywhere near overflowing: the arithmetic was
 * paying 128 loop iterations to avoid an overflow that could not happen.
 *
 * ⚠️ **Measured: 56% of every execution sample in the game, on a real save.** `reducedDensity` alone
 * was 39%. A guard that is merely conservative is not free when it guards the hottest expression
 * there is — and this one was invisible, because the slow path is *correct*, just slow.
 *
 * So ask the numerator too. `|n| ≤ MAX / scale` bounds `n × scale`, and both terms of the split are
 * built from quantities no larger than `n` (`|n / d| ≤ |n|` and `|n % d| ≤ |n|`), so it bounds them
 * as well. The body it guards is unchanged, which is what makes this bit-for-bit the same answer.
 */
private fun fitsSplit(numerator: Long, denominator: Long, scale: Long): Boolean {
    if (denominator <= Long.MAX_VALUE / scale) return true
    // `Long.MIN_VALUE` has no positive magnitude; it cannot fit whatever the scale, so say so
    // rather than negating it into itself.
    if (numerator == Long.MIN_VALUE) return false
    val magnitude = if (numerator < 0L) -numerator else numerator
    return magnitude <= Long.MAX_VALUE / scale
}

/**
 * `a × b / d`, with the product carried in 128 bits so nothing is lost on the way — truncated toward
 * zero, or rounded to nearest with halves away from zero when [round] is set. [d] must be positive;
 * [a] may be either sign, [b] must not be negative.
 *
 * Only reached when `d > Long.MAX / b`, which is rare enough that a bit-at-a-time division is the
 * right trade: correctness at every mass unit, paid for on the path that would otherwise be wrong.
 * The result is assumed to fit a `Long` — it does wherever this is called from, since `a ≤ d` there
 * bounds it by `b`. Note `a = Long.MIN_VALUE` has no positive magnitude and is not supported.
 */
internal fun mulDiv(a: Long, b: Long, d: Long, round: Boolean): Long {
    val negative = a < 0L
    val magnitude = if (negative) (-a).toULong() else a.toULong()
    val multiplier = b.toULong()
    val divisor = d.toULong()

    // Schoolbook 64×64 → 128, in 32-bit limbs.
    val aLo = magnitude and 0xFFFFFFFFuL
    val aHi = magnitude shr 32
    val bLo = multiplier and 0xFFFFFFFFuL
    val bHi = multiplier shr 32
    val ll = aLo * bLo
    val lh = aLo * bHi
    val hl = aHi * bLo
    val mid = lh + (ll shr 32)                       // cannot overflow: both terms fit 64 bits here
    val midCarry = if (mid < lh) 1uL shl 32 else 0uL // the one place the sum can wrap
    val mid2 = mid + hl
    val carry = midCarry + (if (mid2 < mid) 1uL shl 32 else 0uL)
    val high = aHi * bHi + (mid2 shr 32) + carry
    val low = (mid2 shl 32) or (ll and 0xFFFFFFFFuL)

    // Long division of the 128-bit product by `divisor`, most significant bit first. `remainder`
    // stays below `divisor`, so shifting it left one place can never wrap.
    var remainder: ULong
    var quotient: ULong
    if (high == 0uL) {
        // The whole product fits 64 unsigned bits, so the hardware can do this in one instruction.
        // Worth its own branch because it is not a rare shape: the callers that reach here at all
        // do so because their *denominator* is large, which says nothing about the product.
        quotient = low / divisor
        remainder = low % divisor
    } else {
        // A set bit at 64 or above means the quotient does not fit a `Long`, which the callers rule
        // out. It is the same condition the loop used to `require` bit by bit — `high >= divisor` is
        // exactly "some quotient bit lands at 64 or above" — asked once, out of the loop.
        require(high < divisor) { "quotient of $a × $b / $d does not fit a Long" }
        remainder = 0uL
        quotient = 0uL
        // Start at the product's top set bit rather than at 127. The bits above it are zero, so
        // every iteration they buy is a shift of a zero remainder onto a zero quotient.
        for (bit in (127 - high.countLeadingZeroBits()) downTo 0) {
            val digit = if (bit >= 64) (high shr (bit - 64)) and 1uL else (low shr bit) and 1uL
            remainder = (remainder shl 1) or digit
            if (remainder >= divisor) {
                remainder -= divisor
                quotient = quotient or (1uL shl bit)
            }
        }
    }
    // Halves away from zero, on the magnitude — the sign goes back on below.
    if (round && remainder * 2uL >= divisor) quotient++
    val result = quotient.toLong()
    return if (negative) -result else result
}

/**
 * Integer square root: the largest `r` such that `r * r <= n`.
 *
 * Newton's method with integer division, starting from an upper bound of `n + 1`.
 * `n = 0` → `0`; `n = 1` → `1`; `n = 2` → `1`; `n = 3` → `1`; `n = 4` → `2`; etc.
 */
fun isqrt(n: Long): Long {
    if (n < 0L) throw IllegalArgumentException("isqrt of negative")
    if (n == 0L) return 0L
    var x = n
    var y = (x + 1L) shr 1
    while (y < x) {
        x = y
        y = (x + n / x) shr 1
    }
    return x
}
