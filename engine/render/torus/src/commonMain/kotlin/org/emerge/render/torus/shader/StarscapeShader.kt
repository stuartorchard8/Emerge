package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer

/**
 * Fullscreen volumetric starfield background.
 *
 * Renders a fullscreen quad using the raymarched fractal starfield shader.
 * Self-contained — owns its VAO and vertex buffers.
 *
 * Usage (typically as a background layer before any scene drawing):
 * ```kotlin
 * val starscape = StarscapeShader()
 * starscape.draw(bearing = rotationRadians, resolutionX = w, resolutionY = h)
 * starscape.deleteProgram()
 * ```
 */
class StarscapeShader {
    private val program: Int = ShaderFactory.createProgram(
        StarscapeShaderSources.vertex(),
        StarscapeShaderSources.fragment(),
    )
    private val vao: Int? = GPU.genAndBindVertexArrays()
    private val vbo: Int = GPU.genBuffers()

    private val uBearing: Int = GPU.getUniformLocation(program, "uBearing")
    private val uResolution: Int = GPU.getUniformLocation(program, "uResolution")

    init {
        // Fullscreen quad: triangle strip, 4 verts
        val verts = floatArrayOf(
            -1f, 1f,
            -1f, -1f,
             1f, 1f,
             1f, -1f,
        )
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts, 0, verts.size).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun draw(bearing: Float, resolutionX: Float, resolutionY: Float) {
        GPU.useProgram(program)
        GPU.bindVertexArray(vao)
        GPU.putUniform1f(uBearing, bearing)
        GPU.putUniform2f(uResolution, resolutionX, resolutionY)
        GPU.drawTriangles(0, 4)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(vbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }
}
