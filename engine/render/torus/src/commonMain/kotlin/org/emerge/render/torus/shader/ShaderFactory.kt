package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU

/**
 * Platform-specific OpenGL program compilation/linking for the torus shader renderer.
 *
 * - Android: OpenGL ES 2.0 (GLES20)
 * - Desktop JVM: OpenGL 3.3 core via LWJGL (GL33C)
 *
 * Both return an integer program id.
 */
object ShaderFactory {
    fun createProgram(vSrc: String, fSrc: String): Int {
        val v = compileShader(GPU.VERTEX_SHADER, vSrc)
        val f = compileShader(GPU.FRAGMENT_SHADER, fSrc)
        val p = GPU.createProgram()
        GPU.attachShader(p, v)
        GPU.attachShader(p, f)
        GPU.linkProgram(p)
        val ok = GPU.getProgramLinkStatus(p)
        if (ok == 0) {
            val log = GPU.getProgramInfoLog(p)
            GPU.deleteShader(v)
            GPU.deleteShader(f)
            GPU.deleteProgram(p)
            error("GL program link failed: $log")
        }
        GPU.deleteShader(v)
        GPU.deleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GPU.createShader(type)
        GPU.shaderSource(s, src)
        GPU.compileShader(s)
        val ok = GPU.getCompileStatus(s)
        if (ok == 0) {
            val log = GPU.getShaderInfoLog(s)
            GPU.deleteShader(s)
            error("Shader compile failed:\n$log\n\nSource:\n$src")
        }
        return s
    }
}
