package org.emerge.render.torus

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

actual class GpuFloatBuffer actual constructor(capacity: Int) {
    internal val nioBuffer: FloatBuffer = ByteBuffer.allocateDirect(capacity * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    actual fun clear(): GpuFloatBuffer { nioBuffer.clear(); return this }
    actual fun put(src: FloatArray, offset: Int, length: Int): GpuFloatBuffer { nioBuffer.put(src, offset, length); return this }
    actual fun flip(): GpuFloatBuffer { nioBuffer.flip(); return this }
}
