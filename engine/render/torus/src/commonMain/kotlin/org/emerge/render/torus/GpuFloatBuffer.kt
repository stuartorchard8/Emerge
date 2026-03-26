package org.emerge.render.torus

/**
 * Platform-agnostic float buffer for uploading vertex/instance data to the GPU.
 *
 * - JVM/Android: backed by a direct [java.nio.FloatBuffer]
 * - JS: backed by a [Float32Array]
 *
 * @param capacity number of floats the buffer can hold
 */
expect class GpuFloatBuffer(capacity: Int) {
    fun clear(): GpuFloatBuffer
    fun put(src: FloatArray, offset: Int, length: Int): GpuFloatBuffer
    fun flip(): GpuFloatBuffer
}

fun GpuFloatBuffer.put(src: FloatArray): GpuFloatBuffer = put(src, 0, src.size)
