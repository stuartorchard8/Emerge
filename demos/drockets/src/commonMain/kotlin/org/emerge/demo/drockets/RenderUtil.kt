package org.emerge.demo.drockets

import kotlin.math.abs

internal const val TRIANGLE_VERTEX_OFFSET = 0
internal const val QUAD_VERTEX_OFFSET = 3

/**
 * Maps an HSV triplet to linear RGB. `h` is in degrees [0, 360], `s` and `v` in [0, 1].
 * Used by both [WorldRenderer] (body and fire tints from genome) and [LineageOverlay]
 * (lineage-node colors).
 */
internal fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
    if (s <= 0f) return Triple(v, v, v)
    val hh = ((h % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1f - abs(((hh / 60f) % 2f) - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Triple(r1 + m, g1 + m, b1 + m)
}

/** Decoded view's [HsvColor] (h in [0,360], s/v in [0,1000]) to linear RGB. */
internal fun HsvColor.toRgb(): Triple<Float, Float, Float> =
    hsvToRgb(h.toFloat(), s.toFloat() / 1000f, v.toFloat() / 1000f)

/**
 * Stable per-id color hash, reproducing the `mod(vec3(c/1.9, c/2.9, c/4.9), 1.0)` the sprite
 * shader used to compute from `primaryId`. Kept in Kotlin so the shader stays a pure tint
 * consumer; used for entities (e.g. knights) that have no genome-derived color of their own.
 */
internal fun idHashColor(primaryId: Float): Triple<Float, Float, Float> {
    val c = primaryId + 1f
    fun frac(x: Float) = x - kotlin.math.floor(x)
    return Triple(frac(c / 1.9f), frac(c / 2.9f), frac(c / 4.9f))
}

/**
 * Wraps a signed delta into the half-open interval `(-size/2, size/2]`.
 * The world is a torus of side [size]; entities on the far side may project closer
 * across the wrap than directly.
 */
internal fun wrapDelta(d: Float, size: Float): Float {
    val half = 0.5f * size
    val a = d + half
    val m = a - kotlin.math.floor(a / size) * size
    return m - half
}
