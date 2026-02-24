package org.emerge.render.torus

import org.lwjgl.opengl.GL33C
import org.lwjgl.system.MemoryStack
import kotlin.use

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

    actual fun useProgram(program: Int) = GL33C.glUseProgram(program)
    actual fun drawTriangles(first: Int, count: Int) = GL33C.glDrawArrays(GL33C.GL_TRIANGLES, first, count)
}
