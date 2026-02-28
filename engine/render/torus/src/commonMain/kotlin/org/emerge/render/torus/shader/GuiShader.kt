package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.ScreenLayout
import org.emerge.sim.core.physics.Vec2i

class GuiShader() {
    private val vSrc = GuiShaderSources.vertexGles2()
    private val fSrc = GuiShaderSources.fragmentGles2()
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)

    private val uResolution: Int = GPU.getUniformLocation(program, "uResolution")

    private var localResolution = Vec2i(1,1)

    fun useLayout(layout: ScreenLayout) {
        localResolution = layout.resolution
    }

    private fun setUniforms() {
        setResolution(localResolution)
    }

    private fun setResolution(resolution: Vec2i) = GPU.putUniform2f(
        uResolution,
        resolution.x.toFloat(),
        resolution.y.toFloat(),
    )

    fun draw(vOffset: Int = 0) {
        GPU.useProgram(program)
        setUniforms()
        GPU.drawTriangles(vOffset,4)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
    }
}
