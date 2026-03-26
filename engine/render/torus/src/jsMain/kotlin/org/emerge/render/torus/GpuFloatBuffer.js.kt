package org.emerge.render.torus

import org.khronos.webgl.Float32Array

actual class GpuFloatBuffer actual constructor(capacity: Int) {
    internal val float32Array = Float32Array(capacity)
    private var position = 0
    internal var limit = capacity
        private set

    actual fun clear(): GpuFloatBuffer {
        position = 0
        limit = float32Array.length
        return this
    }

    actual fun put(src: FloatArray, offset: Int, length: Int): GpuFloatBuffer {
        val sub = src.sliceArray(offset until offset + length)
        float32Array.set(sub.toTypedArray(), position)
        position += length
        return this
    }

    actual fun flip(): GpuFloatBuffer {
        limit = position
        position = 0
        return this
    }
}
