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
 * ### What actually works: reduce the fraction first
 *
 * A ratio does not care what unit its two halves are in as long as they are in the same one. So
 * this shifts both down together until the denominator is small enough that the scaling cannot
 * overflow, and only then does the arithmetic. The result is **scale-invariant**: it costs nothing
 * at one gram per unit, it holds at a microgram, and it holds at any unit after that without
 * anybody having to come back and re-derive a bound.
 *
 * The precision given up is small and measured (`NumericLimitsTest`). The denominator keeps at
 * least 33 bits after the reduction, so the ratio is good to about one part in 10¹⁰.  Shifting
 * rather than dividing by an arbitrary factor keeps it exact for the common case where no reduction
 * is needed at all — which is every call at today's mass unit.
 *
 * ### The two properties callers are entitled to rely on
 *
 * 1. **Monotonic** and non-decreasing in [numerator], for fixed [denominator] and [scale]. The
 *    reduction shift depends only on those two, so a whole series of calls sharing them is
 *    reduced identically and stays ordered. [org.emerge.demo.outofspace.chem.apportion] rests its
 *    entire conservation argument on this.
 * 2. **Exact at the ends**: `scaledRatio(0, d, s) == 0` and `scaledRatio(d, d, s) == s`.
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
    var n = numerator
    var d = denominator
    // Below this, `remainder × scale` cannot overflow, because the remainder is smaller than `d`.
    val ceiling = Long.MAX_VALUE / scale
    while (d > ceiling) {
        n = n shr 1
        d = d shr 1
    }
    // Exact for the reduced pair, and for both signs: Kotlin truncates toward zero and `%` takes the
    // dividend's sign, so the whole part and the remainder always agree about which way they lean.
    return n / d * scale + n % d * scale / d
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
