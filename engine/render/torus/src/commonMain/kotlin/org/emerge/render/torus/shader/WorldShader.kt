package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.ScreenLayout
import org.emerge.sim.core.physics.primitives.Vec2

class WorldShader {
    private val vSrc = WorldShaderSources.vertex()
    private val fSrc = WorldShaderSources.fragment()
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)

    private val uVpMin: Int = GPU.getUniformLocation(program, "uVpMin")
    private val uVpMax: Int = GPU.getUniformLocation(program, "uVpMax")
    private var vpMin = Vec2(0f,0f)
    private var vpMax = Vec2(1f, 1f)

    private val uWorld: Int = GPU.getUniformLocation(program, "uWorld")
    private val uZoom: Int = GPU.getUniformLocation(program, "uZoom")
    private val uCenter: Int = GPU.getUniformLocation(program, "uCenter")
    private val uRotation: Int = GPU.getUniformLocation(program, "uRotation")

    fun useLayout(layout: ScreenLayout) {
        vpMin = layout.worldPxMin
        vpMax = layout.worldPxMax
    }

    private fun setUniforms(params: WorldShaderParams) {
        setViewport()
        setWorld(params.worldSize)
        setZoom(params.zoom)
        setCenter(params.viewFocus)
        setRotation(params.viewRotationRad)
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
    private fun setWorld(size: Vec2) = GPU.putUniform2f(uWorld, size.x, size.y)
    private fun setZoom(value: Float) = GPU.putUniform1f(uZoom, value)
    private fun setCenter(center: Vec2) = GPU.putUniform2f(uCenter, center.x, center.y)
    private fun setRotation(rad: Float) = GPU.putUniform1f(uRotation, rad)

    fun draw(params: WorldShaderParams, vOffset: Int = 0, segmentation: Int = 0) {
        GPU.useProgram(program)
        setUniforms(params)
        for (x in 0..segmentation*segmentation) {
            GPU.drawTriangles(vOffset+x*4,4)
        }
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
    }
}
