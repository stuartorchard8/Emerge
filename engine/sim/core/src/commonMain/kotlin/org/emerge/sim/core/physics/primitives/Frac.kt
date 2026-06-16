package org.emerge.sim.core.physics.primitives

import kotlin.jvm.JvmInline
import kotlin.math.sign

/**
 * Fixed-point fractional number with the scale `raw / Int.MAX_VALUE`.
 *
 * On JVM/Android, `Frac` is a `@JvmInline value class`: instance storage is
 * unboxed to the underlying `Long` wherever the compiler can statically resolve
 * the type (local variables, non-nullable parameters, primitive-typed arrays,
 * data-class fields). This removes the ~16 byte allocation per `Frac` value
 * that a regular class would incur, which is the dominant allocation source
 * in the physics hot path (Contact.compute + Gravity + Integration run tens
 * of thousands of arithmetic ops per tick).
 *
 * The two constructor forms are preserved from the original class:
 *   - [Frac] `(raw)`    — stores `raw` directly.
 *   - [Frac] `(n, d)`   — stores `n * Int.MAX_VALUE / d`.
 *
 * Boxing still occurs when a `Frac` is used as a nullable (`Frac?`), stored in
 * a generic container (`List<Frac>`, `Map<_, Frac>`), or accessed via a
 * reference type; the hot paths avoid these patterns.
 */
@JvmInline
value class Frac(val raw: Long) {
    constructor(n: Long, d: Int) : this(n * Int.MAX_VALUE.toLong() / d.toLong())

    operator fun plus(o: Frac?): Frac = if (o == null) this else Frac(raw + o.raw)
    operator fun minus(o: Frac?): Frac = if (o == null) this else Frac(raw - o.raw)
    operator fun div(o: Int): Frac = Frac(raw / o)
    operator fun div(o: Frac): Frac = Frac(raw * Int.MAX_VALUE.toLong() / o.raw)
    operator fun times(o: Frac): Frac = Frac(raw * o.raw / Int.MAX_VALUE.toLong())
    operator fun times(o: Int): Frac = Frac(raw * o.toLong())
    operator fun unaryMinus(): Frac = Frac(-raw)

    fun wrap(): Coord = Coord(raw.toInt())

    fun toFloat(): Float = raw.toFloat() / Int.MAX_VALUE.toFloat() // -1f..1f
    fun toLong(): Long = raw
    fun toCircumference(): Frac = Frac((this * PIon4).raw * 4)
    val sign: Int get() = raw.sign

    fun coerceAtMost(o: Frac): Frac = Frac(raw.coerceAtMost(o.raw))
    fun coerceAtLeast(o: Frac): Frac = Frac(raw.coerceAtLeast(o.raw))
    fun coerceIn(lo: Frac, hi: Frac): Frac = Frac(raw.coerceIn(lo.raw, hi.raw))

    operator fun compareTo(o: Frac): Int = raw.compareTo(o.raw)

    /** Integer remainder of `raw % n` as a Frac — the part of a [div]`(n)` split that doesn't divide
     *  evenly. In a closed system this must go somewhere (e.g. expelled to the environment as waste). */
    fun remainder(n: Int): Frac = Frac(raw % n)

    /** Fixed-point square root in value-space: `sqrt(raw / MAX)`. */
    fun sqrt(): Frac = if (raw <= 0L) Frac(0) else Frac(isqrt(raw) * SQRT_MAX_INT)

    companion object {
        val PIon4: Frac = Frac(1686629713L)
        fun abs(v: Frac): Frac = Frac(kotlin.math.abs(v.raw))

        /** Float → Frac boundary conversion — for the non-sim edges only (codec text, light-field
         *  build from `exp`, rendering). Sim arithmetic stays in Frac. */
        fun fromFloat(f: Float): Frac = Frac((f.toDouble() * Int.MAX_VALUE.toDouble()).toLong())

        // Exact floor(√n). The former [1, floor(√Long.MAX)] bisection was, for n ≥ 2, exactly floor(√n)
        // (the bounds never clamp) and n for n < 2. A double seed corrected to integer exactness yields the
        // same floor with one sqrt + a couple of overflow-safe division checks instead of ~31 divisions; the
        // corrected result is the exact integer floor regardless of the double's rounding, so it stays
        // deterministic across platforms and bit-identical to the old bisection.
        private fun isqrt(n: Long): Long {
            if (n < 2) return n
            var x = kotlin.math.sqrt(n.toDouble()).toLong()
            if (x < 1L) x = 1L
            while (x > n / x) x--                 // descend until x·x ≤ n
            while (x + 1L <= n / (x + 1L)) x++     // ascend until (x+1)·(x+1) > n  → x = floor(√n)
            return x
        }
        private val SQRT_MAX_INT: Long = isqrt(Int.MAX_VALUE.toLong())


        fun beizer(x: Frac): Frac {
            return x*(x*3-x*x*2)
        }

        fun parametric(x: Frac): Frac {
            val sqr = x*x
            return sqr/(Frac(1L,1) - (x-sqr)*2)
        }
    }
}
