package org.emerge.demo.drockets.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Fullscreen volumetric starfield background.
 * Renders on a fullscreen quad, replacing the engine's default WorldShader
 * when used in the Drockets demo.
 */
class StarscapeShader {
    private val program: Int = ShaderFactory.createProgram(
        StarscapeShaderSources.vertex(),
        StarscapeShaderSources.fragment(),
    )
    private val uBearing: Int = GPU.getUniformLocation(program, "uBearing")
    private val uResolution: Int = GPU.getUniformLocation(program, "uResolution")

    fun draw(vOffset: Int, bearing: Float, resolutionX: Float, resolutionY: Float) {
        GPU.useProgram(program)
        GPU.putUniform1f(uBearing, bearing)
        GPU.putUniform2f(uResolution, resolutionX, resolutionY)
        GPU.drawTriangles(vOffset, 4)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
    }
}
