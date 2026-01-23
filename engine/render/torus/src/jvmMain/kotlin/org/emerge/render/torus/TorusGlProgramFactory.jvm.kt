package org.emerge.render.torus

import org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL33C.GL_LINK_STATUS
import org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL33C.glAttachShader
import org.lwjgl.opengl.GL33C.glCompileShader
import org.lwjgl.opengl.GL33C.glCreateProgram
import org.lwjgl.opengl.GL33C.glCreateShader
import org.lwjgl.opengl.GL33C.glDeleteProgram
import org.lwjgl.opengl.GL33C.glDeleteShader
import org.lwjgl.opengl.GL33C.glGetProgramInfoLog
import org.lwjgl.opengl.GL33C.glGetProgrami
import org.lwjgl.opengl.GL33C.glGetShaderInfoLog
import org.lwjgl.opengl.GL33C.glGetShaderi
import org.lwjgl.opengl.GL33C.glLinkProgram
import org.lwjgl.opengl.GL33C.glShaderSource

actual object TorusGlProgramFactory {
    actual fun createProgramGles2(maxBodies: Int): Int {
        error("GLES2 is not supported on desktop JVM (use GL330)")
    }

    actual fun createProgramGl330(maxBodies: Int): Int {
        val vs = TorusShaderSources.vertexGl330()
        val fs = TorusShaderSources.fragmentGl330(maxBodies)
        return buildProgram(vs, fs)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compileShader(GL_VERTEX_SHADER, vs)
        val f = compileShader(GL_FRAGMENT_SHADER, fs)
        val p = glCreateProgram()
        glAttachShader(p, v)
        glAttachShader(p, f)
        glLinkProgram(p)
        val ok = glGetProgrami(p, GL_LINK_STATUS)
        if (ok == 0) {
            val log = glGetProgramInfoLog(p)
            glDeleteShader(v)
            glDeleteShader(f)
            glDeleteProgram(p)
            error("Program link failed:\n$log")
        }
        glDeleteShader(v)
        glDeleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = glGetShaderi(s, GL_COMPILE_STATUS)
        if (ok == 0) {
            val log = glGetShaderInfoLog(s)
            glDeleteShader(s)
            error("Shader compile failed:\n$log\n\nSource:\n$src")
        }
        return s
    }
}

