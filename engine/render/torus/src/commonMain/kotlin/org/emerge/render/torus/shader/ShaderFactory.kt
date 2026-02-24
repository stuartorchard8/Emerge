package org.emerge.render.torus.shader

import org.emerge.render.torus.Renderer

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
        val v = compileShader(Renderer.VERTEX_SHADER, vSrc)
        val f = compileShader(Renderer.FRAGMENT_SHADER, fSrc)
        val p = Renderer.createProgram()
        Renderer.attachShader(p, v)
        Renderer.attachShader(p, f)
        Renderer.linkProgram(p)
        val ok = Renderer.getProgramLinkStatus(p)
        if (ok == 0) {
            val log = Renderer.getProgramInfoLog(p)
            Renderer.deleteShader(v)
            Renderer.deleteShader(f)
            Renderer.deleteProgram(p)
            error("GL program link failed: $log")
        }
        Renderer.deleteShader(v)
        Renderer.deleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = Renderer.createShader(type)
        Renderer.shaderSource(s, src)
        Renderer.compileShader(s)
        val ok = Renderer.getCompileStatus(s)
        if (ok == 0) {
            val log = Renderer.getShaderInfoLog(s)
            Renderer.deleteShader(s)
            error("Shader compile failed:\n$log\n\nSource:\n$src")
        }
        return s
    }
}
