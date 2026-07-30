package org.emerge.render.torus

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint8Array

/**
 * WebGL2 GPU backend.
 *
 * The common [GPU] expect API uses integer handles for GL objects (shaders, programs, buffers, etc.)
 * because that's how desktop OpenGL and GLES work. WebGL uses opaque JS object references instead,
 * so this implementation maintains bidirectional maps between allocated int IDs and the underlying
 * WebGL objects.
 *
 * Call [init] with a `WebGL2RenderingContext` (obtained from an HTML canvas) before using any
 * other function.
 */
actual object GPU {
    private var gl: dynamic = null

    private var nextId = 1
    private val shaderObjs = HashMap<Int, dynamic>()
    private val programObjs = HashMap<Int, dynamic>()
    private val bufferObjs = HashMap<Int, dynamic>()
    private val textureObjs = HashMap<Int, dynamic>()
    private val vaoObjs = HashMap<Int, dynamic>()
    private val framebufferObjs = HashMap<Int, dynamic>()
    private val uniformLocObjs = HashMap<Int, dynamic>()

    fun init(webgl2Context: dynamic) {
        gl = webgl2Context
    }

    private fun allocId(): Int = nextId++

    // -- Constants --

    actual val shaderVersion: String = "300 es"
    actual val VERTEX_SHADER: Int get() = gl.VERTEX_SHADER as Int
    actual val FRAGMENT_SHADER: Int get() = gl.FRAGMENT_SHADER as Int
    actual val FLOAT: Int get() = gl.FLOAT as Int
    actual val ARRAY_BUFFER: Int get() = gl.ARRAY_BUFFER as Int
    actual val STATIC_DRAW: Int get() = gl.STATIC_DRAW as Int
    actual val DYNAMIC_DRAW: Int get() = gl.DYNAMIC_DRAW as Int

    // -- Shaders --

    actual fun createShader(type: Int): Int {
        val obj = gl.createShader(type) ?: error("createShader failed")
        val id = allocId()
        shaderObjs[id] = obj
        return id
    }

    actual fun shaderSource(shader: Int, string: String) {
        gl.shaderSource(shaderObjs[shader], string)
    }

    actual fun compileShader(type: Int) {
        gl.compileShader(shaderObjs[type])
    }

    actual fun getCompileStatus(shader: Int): Int {
        val ok = gl.getShaderParameter(shaderObjs[shader], gl.COMPILE_STATUS)
        return if (ok as Boolean) 1 else 0
    }

    actual fun getShaderInfoLog(shader: Int): String {
        return (gl.getShaderInfoLog(shaderObjs[shader]) as? String) ?: ""
    }

    actual fun deleteShader(shader: Int) {
        val obj = shaderObjs.remove(shader)
        if (obj != null) gl.deleteShader(obj)
    }

    // -- Programs --

    actual fun createProgram(): Int {
        val obj = gl.createProgram() ?: error("createProgram failed")
        val id = allocId()
        programObjs[id] = obj
        return id
    }

    actual fun attachShader(program: Int, shader: Int) {
        gl.attachShader(programObjs[program], shaderObjs[shader])
    }

    actual fun linkProgram(program: Int) {
        gl.linkProgram(programObjs[program])
    }

    actual fun getProgramLinkStatus(program: Int): Int {
        val ok = gl.getProgramParameter(programObjs[program], gl.LINK_STATUS)
        return if (ok as Boolean) 1 else 0
    }

    actual fun getProgramInfoLog(program: Int): String {
        return (gl.getProgramInfoLog(programObjs[program]) as? String) ?: ""
    }

    actual fun deleteProgram(program: Int) {
        val obj = programObjs.remove(program)
        if (obj != null) gl.deleteProgram(obj)
    }

    // -- Uniforms & Attributes --

    actual fun getUniformLocation(program: Int, name: String): Int {
        val loc = gl.getUniformLocation(programObjs[program], name) ?: return -1
        val id = allocId()
        uniformLocObjs[id] = loc
        return id
    }

    actual fun getAttribLocation(program: Int, name: String): Int {
        return gl.getAttribLocation(programObjs[program], name) as Int
    }

    actual fun putUniform1i(location: Int, v0: Int) {
        gl.uniform1i(uniformLocObjs[location], v0)
    }

    actual fun putUniform1f(location: Int, v0: Float) {
        gl.uniform1f(uniformLocObjs[location], v0)
    }

    actual fun putUniform2f(location: Int, v0: Float, v1: Float) {
        gl.uniform2f(uniformLocObjs[location], v0, v1)
    }

    actual fun putUniform4fv(location: Int, v: FloatArray, count: Int) {
        val f32 = Float32Array(count * 4)
        f32.set(v.sliceArray(0 until count * 4).toTypedArray(), 0)
        gl.uniform4fv(uniformLocObjs[location], f32)
    }

    // -- Vertex attributes --

    actual fun putVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {
        gl.vertexAttribPointer(index, size, type, normalized, stride, offset)
    }

    actual fun vertexAttribDivisor(index: Int, divisor: Int) {
        gl.vertexAttribDivisor(index, divisor)
    }

    // -- VAOs --

    actual fun genAndBindVertexArrays(): Int? {
        val obj = gl.createVertexArray() ?: return null
        val id = allocId()
        vaoObjs[id] = obj
        gl.bindVertexArray(obj)
        return id
    }

    actual fun bindVertexArray(vao: Int?) {
        if (vao == null) {
            gl.bindVertexArray(null)
        } else {
            gl.bindVertexArray(vaoObjs[vao])
        }
    }
    actual fun deleteVertexArrays(vao: Int) {
        val obj = vaoObjs.remove(vao)
        if (obj != null) gl.deleteVertexArray(obj)
    }

    // -- Buffers --

    actual fun genBuffers(): Int {
        val obj = gl.createBuffer() ?: error("createBuffer failed")
        val id = allocId()
        bufferObjs[id] = obj
        return id
    }

    actual fun deleteBuffers(buffer: Int) {
        val obj = bufferObjs.remove(buffer)
        if (obj != null) gl.deleteBuffer(obj)
    }

    // -- Textures --

    actual fun genTextures(): Int {
        val obj = gl.createTexture() ?: error("createTexture failed")
        val id = allocId()
        textureObjs[id] = obj
        return id
    }

    actual fun deleteTextures(texture: Int) {
        val obj = textureObjs.remove(texture)
        if (obj != null) gl.deleteTexture(obj)
    }

    actual fun activeTexture(unit: Int) {
        gl.activeTexture(gl.TEXTURE0 + unit)
    }

    actual fun bindTexture2D(texture: Int) {
        gl.bindTexture(gl.TEXTURE_2D, if (texture == 0) null else textureObjs[texture])
    }

    actual fun configureTexture2DRepeatLinear() {
        val t2d = gl.TEXTURE_2D
        gl.texParameteri(t2d, gl.TEXTURE_WRAP_S, gl.REPEAT)
        gl.texParameteri(t2d, gl.TEXTURE_WRAP_T, gl.REPEAT)
        gl.texParameteri(t2d, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
        gl.texParameteri(t2d, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    }

    actual fun uploadTextureR8(width: Int, height: Int, data: ByteArray) {
        val u8 = Uint8Array(data.toTypedArray())
        gl.texImage2D(
            gl.TEXTURE_2D,
            0,
            gl.R8,
            width,
            height,
            0,
            gl.RED,
            gl.UNSIGNED_BYTE,
            u8,
        )
    }

    actual fun uploadTextureRGBA8(width: Int, height: Int, data: ByteArray) {
        val u8 = Uint8Array(data.toTypedArray())
        gl.texImage2D(
            gl.TEXTURE_2D,
            0,
            gl.RGBA8,
            width,
            height,
            0,
            gl.RGBA,
            gl.UNSIGNED_BYTE,
            u8,
        )
    }

    actual fun configureTexture2DClampNearest() {
        val t2d = gl.TEXTURE_2D
        gl.texParameteri(t2d, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
        gl.texParameteri(t2d, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
        gl.texParameteri(t2d, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
        gl.texParameteri(t2d, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
    }

    // -- Buffer data --

    actual fun bindBuffer(target: Int, buffer: Int) {
        gl.bindBuffer(target, if (buffer == 0) null else bufferObjs[buffer])
    }

    actual fun bufferData(target: Int, count: Int, data: GpuFloatBuffer, usage: Int) {
        val view = data.float32Array.subarray(0, data.limit)
        gl.bufferData(target, view, usage)
    }

    // -- Render targets --

    actual fun genFramebuffers(): Int {
        val obj = gl.createFramebuffer() ?: error("createFramebuffer failed")
        val id = allocId()
        framebufferObjs[id] = obj
        return id
    }

    actual fun deleteFramebuffers(fbo: Int) {
        val obj = framebufferObjs.remove(fbo)
        if (obj != null) gl.deleteFramebuffer(obj)
    }

    actual fun bindFramebuffer(fbo: Int) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, if (fbo == 0) null else framebufferObjs[fbo])
    }

    actual fun framebufferColorTexture2D(texture: Int) {
        gl.framebufferTexture2D(
            gl.FRAMEBUFFER,
            gl.COLOR_ATTACHMENT0,
            gl.TEXTURE_2D,
            if (texture == 0) null else textureObjs[texture],
            0,
        )
    }

    actual fun isFramebufferComplete(): Boolean =
        gl.checkFramebufferStatus(gl.FRAMEBUFFER) == gl.FRAMEBUFFER_COMPLETE

    actual fun allocateTextureRGBA8(width: Int, height: Int) {
        gl.texImage2D(
            gl.TEXTURE_2D,
            0,
            gl.RGBA8,
            width,
            height,
            0,
            gl.RGBA,
            gl.UNSIGNED_BYTE,
            null,
        )
    }

    actual fun setClearColor(r: Float, g: Float, b: Float, a: Float) {
        gl.clearColor(r, g, b, a)
    }

    actual fun clearColorBuffer() {
        gl.clear(gl.COLOR_BUFFER_BIT)
    }

    // -- Draw --

    actual fun setViewport(minX: Int, minY: Int, maxX: Int, maxY: Int) {
        gl.viewport(minX, minY, maxX, maxY)
    }

    actual fun useProgram(program: Int) {
        gl.useProgram(programObjs[program])
    }

    actual fun enableVertexAttribArray(v: Int) {
        gl.enableVertexAttribArray(v)
    }

    actual fun disableVertexAttribArray(v: Int) {
        gl.disableVertexAttribArray(v)
    }

    actual fun drawTriangles(first: Int, count: Int) {
        gl.drawArrays(gl.TRIANGLE_STRIP, first, count)
    }

    actual fun drawTrianglesInstanced(first: Int, count: Int, instanceCount: Int) {
        gl.drawArraysInstanced(gl.TRIANGLE_STRIP, first, count, instanceCount)
    }

    actual fun drawLines(first: Int, count: Int) {
        gl.drawArrays(gl.LINES, first, count)
    }

    actual fun drawLinesInstanced(first: Int, count: Int, instanceCount: Int) {
        gl.drawArraysInstanced(gl.LINES, first, count, instanceCount)
    }

    // -- Blending --

    actual fun enableBlend() {
        gl.enable(gl.BLEND)
    }

    actual fun disableBlend() {
        gl.disable(gl.BLEND)
    }

    actual fun setBlendFuncSrcAlphaOneMinusSrcAlpha() {
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
    }

    actual fun setBlendFuncSrcAlphaOne() {
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE)
    }

    actual fun setBlendFuncDstColorZero() {
        gl.blendFunc(gl.DST_COLOR, gl.ZERO)
    }

    // -- Scissor --

    actual fun enableScissorTest() {
        gl.enable(gl.SCISSOR_TEST)
    }

    actual fun disableScissorTest() {
        gl.disable(gl.SCISSOR_TEST)
    }

    actual fun setScissor(x: Int, y: Int, width: Int, height: Int) {
        gl.scissor(x, y, width, height)
    }
}
