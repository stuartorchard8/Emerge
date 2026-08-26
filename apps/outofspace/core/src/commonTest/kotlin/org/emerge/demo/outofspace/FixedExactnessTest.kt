package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.num.mulDiv
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.num.scaledRatioRounded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the two fixed-point primitives are **exact**, not merely close.
 *
 * Both were made much faster by adding paths, and a path is where an off-by-one hides: the result
 * stays plausible, the suite stays green, and a mass ledger drifts by a unit somewhere a long way
 * downstream. `scaledRatio` in particular is load-bearing for conservation — `apportion` rests its
 * whole argument on this function being monotonic and exact — so "the fast version agrees with the
 * slow one" is the property worth pinning, and pinning it needs the two to be genuinely different
 * algorithms rather than one calling the other.
 *
 * They are. The split form is `n/d × s + (n%d) × s/d` in 64 bits; [mulDiv] builds the full 128-bit
 * product and divides it. Where both are valid they must agree bit for bit, and that is what most of
 * this file checks — across inputs chosen to land in each of [mulDiv]'s three branches.
 */
class FixedExactnessTest {

    /** The three shapes [mulDiv] now dispatches on, plus the boundaries between them. */
    private val divisors = longArrayOf(
        1L, 2L, 3L, 7L,
        Int.MAX_VALUE.toLong(),            // `Flight.FRAC_ONE` — the 32-bit path, and `rotScale`'s
        0xFFFFFFFFL,                       // the widest divisor the 32-bit path may take
        0x100000000L,                      // one past it: the bit loop
        1_000_000_000L, 322_000_000_000L,  // a critical mass per tile, in micrograms
        Long.MAX_VALUE / 3,
    )

    private val numerators = longArrayOf(
        0L, 1L, 2L, 999L, 1_000_000L, 1_000_000_007L,
        1_000_000_000_000L, 4_000_000_000_000_000L, Long.MAX_VALUE / 8, Long.MAX_VALUE / 2,
        -1L, -1_000_000_007L, -4_000_000_000_000_000L,
    )

    private val scales = longArrayOf(1L, 1000L, 100_000_000L, Int.MAX_VALUE.toLong())

    @Test
    fun `the split form and the 128-bit product agree everywhere both are defined`() {
        for (d in divisors) for (n in numerators) for (s in scales) {
            // `mulDiv` is only defined where the quotient fits, which is what its own `require`
            // says; skip the pairs that would (correctly) throw rather than asserting about them.
            if (!quotientFits(n, s, d)) continue
            assertEquals(mulDiv(n, s, d, round = false), scaledRatio(n, d, s), "trunc $n × $s / $d")
            assertEquals(mulDiv(n, s, d, round = true), scaledRatioRounded(n, d, s), "round $n × $s / $d")
        }
    }

    @Test
    fun `exact at the ends, and monotonic in the numerator`() {
        for (d in divisors) for (s in scales) {
            assertEquals(0L, scaledRatio(0L, d, s), "zero over $d")
            assertEquals(s, scaledRatio(d, d, s), "$d over $d")
            var previous = Long.MIN_VALUE
            for (n in numerators.sorted()) {
                if (!quotientFits(n, s, d)) continue
                val here = scaledRatio(n, d, s)
                assertTrue(here >= previous, "not monotonic at $n × $s / $d: $here after $previous")
                previous = here
            }
        }
    }

    /**
     * That [isqrt] really is the floor of the square root, said the way the definition says it.
     *
     * `x ≤ n / x` rather than `x * x ≤ n`, because the square of anything past 3×10⁹ is not a `Long`
     * — which is also why the implementation corrects its double seed the same way.
     */
    @Test
    fun `isqrt is the exact integer floor`() {
        val cases = ArrayList<Long>()
        for (n in 0L..1_000L) cases += n
        for (k in 0..62) {
            val bit = 1L shl k
            cases += bit
            cases += bit - 1L
            cases += bit + 1L
        }
        // Around perfect squares, where a seed that lands a hair high or low is visible at all.
        for (r in longArrayOf(3L, 46_341L, 1_000_000L, 2_147_483_647L, 3_037_000_499L)) {
            cases += r * r - 1L
            cases += r * r
            cases += r * r + 1L
        }
        cases += Long.MAX_VALUE
        for (n in cases) {
            if (n < 0L) continue
            val x = isqrt(n)
            assertTrue(x >= 0L, "isqrt($n) = $x is negative")
            assertTrue(x == 0L || x <= n / x, "isqrt($n) = $x is too large")
            assertTrue(x + 1L > n / (x + 1L), "isqrt($n) = $x is too small")
        }
    }

    /** Whether `n × s / d` has an answer a `Long` can hold — [mulDiv]'s precondition, restated. */
    private fun quotientFits(n: Long, s: Long, d: Long): Boolean {
        if (d <= 0L || s <= 0L) return false
        val magnitude = if (n < 0L) -n else n
        // `|n| / d × s` is the quotient's whole part; if that alone overflows, so does the answer.
        return magnitude / d <= Long.MAX_VALUE / s
    }
}
