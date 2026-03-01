package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.ScreenLayout
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i

class GuiShader() {
    private val vSrc = GuiShaderSources.vertexGles2()
    private val fSrc = GuiShaderSources.fragmentGles2()
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)

    private val uVpMin: Int = GPU.getUniformLocation(program, "uVpMin")
    private val uVpMax: Int = GPU.getUniformLocation(program, "uVpMax")

    private var vpMin = Vec2(0f,0f)
    private var vpMax = Vec2(1f, 1f)

    fun useLayout(layout: ScreenLayout) {
        vpMin = layout.guiPxMin
        vpMax = layout.guiPxMax
    }

    private fun setUniforms() {
        setViewport()
    }

    private fun setViewport() {
        GPU.putUniform2f(
            uVpMin,
            vpMin.x,
            vpMin.y,
        )
        GPU.putUniform2f(
            uVpMax,
            vpMax.x,
            vpMax.y,
        )
    }

    fun draw(vOffset: Int = 0) {
        GPU.useProgram(program)
        setUniforms()
        GPU.drawTriangles(vOffset,4)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
    }
}
