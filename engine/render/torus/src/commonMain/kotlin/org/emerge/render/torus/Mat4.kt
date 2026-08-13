package org.emerge.render.torus

import kotlin.jvm.JvmInline
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 4×4 transform matrix stored as 16 **column-major** floats — the layout OpenGL
 * expects for `uniformMatrix4fv` and for the instanced model-matrix attribute the
 * sprite/circle/planet shaders read.
 *
 * This is a value class over its backing [FloatArray], so it adds no per-matrix
 * object overhead, but the array itself is mutable. The render hot path (per frame,
 * per instance) reuses a small pool of [scratch] matrices and writes into them in
 * place via [setScale]/[setRotationZ]/[setTranslation]/[setProduct], staying
 * allocation-free; the allocating companion factories ([translation], [scale],
 * [rotationZ], [identity]) and [times] are for cold or once-per-frame use.
 *
 * The in-place mutators return `this` so calls can chain.
 */
@JvmInline
value class Mat4(val m: FloatArray) {

    /** Resets this matrix to the identity, in place. */
    fun setIdentity(): Mat4 {
        val m = m
        for (i in 0 until FLOATS) m[i] = 0f
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
        return this
    }

    /** Sets this matrix to a translation by ([tx], [ty]), in place. */
    fun setTranslation(tx: Float, ty: Float): Mat4 {
        setIdentity()
        m[12] = tx
        m[13] = ty
        return this
    }

    /** Sets this matrix to a scale of ([sx], [sy]), in place. */
    fun setScale(sx: Float, sy: Float): Mat4 {
        setIdentity()
        m[0] = sx
        m[5] = sy
        return this
    }

    /** Sets this matrix to a rotation of [rad] radians about the Z axis, in place. */
    fun setRotationZ(rad: Float): Mat4 = setRotationZ(cos(rad), sin(rad))

    /**
     * Sets this matrix to the Z rotation with the given [cos]/[sin], in place — for callers whose
     * angle never was a radian. A sim that turns by an integer angle gets its cosine and sine from
     * its own exact table, and converting that to radians only to take `cos` of it again would put
     * a float trig round-trip in the middle of the one path that was built to avoid trig.
     */
    fun setRotationZ(cos: Float, sin: Float): Mat4 {
        setIdentity()
        m[0] = cos
        m[1] = sin
        m[4] = -sin
        m[5] = cos
        return this
    }

    /**
     * Sets this matrix to the column-major product `a * b`, in place.
     *
     * `this` may alias [b] (each of b's columns is read before that column of the
     * output is written) but must **not** alias [a], whose entries are read for
     * every output column.
     */
    fun setProduct(a: Mat4, b: Mat4): Mat4 {
        val out = m
        val am = a.m
        val bm = b.m
        for (col in 0..3) {
            val b0 = bm[col * 4 + 0]
            val b1 = bm[col * 4 + 1]
            val b2 = bm[col * 4 + 2]
            val b3 = bm[col * 4 + 3]
            out[col * 4 + 0] = am[0] * b0 + am[4] * b1 + am[8] * b2 + am[12] * b3
            out[col * 4 + 1] = am[1] * b0 + am[5] * b1 + am[9] * b2 + am[13] * b3
            out[col * 4 + 2] = am[2] * b0 + am[6] * b1 + am[10] * b2 + am[14] * b3
            out[col * 4 + 3] = am[3] * b0 + am[7] * b1 + am[11] * b2 + am[15] * b3
        }
        return this
    }

    /**
     * Copies the 16 column-major floats into [dest] starting at [offset] — e.g. the
     * slot for one instance in a packed `MAX_INSTANCES * FLOATS` GPU upload buffer.
     */
    fun copyInto(dest: FloatArray, offset: Int) {
        m.copyInto(dest, destinationOffset = offset, startIndex = 0, endIndex = FLOATS)
    }

    /** Allocating product `this * b`; for cold paths. The hot path uses [setProduct]. */
    operator fun times(b: Mat4): Mat4 = Mat4(FloatArray(FLOATS)).setProduct(this, b)

    companion object {
        /** Number of floats backing a 4×4 matrix. */
        const val FLOATS: Int = 16

        /** A fresh matrix backed by its own zeroed 16-float array (not yet identity). */
        fun scratch(): Mat4 = Mat4(FloatArray(FLOATS))

        fun identity(): Mat4 = scratch().setIdentity()
        fun translation(tx: Float, ty: Float): Mat4 = scratch().setTranslation(tx, ty)
        fun scale(sx: Float, sy: Float): Mat4 = scratch().setScale(sx, sy)
        fun rotationZ(rad: Float): Mat4 = scratch().setRotationZ(rad)
        fun rotationZ(cos: Float, sin: Float): Mat4 = scratch().setRotationZ(cos, sin)
    }
}
