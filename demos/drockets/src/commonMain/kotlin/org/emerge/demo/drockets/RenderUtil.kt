package org.emerge.demo.drockets

import kotlin.math.abs

internal const val M4 = 16
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

internal fun setIdentity(out: FloatArray) {
    for (i in 0 until M4) out[i] = 0f
    out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
}

internal fun setTranslation(out: FloatArray, tx: Float, ty: Float) {
    setIdentity(out)
    out[12] = tx; out[13] = ty
}

internal fun setScale(out: FloatArray, sx: Float, sy: Float) {
    setIdentity(out)
    out[0] = sx; out[5] = sy
}

internal fun setRotationZ(out: FloatArray, rad: Float) {
    setIdentity(out)
    val c = kotlin.math.cos(rad); val s = kotlin.math.sin(rad)
    out[0] = c; out[1] = s; out[4] = -s; out[5] = c
}

internal fun multiply4x4(out: FloatArray, a: FloatArray, b: FloatArray) {
    for (col in 0..3) {
        val b0 = b[col * 4]
        val b1 = b[col * 4 + 1]
        val b2 = b[col * 4 + 2]
        val b3 = b[col * 4 + 3]
        out[col * 4 + 0] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
        out[col * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
        out[col * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
        out[col * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
    }
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
