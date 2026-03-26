package org.emerge.render.torus

/**
 * WebGL2 GPU backend. Initialized at startup via [init] with the canvas rendering context.
 *
 * TODO(Phase 3): Implement all functions against WebGL2RenderingContext.
 */
actual object GPU {
    actual val shaderVersion: String = "300 es"
    actual val VERTEX_SHADER: Int = 0x8B31
    actual val FRAGMENT_SHADER: Int = 0x8B30
    actual val FLOAT: Int = 0x1406
    actual val ARRAY_BUFFER: Int = 0x8892
    actual val STATIC_DRAW: Int = 0x88E4
    actual val DYNAMIC_DRAW: Int = 0x88E8

    actual fun createShader(type: Int): Int = TODO("Phase 3")
    actual fun shaderSource(shader: Int, string: String): Unit = TODO("Phase 3")
    actual fun compileShader(type: Int): Unit = TODO("Phase 3")
    actual fun getCompileStatus(shader: Int): Int = TODO("Phase 3")
    actual fun getShaderInfoLog(shader: Int): String = TODO("Phase 3")
    actual fun deleteShader(shader: Int): Unit = TODO("Phase 3")

    actual fun createProgram(): Int = TODO("Phase 3")
    actual fun attachShader(program: Int, shader: Int): Unit = TODO("Phase 3")
    actual fun linkProgram(program: Int): Unit = TODO("Phase 3")
    actual fun getProgramLinkStatus(program: Int): Int = TODO("Phase 3")
    actual fun getProgramInfoLog(program: Int): String = TODO("Phase 3")
    actual fun deleteProgram(program: Int): Unit = TODO("Phase 3")

    actual fun getUniformLocation(program: Int, name: String): Int = TODO("Phase 3")
    actual fun getAttribLocation(program: Int, name: String): Int = TODO("Phase 3")

    actual fun putUniform1i(location: Int, v0: Int): Unit = TODO("Phase 3")
    actual fun putUniform1f(location: Int, v0: Float): Unit = TODO("Phase 3")
    actual fun putUniform2f(location: Int, v0: Float, v1: Float): Unit = TODO("Phase 3")
    actual fun putUniform4fv(location: Int, v: FloatArray, count: Int): Unit = TODO("Phase 3")

    actual fun putVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int): Unit = TODO("Phase 3")
    actual fun vertexAttribDivisor(index: Int, divisor: Int): Unit = TODO("Phase 3")

    actual fun genAndBindVertexArrays(): Int? = TODO("Phase 3")
    actual fun deleteVertexArrays(vao: Int): Unit = TODO("Phase 3")

    actual fun genBuffers(): Int = TODO("Phase 3")
    actual fun deleteBuffers(buffer: Int): Unit = TODO("Phase 3")

    actual fun genTextures(): Int = TODO("Phase 3")
    actual fun deleteTextures(texture: Int): Unit = TODO("Phase 3")
    actual fun activeTexture(unit: Int): Unit = TODO("Phase 3")
    actual fun bindTexture2D(texture: Int): Unit = TODO("Phase 3")
    actual fun configureTexture2DRepeatLinear(): Unit = TODO("Phase 3")
    actual fun uploadTextureR8(width: Int, height: Int, data: ByteArray): Unit = TODO("Phase 3")

    actual fun bindBuffer(target: Int, buffer: Int): Unit = TODO("Phase 3")
    actual fun bufferData(target: Int, count: Int, data: GpuFloatBuffer, usage: Int): Unit = TODO("Phase 3")

    actual fun setViewport(minX: Int, minY: Int, maxX: Int, maxY: Int): Unit = TODO("Phase 3")

    actual fun useProgram(program: Int): Unit = TODO("Phase 3")
    actual fun enableVertexAttribArray(v: Int): Unit = TODO("Phase 3")
    actual fun disableVertexAttribArray(v: Int): Unit = TODO("Phase 3")
    actual fun drawTriangles(first: Int, count: Int): Unit = TODO("Phase 3")
    actual fun drawTrianglesInstanced(first: Int, count: Int, instanceCount: Int): Unit = TODO("Phase 3")

    actual fun enableBlend(): Unit = TODO("Phase 3")
    actual fun disableBlend(): Unit = TODO("Phase 3")
    actual fun setBlendFuncSrcAlphaOneMinusSrcAlpha(): Unit = TODO("Phase 3")

    actual fun enableScissorTest(): Unit = TODO("Phase 3")
    actual fun disableScissorTest(): Unit = TODO("Phase 3")
    actual fun setScissor(x: Int, y: Int, width: Int, height: Int): Unit = TODO("Phase 3")
}
