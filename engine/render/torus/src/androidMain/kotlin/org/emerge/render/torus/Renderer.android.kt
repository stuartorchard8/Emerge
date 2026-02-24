package org.emerge.render.torus

import android.opengl.GLES20

actual object Renderer {
    actual val VERTEX_SHADER: Int = GLES20.GL_VERTEX_SHADER
    actual val FRAGMENT_SHADER: Int = GLES20.GL_FRAGMENT_SHADER
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
}
