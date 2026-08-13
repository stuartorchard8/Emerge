package org.emerge.render.torus

import org.lwjgl.opengl.GL33C
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import kotlin.use

actual object GPU {
    actual val shaderVersion: String = "330 core"
    actual val VERTEX_SHADER: Int = GL33C.GL_VERTEX_SHADER
    actual val FRAGMENT_SHADER: Int = GL33C.GL_FRAGMENT_SHADER
    actual val FLOAT: Int = GL33C.GL_FLOAT
    actual val ARRAY_BUFFER: Int = GL33C.GL_ARRAY_BUFFER
    actual val STATIC_DRAW: Int = GL33C.GL_STATIC_DRAW
    actual val DYNAMIC_DRAW: Int = GL33C.GL_DYNAMIC_DRAW
    actual fun createShader(type: Int) : Int = GL33C.glCreateShader(type)
    actual fun shaderSource(shader: Int, string: String) = GL33C.glShaderSource(shader, string)
    actual fun compileShader(type: Int) = GL33C.glCompileShader(type)
    actual fun getCompileStatus(shader: Int): Int = GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS)
    actual fun getShaderInfoLog(shader: Int): String = GL33C.glGetShaderInfoLog(shader)
    actual fun deleteShader(shader: Int) = GL33C.glDeleteShader(shader)

    actual fun createProgram(): Int = GL33C.glCreateProgram()
    actual fun attachShader(program: Int, shader: Int) = GL33C.glAttachShader(program, shader)
    actual fun linkProgram(program: Int) = GL33C.glLinkProgram(program)
    actual fun getProgramLinkStatus(program: Int) = GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS)
    actual fun getProgramInfoLog(program: Int): String = GL33C.glGetProgramInfoLog(program)
    actual fun deleteProgram(program: Int) = GL33C.glDeleteProgram(program)

    actual fun getUniformLocation(program: Int, name: String) = GL33C.glGetUniformLocation(program, name)
    actual fun getAttribLocation(program: Int, name: String) = GL33C.glGetAttribLocation(program, name)

    actual fun putUniform1i(location: Int, v0: Int) = GL33C.glUniform1i(location, v0)
    actual fun putUniform1f(location: Int, v0: Float) = GL33C.glUniform1f(location, v0)
    actual fun putUniform2f(location: Int, v0: Float, v1: Float) = GL33C.glUniform2f(location, v0, v1)
    actual fun putUniform4fv(location: Int, v: FloatArray, count: Int) {
        // TODO: manage memory better
        MemoryStack.stackPush().use { st ->
            val fb = st.mallocFloat(4 * count)
            fb.put(v, 0, 4 * count)
            fb.flip()
            GL33C.glUniform4fv(location, fb)
        }
    }

    actual fun putUniformMatrix4fv(location: Int, v: FloatArray) {
        MemoryStack.stackPush().use { st ->
            val fb = st.mallocFloat(Mat4.FLOATS)
            fb.put(v, 0, Mat4.FLOATS)
            fb.flip()
            GL33C.glUniformMatrix4fv(location, false, fb)
        }
    }

    actual fun putVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) = GL33C.glVertexAttribPointer(index, size, type, normalized, stride, offset.toLong())
    actual fun vertexAttribDivisor(index: Int, divisor: Int) = GL33C.glVertexAttribDivisor(index, divisor)

    actual fun genAndBindVertexArrays(): Int? {
        val vao = GL33C.glGenVertexArrays()
        GL33C.glBindVertexArray(vao)
        return vao
    }
    actual fun bindVertexArray(vao: Int?) {
        GL33C.glBindVertexArray(vao ?: 0)
    }
    actual fun deleteVertexArrays(vao: Int) = GL33C.glDeleteVertexArrays(vao)

    actual fun genBuffers(): Int = GL33C.glGenBuffers()
    actual fun deleteBuffers(buffer: Int) = GL33C.glDeleteBuffers(buffer)

    actual fun genTextures(): Int = GL33C.glGenTextures()
    actual fun deleteTextures(texture: Int) = GL33C.glDeleteTextures(texture)
    actual fun activeTexture(unit: Int) = GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit)
    actual fun bindTexture2D(texture: Int) = GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture)
    actual fun configureTexture2DRepeatLinear() {
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_REPEAT)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_REPEAT)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR)
    }
    actual fun uploadTextureR8(width: Int, height: Int, data: ByteArray) {
        // Off-heap (not MemoryStack): textures can exceed the ~64KB stack (e.g. a 256² RGBA density map).
        val buffer = MemoryUtil.memAlloc(data.size)
        try {
            buffer.put(data)
            buffer.flip()
            GL33C.glTexImage2D(
                GL33C.GL_TEXTURE_2D, 0, GL33C.GL_R8, width, height, 0,
                GL33C.GL_RED, GL33C.GL_UNSIGNED_BYTE, buffer,
            )
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }
    actual fun uploadTextureRGBA8(width: Int, height: Int, data: ByteArray) {
        // Off-heap (not MemoryStack): textures can exceed the ~64KB stack (e.g. a 256² RGBA density map).
        val buffer = MemoryUtil.memAlloc(data.size)
        try {
            buffer.put(data)
            buffer.flip()
            GL33C.glTexImage2D(
                GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, buffer,
            )
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }
    actual fun configureTexture2DClampNearest() {
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST)
    }
    actual fun configureTexture2DClampLinear() {
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR)
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR)
    }

    actual fun bindBuffer(target: Int, buffer: Int) = GL33C.glBindBuffer(target, buffer)
    actual fun bufferData(target: Int, count: Int, data: GpuFloatBuffer, usage: Int) = GL33C.glBufferData(target, data.nioBuffer, usage)

    actual fun genFramebuffers(): Int = GL33C.glGenFramebuffers()
    actual fun deleteFramebuffers(fbo: Int) = GL33C.glDeleteFramebuffers(fbo)
    actual fun bindFramebuffer(fbo: Int) = GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo)
    actual fun framebufferColorTexture2D(texture: Int) = GL33C.glFramebufferTexture2D(
        GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, texture, 0,
    )
    actual fun isFramebufferComplete(): Boolean =
        GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER) == GL33C.GL_FRAMEBUFFER_COMPLETE
    actual fun allocateTextureRGBA8(width: Int, height: Int) = GL33C.glTexImage2D(
        GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
        GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?,
    )

    actual fun setClearColor(r: Float, g: Float, b: Float, a: Float) = GL33C.glClearColor(r, g, b, a)
    actual fun clearColorBuffer() = GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT)

    actual fun setViewport(minX: Int, minY: Int, maxX: Int, maxY: Int) = GL33C.glViewport(minX, minY, maxX, maxY)

    actual fun useProgram(program: Int) = GL33C.glUseProgram(program)
    actual fun enableVertexAttribArray(v: Int) = GL33C.glEnableVertexAttribArray(v)
    actual fun disableVertexAttribArray(v: Int) = GL33C.glDisableVertexAttribArray(v)
    actual fun drawTriangles(first: Int, count: Int) = GL33C.glDrawArrays(GL33C.GL_TRIANGLE_STRIP, first, count)
    actual fun drawTrianglesInstanced(first: Int, count: Int, instanceCount: Int) =
        GL33C.glDrawArraysInstanced(GL33C.GL_TRIANGLE_STRIP, first, count, instanceCount)
    actual fun drawLines(first: Int, count: Int) = GL33C.glDrawArrays(GL33C.GL_LINES, first, count)
    actual fun drawLinesInstanced(first: Int, count: Int, instanceCount: Int) =
        GL33C.glDrawArraysInstanced(GL33C.GL_LINES, first, count, instanceCount)

    actual fun enableBlend() = GL33C.glEnable(GL33C.GL_BLEND)
    actual fun disableBlend() = GL33C.glDisable(GL33C.GL_BLEND)
    actual fun setBlendFuncSrcAlphaOneMinusSrcAlpha() = GL33C.glBlendFunc(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA)
    actual fun setBlendFuncSrcAlphaOne() = GL33C.glBlendFunc(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE)
    actual fun setBlendFuncDstColorZero() = GL33C.glBlendFunc(GL33C.GL_DST_COLOR, GL33C.GL_ZERO)

    actual fun enableScissorTest() = GL33C.glEnable(GL33C.GL_SCISSOR_TEST)
    actual fun disableScissorTest() = GL33C.glDisable(GL33C.GL_SCISSOR_TEST)
    actual fun setScissor(x: Int, y: Int, width: Int, height: Int) = GL33C.glScissor(x, y, width, height)
}
