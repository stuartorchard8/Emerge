package org.emerge.demo.physics

import android.opengl.GLES20

actual object TorusGlProgramFactory {
    actual fun createProgramGles2(maxBodies: Int): Int {
        val vs = TorusShaderSources.vertexGles2()
        val fs = TorusShaderSources.fragmentGles2(maxBodies)
        return linkProgram(
            vType = GLES20.GL_VERTEX_SHADER,
            vSrc = vs,
            fType = GLES20.GL_FRAGMENT_SHADER,
            fSrc = fs,
        )
    }

    actual fun createProgramGl330(maxBodies: Int): Int {
        error("GL330 is not supported on Android (use GLES2)")
    }

    private fun linkProgram(vType: Int, vSrc: String, fType: Int, fSrc: String): Int {
        val v = compileShader(vType, vSrc)
        val f = compileShader(fType, fSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            GLES20.glDeleteProgram(p)
            error("GL program link failed: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("GL shader compile failed: $log\n\n$src")
        }
        return s
    }
}

