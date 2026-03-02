package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.ScreenLayout
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i

class CircleShader {
    private val vSrc = CircleShaderSources.vertex()
    private val fSrc = CircleShaderSources.fragment()
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)

    fun draw(vOffset: Int = 0) {
        GPU.useProgram(program)
        GPU.drawTriangles(vOffset,3)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
    }
}
