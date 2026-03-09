package org.emerge.render.torus

import android.opengl.GLES30
import java.nio.FloatBuffer

actual object GPU {
    actual val shaderVersion: String = "300 es"
    actual val VERTEX_SHADER: Int = GLES30.GL_VERTEX_SHADER
    actual val FRAGMENT_SHADER: Int = GLES30.GL_FRAGMENT_SHADER
    actual val FLOAT: Int = GLES30.GL_FLOAT
    actual val ARRAY_BUFFER: Int = GLES30.GL_ARRAY_BUFFER
    actual val STATIC_DRAW: Int = GLES30.GL_STATIC_DRAW
    actual val DYNAMIC_DRAW: Int = GLES30.GL_DYNAMIC_DRAW
    actual fun createShader(type: Int) : Int = GLES30.glCreateShader(type)
    actual fun shaderSource(shader: Int, string: String) = GLES30.glShaderSource(shader, string)
    actual fun compileShader(type: Int) = GLES30.glCompileShader(type)
    actual fun getCompileStatus(shader: Int): Int {
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        return compiled[0]
    }
    actual fun getShaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)
    actual fun deleteShader(shader: Int) = GLES30.glDeleteShader(shader)

    actual fun createProgram(): Int = GLES30.glCreateProgram()
    actual fun attachShader(program: Int, shader: Int) = GLES30.glAttachShader(program, shader)
    actual fun linkProgram(program: Int) = GLES30.glLinkProgram(program)
    actual fun getProgramLinkStatus(program: Int): Int {
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        return linkStatus[0]
    }
    actual fun getProgramInfoLog(program: Int): String = GLES30.glGetProgramInfoLog(program)
    actual fun deleteProgram(program: Int) = GLES30.glDeleteProgram(program)

    actual fun getUniformLocation(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
    actual fun getAttribLocation(program: Int, name: String) = GLES30.glGetAttribLocation(program, name)

    actual fun putUniform1i(location: Int, v0: Int) = GLES30.glUniform1i(location, v0)
    actual fun putUniform1f(location: Int, v0: Float) = GLES30.glUniform1f(location, v0)
    actual fun putUniform2f(location: Int, v0: Float, v1: Float) = GLES30.glUniform2f(location, v0, v1)
    actual fun putUniform4fv(location: Int, v: FloatArray, count: Int) = GLES30.glUniform4fv(location, count, v, 0)

    actual fun putVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        GLES30.glVertexAttribPointer(index, size, type, normalized, stride, offset)

    actual fun vertexAttribDivisor(index: Int, divisor: Int) = GLES30.glVertexAttribDivisor(index, divisor)

    actual fun genAndBindVertexArrays(): Int? {
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glBindVertexArray(vaos[0])
        return vaos[0]
    }
    actual fun deleteVertexArrays(vao: Int) {
        val vaos = intArrayOf(vao)
        GLES30.glDeleteVertexArrays(1, vaos, 0)
    }

    actual fun genBuffers(): Int {
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        return buffers[0]
    }
    actual fun deleteBuffers(buffer: Int) {
        val buffers = intArrayOf(buffer)
        GLES30.glDeleteBuffers(1, buffers, 0)
    }

    actual fun bindBuffer(target: Int, buffer: Int) = GLES30.glBindBuffer(target, buffer)
    actual fun bufferData(target: Int, count: Int, data: FloatBuffer, usage: Int) = GLES30.glBufferData(target, count * 4, data, usage)

    actual fun setViewport(minX: Int, minY: Int, maxX: Int, maxY: Int) = GLES30.glViewport(minX, minY, maxX, maxY)

    actual fun useProgram(program: Int) = GLES30.glUseProgram(program)
    actual fun enableVertexAttribArray(v: Int) = GLES30.glEnableVertexAttribArray(v)
    actual fun disableVertexAttribArray(v: Int) = GLES30.glDisableVertexAttribArray(v)
    actual fun drawTriangles(first: Int, count: Int) = GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, first, count)
    actual fun drawTrianglesInstanced(first: Int, count: Int, instanceCount: Int) =
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, first, count, instanceCount)

    actual fun enableBlend() = GLES30.glEnable(GLES30.GL_BLEND)
    actual fun disableBlend() = GLES30.glDisable(GLES30.GL_BLEND)
    actual fun setBlendFuncSrcAlphaOneMinusSrcAlpha() = GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    actual fun enableScissorTest() = GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
    actual fun disableScissorTest() = GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    actual fun setScissor(x: Int, y: Int, width: Int, height: Int) = GLES30.glScissor(x, y, width, height)
}
