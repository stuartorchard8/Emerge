package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.ScreenLayout
import org.emerge.render.torus.packBodiesToFloatArray
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Vec2
import kotlin.math.min

class WorldShader(val maxBodies: Int) {
    private val vSrc = WorldShaderSources.vertexGles2()
    private val fSrc = WorldShaderSources.fragmentGles2(maxBodies)
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)

    private val uVpMin: Int = GPU.getUniformLocation(program, "uVpMin")
    private val uVpMax: Int = GPU.getUniformLocation(program, "uVpMax")
    private var vpMin = Vec2(0f,0f)
    private var vpMax = Vec2(1f, 1f)

    private val uWorld: Int = GPU.getUniformLocation(program, "uWorld")
    private val uZoom: Int = GPU.getUniformLocation(program, "uZoom")
    private val uCenter: Int = GPU.getUniformLocation(program, "uCenter")
    private val uBodyCount: Int = GPU.getUniformLocation(program, "uBodyCount")
    private val uMyId: Int = GPU.getUniformLocation(program, "uMyId")
    private val uBodies: Int = GPU.getUniformLocation(program, "uBodies")

    private val bodiesFloats = FloatArray(4 * maxBodies)

    fun useLayout(layout: ScreenLayout) {
        vpMin = layout.worldPxMin
        vpMax = layout.worldPxMax
    }

    private fun setUniforms(params: WorldShaderParams) {
        setViewport()
        setWorld(params.worldSize)
        setZoom(params.zoom)
        setCenter(params.viewFocus)
        setMyId(params.myId?.value ?: -1)
        setBodies(params.bodies)
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
    private fun setMyId(value: Int) = GPU.putUniform1i(uMyId, value)
    private fun setBodies(bodies: List<CircleBody>) {
        val n = min(maxBodies, bodies.size)
        GPU.putUniform1i(uBodyCount, n)
        packBodiesToFloatArray(bodies, maxBodies, out = bodiesFloats)
        GPU.putUniform4fv(uBodies, bodiesFloats, maxBodies)
    }

    fun draw(params: WorldShaderParams, vOffset: Int = 0, segmentation: Int = 0) {
        GPU.useProgram(program)
        setUniforms(params)
        for (x in 0..segmentation*segmentation/2) {
            GPU.drawTriangles(vOffset+x*2*4,4)
        }
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
    }
}
