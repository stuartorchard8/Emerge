package org.emerge.render.torus

import android.opengl.GLES20
import java.nio.FloatBuffer

actual object GPU {
    actual val shaderVersion: String = "320 es"
    actual val VERTEX_SHADER: Int = GLES20.GL_VERTEX_SHADER
    actual val FRAGMENT_SHADER: Int = GLES20.GL_FRAGMENT_SHADER
    actual val FLOAT: Int = GLES20.GL_FLOAT
    actual val ARRAY_BUFFER: Int = GLES20.GL_ARRAY_BUFFER
    actual val STATIC_DRAW: Int = GLES20.GL_STATIC_DRAW
    actual val DYNAMIC_DRAW: Int = GLES20.GL_DYNAMIC_DRAW
    actual fun createShader(type: Int) : Int = GLES20.glCreateShader(type)
    actual fun shaderSource(shader: Int, string: String) = GLES20.glShaderSource(shader, string)
    actual fun compileShader(type: Int) = GLES20.glCompileShader(type)
    actual fun getCompileStatus(shader: Int): Int {
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        return compiled[0]
    }
    actual fun getShaderInfoLog(shader: Int): String = GLES20.glGetShaderInfoLog(shader)
    actual fun deleteShader(shader: Int) = GLES20.glDeleteShader(shader)

    actual fun createProgram(): Int = GLES20.glCreateProgram()
    actual fun attachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    actual fun linkProgram(program: Int) = GLES20.glLinkProgram(program)
    actual fun getProgramLinkStatus(program: Int): Int {
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        return linkStatus[0]
    }
    actual fun getProgramInfoLog(program: Int): String = GLES20.glGetProgramInfoLog(program)
    actual fun deleteProgram(program: Int) = GLES20.glDeleteProgram(program)

    actual fun getUniformLocation(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)
    actual fun getAttribLocation(program: Int, name: String) = GLES20.glGetAttribLocation(program, name)

    actual fun putUniform1i(location: Int, v0: Int) = GLES20.glUniform1i(location, v0)
    actual fun putUniform1f(location: Int, v0: Float) = GLES20.glUniform1f(location, v0)
    actual fun putUniform2f(location: Int, v0: Float, v1: Float) = GLES20.glUniform2f(location, v0, v1)
    actual fun putUniform4fv(location: Int, v: FloatArray, count: Int) = GLES20.glUniform4fv(location, count, v, 0)

    actual fun putVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) = GLES20.glVertexAttribPointer(index, size, type, normalized, stride, offset)

    actual fun genAndBindVertexArrays(): Int? = null
    actual fun deleteVertexArrays(vao: Int) {}

    actual fun genBuffers(): Int {
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        return buffers[0]
    }
    actual fun deleteBuffers(buffer: Int) {
        val buffers = IntArray(1)
        buffers[0] = buffer
        GLES20.glDeleteBuffers(1, buffers, 0)
    }

    actual fun bindBuffer(target: Int, buffer: Int) = GLES20.glBindBuffer(target, buffer)
    actual fun bufferData(target: Int, count: Int, data: FloatBuffer, usage: Int) = GLES20.glBufferData(target, count*4, data, usage)

    actual fun setViewport(minX: Int, minY: Int, maxX: Int, maxY: Int) = GLES20.glViewport(minX, minY, maxX, maxY)

    actual fun useProgram(program: Int) = GLES20.glUseProgram(program)
    actual fun enableVertexAttribArray(v: Int) = GLES20.glEnableVertexAttribArray(v)
    actual fun disableVertexAttribArray(v: Int) = GLES20.glDisableVertexAttribArray(v)
    actual fun drawTriangles(first: Int, count: Int) = GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, first, count)
}
