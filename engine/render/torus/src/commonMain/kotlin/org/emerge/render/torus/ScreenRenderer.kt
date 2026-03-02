package org.emerge.render.torus

import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.GuiShader
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ScreenRenderer(val contentScale: Vec2) {
    private var zoom: Float = 1.0f // <1 => zoom out (see multiple tiles)

    private val vao = GPU.genAndBindVertexArrays()
    private var vbo: Int = GPU.genBuffers()

    private val worldShader = WorldShader()
    private val guiShader = GuiShader()
    private val circleShader = CircleShader()
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2i(1,1), contentScale)
    private val bodyInstanceMatrices = FloatArray(MAX_BODIES * MAT4_FLOATS)

    fun setResolution(resolution: Vec2i) {
        GPU.setViewport(0, 0, resolution.x, resolution.y)
        layout = ScreenLayout.compute(resolution, contentScale)
        layout.putVerts(vbo)
        worldShader.useLayout(layout)
        guiShader.useLayout(layout)
    }

    fun zoomOut() {
        zoom = max(1.0f, zoom * 0.98f)
    }
    fun zoomIn() {
        zoom = min(20f, zoom * 1.02f)
    }

    fun draw(state: PhysicsState, myId: PlayerId?) {
        val params = WorldShaderParams.compute(state, myId, zoom)
        worldShader.draw(params, segmentation=layout.worldSegmentation)
        guiShader.draw(vOffset=layout.guiVertexOffset)
        val n = packBodyInstanceMatrices(params, layout, outColMajor = bodyInstanceMatrices)
        circleShader.drawInstanced(vOffset = layout.circleVertexOffset, instanceCount = n, matricesColMajor = bodyInstanceMatrices)
    }

    fun cleanup() {
        worldShader.deleteProgram()
        guiShader.deleteProgram()
        circleShader.deleteProgram()
        GPU.deleteBuffers(vbo)
        if (vao != null) {
            GPU.deleteVertexArrays(vao)
        }
    }
    companion object {
        const val MAX_BODIES: Int = 1000
        private const val MAT4_FLOATS: Int = 16
    }

    private fun packBodyInstanceMatrices(
        params: WorldShaderParams,
        layout: ScreenLayout,
        outColMajor: FloatArray,
    ): Int {
        val bodies = params.bodies
        val n = min(MAX_BODIES, bodies.size)

        val res = layout.worldPxMax - layout.worldPxMin
        val aspect = res.x / res.y
        val minAspect = min(aspect, 1f)
        val maxAspect = max(aspect, 1f)

        val scaleVecX = params.worldSize.x * minAspect * params.zoom
        val scaleVecY = -params.worldSize.y / maxAspect * params.zoom
        val invAbsScaleVecY = 1f / abs(scaleVecY)

        for (i in 0 until n) {
            val b = bodies[i]
            val bx = b.pos.x.toFloat() / Int.MAX_VALUE
            val by = b.pos.y.toFloat() / Int.MAX_VALUE

            val dx = wrapDelta(bx - params.viewFocus.x, params.worldSize.x)
            val dy = wrapDelta(by - params.viewFocus.y, params.worldSize.y)

            val tx = 2f * dx / scaleVecX
            val ty = 2f * dy / scaleVecY

            val r = b.radius.toFloat() / Int.MAX_VALUE
            val sx = 2f * r / scaleVecX
            val sy = 2f * r * invAbsScaleVecY

            val base = i * MAT4_FLOATS
            // Column 0
            outColMajor[base + 0] = sx
            outColMajor[base + 1] = 0f
            outColMajor[base + 2] = 0f
            outColMajor[base + 3] = 0f
            // Column 1
            outColMajor[base + 4] = 0f
            outColMajor[base + 5] = sy
            outColMajor[base + 6] = 0f
            outColMajor[base + 7] = 0f
            // Column 2
            outColMajor[base + 8] = 0f
            outColMajor[base + 9] = 0f
            outColMajor[base + 10] = 1f
            outColMajor[base + 11] = 0f
            // Column 3 (translation)
            outColMajor[base + 12] = tx
            outColMajor[base + 13] = ty
            outColMajor[base + 14] = 0f
            outColMajor[base + 15] = 1f
        }
        return n
    }

    private fun wrapDelta(d: Float, size: Float): Float {
        val half = 0.5f * size
        val a = d + half
        val m = a - floor(a / size) * size
        return m - half
    }
}
