package org.emerge.render.torus

expect object GPU {
    val shaderVersion: String
    val VERTEX_SHADER: Int
    val FRAGMENT_SHADER: Int
    val FLOAT: Int
    val ARRAY_BUFFER: Int
    val STATIC_DRAW: Int
    val DYNAMIC_DRAW: Int
    fun createShader(type: Int) : Int
    fun shaderSource(shader: Int, string: String)
    fun compileShader(type: Int)
    fun getCompileStatus(shader: Int): Int
    fun getShaderInfoLog(shader: Int): String
    fun deleteShader(shader: Int)

    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun getProgramLinkStatus(program: Int): Int
    fun getProgramInfoLog(program: Int): String
    fun deleteProgram(program: Int)

    fun getUniformLocation(program: Int, name: String): Int
    fun getAttribLocation(program: Int, name: String): Int

    fun putUniform1i(location: Int, v0: Int)
    fun putUniform1f(location: Int, v0: Float)
    fun putUniform2f(location: Int, v0: Float, v1: Float)
    fun putUniform4fv(location: Int, v: FloatArray, count: Int)

    fun putVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)
    fun vertexAttribDivisor(index: Int, divisor: Int)

    fun genAndBindVertexArrays(): Int?
    fun bindVertexArray(vao: Int?)
    fun deleteVertexArrays(vao: Int)

    fun genBuffers(): Int
    fun deleteBuffers(buffer: Int)

    fun genTextures(): Int
    fun deleteTextures(texture: Int)
    fun activeTexture(unit: Int)
    fun bindTexture2D(texture: Int)
    fun configureTexture2DRepeatLinear()
    fun uploadTextureR8(width: Int, height: Int, data: ByteArray)
    fun uploadTextureRGBA8(width: Int, height: Int, data: ByteArray)
    fun configureTexture2DClampNearest()
    fun configureTexture2DClampLinear()

    fun bindBuffer(target: Int, buffer: Int)
    fun bufferData(target: Int, count: Int, data: GpuFloatBuffer, usage: Int)

    // ── Render targets ───────────────────────────────────────────────────────────
    // Draw into a texture instead of the screen. Used to render one period of the torus world once and
    // then tile it, so a zoomed-out view repeats the world without redrawing its contents per repeat.

    fun genFramebuffers(): Int
    fun deleteFramebuffers(fbo: Int)

    /** Bind [fbo] as the draw target. Pass 0 to restore the default (screen) framebuffer — valid on every
     *  backend here (desktop GL, GLES via GLSurfaceView, WebGL2), none of which use a non-zero default. */
    fun bindFramebuffer(fbo: Int)

    /** Attach the currently-bound 2D [texture] as colour attachment 0 of the currently-bound framebuffer. */
    fun framebufferColorTexture2D(texture: Int)

    /** Whether the bound framebuffer is complete, i.e. safe to draw into. Check once after attaching. */
    fun isFramebufferComplete(): Boolean

    /** Size the bound 2D texture's RGBA8 storage without supplying pixels — the backing store for a render
     *  target, whose contents come from drawing into it rather than from an upload. */
    fun allocateTextureRGBA8(width: Int, height: Int)

    fun setClearColor(r: Float, g: Float, b: Float, a: Float)
    fun clearColorBuffer()

    fun setViewport(minX: Int, minY: Int, maxX: Int, maxY: Int)

    fun useProgram(program: Int)
    fun enableVertexAttribArray(v: Int)
    fun disableVertexAttribArray(v: Int)
    fun drawTriangles(first: Int, count: Int)
    fun drawTrianglesInstanced(first: Int, count: Int, instanceCount: Int)
    /** [count] is the number of vertices (must be even for independent line segments). */
    fun drawLines(first: Int, count: Int)

    /** GL_LINES instanced draw; base mesh uses [count] vertices per instance (typically 2). */
    fun drawLinesInstanced(first: Int, count: Int, instanceCount: Int)

    fun enableBlend()
    fun disableBlend()
    fun setBlendFuncSrcAlphaOneMinusSrcAlpha()
    /** Additive blend (src·srcAlpha + dst): for glows/particles that should only ever brighten. */
    fun setBlendFuncSrcAlphaOne()

    /** Multiply blending (`GL_DST_COLOR, GL_ZERO`): the drawn fragment scales what is already in the
     *  framebuffer instead of layering over it. A full-screen pass in this mode acts as a light term over the
     *  whole scene — every pixel already drawn gets multiplied, so one pass lights ground and cells alike. */
    fun setBlendFuncDstColorZero()

    fun enableScissorTest()
    fun disableScissorTest()
    fun setScissor(x: Int, y: Int, width: Int, height: Int)
}
