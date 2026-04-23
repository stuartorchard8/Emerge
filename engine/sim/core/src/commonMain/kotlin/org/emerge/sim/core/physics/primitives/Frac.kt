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

    companion object {
        val PIon4: Frac = Frac(1686629713L)
        fun abs(v: Frac): Frac = Frac(kotlin.math.abs(v.raw))
    }
}
