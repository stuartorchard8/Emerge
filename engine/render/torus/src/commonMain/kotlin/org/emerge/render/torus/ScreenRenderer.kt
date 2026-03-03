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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ScreenRenderer(val contentScale: Vec2) {
    private var zoom: Float = 1.0f // <1 => zoom out (see multiple tiles)
    private var worldRotationRad: Float = 0f

    private val vao = GPU.genAndBindVertexArrays()
    private var vbo: Int = GPU.genBuffers()

    private val worldShader = WorldShader()
    private val guiShader = GuiShader()
    private val circleShader = CircleShader()
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2i(1,1), contentScale)
    private val bodyInstanceMatrices = FloatArray(MAX_BODIES * MAT4_FLOATS)
    private val bodyInstanceIds = FloatArray(MAX_BODIES)

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

    fun rotateLeft() {
        worldRotationRad -= ROTATION_STEP_RAD
    }
    fun rotateRight() {
        worldRotationRad += ROTATION_STEP_RAD
    }

    fun draw(state: PhysicsState, myId: PlayerId?) {
        val params = WorldShaderParams.compute(state, myId, zoom, worldRotationRad)
        worldShader.draw(params, segmentation=layout.worldSegmentation)
        guiShader.draw(vOffset=layout.guiVertexOffset)
        val n =
            packBodyInstances(
                params = params,
                layout = layout,
                outMatricesColMajor = bodyInstanceMatrices,
                outIds = bodyInstanceIds,
            )

        val x0 = floor(layout.worldPxMin.x).toInt()
        val y0 = floor(layout.worldPxMin.y).toInt()
        val x1 = ceil(layout.worldPxMax.x).toInt()
        val y1 = ceil(layout.worldPxMax.y).toInt()
        val w = max(0, x1 - x0)
        val h = max(0, y1 - y0)
        GPU.enableScissorTest()
        GPU.setScissor(x0, y0, w, h)
        circleShader.drawInstanced(
            vOffset = layout.circleVertexOffset,
            instanceCount = n,
            matricesColMajor = bodyInstanceMatrices,
            ids = bodyInstanceIds,
        )
        GPU.disableScissorTest()
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
        private const val ROTATION_STEP_RAD: Float = 0.03f
    }

    private fun packBodyInstances(
        params: WorldShaderParams,
        layout: ScreenLayout,
        outMatricesColMajor: FloatArray,
        outIds: FloatArray,
    ): Int {
        val bodies = params.bodies
        val n = min(MAX_BODIES, bodies.size)

        // Work in "world viewport local clip space" first ([-1,1] in each axis),
        // then map into the world-viewport sub-rectangle inside full-screen NDC.
        val worldPxSize = layout.worldPxMax - layout.worldPxMin
        val aspect = worldPxSize.x / worldPxSize.y
        val minAspect = min(aspect, 1f)
        val maxAspect = max(aspect, 1f)

        val worldNdcMin = layout.pxToNdc(layout.worldPxMin)
        val worldNdcMax = layout.pxToNdc(layout.worldPxMax)
        val viewScaleX = (worldNdcMax.x - worldNdcMin.x) * 0.5f
        val viewScaleY = (worldNdcMax.y - worldNdcMin.y) * 0.5f
        val viewCenterX = (worldNdcMax.x + worldNdcMin.x) * 0.5f
        val viewCenterY = (worldNdcMax.y + worldNdcMin.y) * 0.5f

        val scaleVecX = params.worldSize.x * minAspect * params.zoom
        val scaleVecY = -params.worldSize.y / maxAspect * params.zoom
        val invAbsScaleVecY = 1f / abs(scaleVecY)

        for (i in 0 until n) {
            val b = bodies[i]
            val bx = b.pos.x.toFloat() / Int.MAX_VALUE
            val by = b.pos.y.toFloat() / Int.MAX_VALUE

            val dx = wrapDelta(bx - params.viewFocus.x, params.worldSize.x)
            val dy = wrapDelta(by - params.viewFocus.y, params.worldSize.y)

            var dxRot = dx
            var dyRot = dy
            val rot = params.viewRotationRad
            if (rot != 0f) {
                val c = cos(rot)
                val s = sin(rot)
                val dxRotLocal = dx * c - dy * s
                val dyRotLocal = dx * s + dy * c
                dxRot = dxRotLocal
                dyRot = dyRotLocal
            }
            val txLocal = 2f * dxRot / scaleVecX
            val tyLocal = 2f * dyRot / scaleVecY

            val r = b.radius.toFloat() / Int.MAX_VALUE
            val sxLocal = 2f * r / scaleVecX
            val syLocal = 2f * r * invAbsScaleVecY

            val tx = viewScaleX * txLocal + viewCenterX
            val ty = viewScaleY * tyLocal + viewCenterY
            val sx = viewScaleX * sxLocal
            val sy = viewScaleY * syLocal

            val base = i * MAT4_FLOATS
            // Column 0
            outMatricesColMajor[base + 0] = sx
            outMatricesColMajor[base + 1] = 0f
            outMatricesColMajor[base + 2] = 0f
            outMatricesColMajor[base + 3] = 0f
            // Column 1
            outMatricesColMajor[base + 4] = 0f
            outMatricesColMajor[base + 5] = sy
            outMatricesColMajor[base + 6] = 0f
            outMatricesColMajor[base + 7] = 0f
            // Column 2
            outMatricesColMajor[base + 8] = 0f
            outMatricesColMajor[base + 9] = 0f
            outMatricesColMajor[base + 10] = 1f
            outMatricesColMajor[base + 11] = 0f
            // Column 3 (translation)
            outMatricesColMajor[base + 12] = tx
            outMatricesColMajor[base + 13] = ty
            outMatricesColMajor[base + 14] = 0f
            outMatricesColMajor[base + 15] = 1f

            outIds[i] = b.playerId.value.toFloat()
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
