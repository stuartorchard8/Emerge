package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.ScreenLayout
import org.emerge.render.torus.packBodiesToFloatArray
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i
import kotlin.math.max
import kotlin.math.min

class WorldShader(val maxBodies: Int) {
    private val vSrc = WorldShaderSources.vertexGles2()
    private val fSrc = WorldShaderSources.fragmentGles2(maxBodies)
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)

    private val uResolution: Int = GPU.getUniformLocation(program, "uResolution")
    private val uWorld: Int = GPU.getUniformLocation(program, "uWorld")
    private val uZoom: Int = GPU.getUniformLocation(program, "uZoom")
    private val uCenter: Int = GPU.getUniformLocation(program, "uCenter")
    private val uBodyCount: Int = GPU.getUniformLocation(program, "uBodyCount")
    private val uMyId: Int = GPU.getUniformLocation(program, "uMyId")
    private val uBodies: Int = GPU.getUniformLocation(program, "uBodies")

    private val bodiesFloats = FloatArray(4 * maxBodies)

    private var localResolution = Vec2i(1,1)
    private var focusOffset = Vec2(0f,0f)

    fun useLayout(layout: ScreenLayout) {
        localResolution = layout.resolution
        // Offset focus based on viewport center
        val worldViewportCenter = layout.getWorldCenter()
        focusOffset = Vec2(
            -worldViewportCenter.x * min(layout.aspectRatio, 1f),
            worldViewportCenter.y / max(layout.aspectRatio, 1f),
        )
    }

    private fun setUniforms(params: WorldShaderParams) {
        setResolution(localResolution)
        setWorld(params.worldSize)
        setZoom(params.zoom)
        setCenter(params.viewFocus + focusOffset/params.zoom)
        setMyId(params.myId?.value ?: -1)
        setBodies(params.bodies)
    }
    private fun setResolution(resolution: Vec2i) = GPU.putUniform2f(
        uResolution,
        resolution.x.toFloat(),
        resolution.y.toFloat(),
    )
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

    fun draw(params: WorldShaderParams, vOffset: Int = 0) {
        GPU.useProgram(program)
        setUniforms(params)
        GPU.drawTriangles(vOffset,4)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
    }
}
