package org.emerge.render.torus

import org.lwjgl.opengl.GL33C

actual object Renderer {
    actual val VERTEX_SHADER: Int = GL33C.GL_VERTEX_SHADER
    actual val FRAGMENT_SHADER: Int = GL33C.GL_FRAGMENT_SHADER
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
}
