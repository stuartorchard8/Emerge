package org.emerge.render.torus

import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.GuiShader
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

class ScreenRenderer(val contentScale: Vec2) {
    private var zoom: Float = 1.5f
    @Volatile private var worldRotationRad: Float = 0f

    private val vao = GPU.genAndBindVertexArrays()
    private var vbo: Int = GPU.genBuffers()

    private val worldShader = WorldShader()
    private val guiShader = GuiShader()
    private val circleShader = CircleShader()
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2(1f,1f), contentScale)
    private val bodyInstanceMatrices = FloatArray(MAX_BODIES * MAT4_FLOATS)
    private val bodyInstanceIds = FloatArray(MAX_BODIES)

    companion object {
        const val MAX_BODIES: Int = 50
        private const val MAT4_FLOATS: Int = 16
        private const val ROTATION_STEP_RAD: Float = 0.03f
    }

    fun setResolution(resolution: Vec2) {
        GPU.setViewport(0, 0, resolution.x.toInt(), resolution.y.toInt())
        layout = ScreenLayout.compute(resolution, contentScale)
        layout.putVerts(vbo)
        worldShader.useLayout(layout)
        guiShader.useLayout(layout)
    }

    fun zoomOut() {
        zoomByFactor(0.98f)
    }
    fun zoomIn() {
        zoomByFactor(1.02f)
    }

    fun zoomByFactor(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) {
            return
        }
        zoom = (zoom * factor).coerceIn(1.5f, 20f)
    }

    fun rotateLeft() {
        rotateBy(ROTATION_STEP_RAD)
    }
    fun rotateRight() {
        rotateBy(-ROTATION_STEP_RAD)
    }

    fun rotateBy(deltaRad: Float) {
        if (!deltaRad.isFinite()) {
            return
        }
        worldRotationRad += deltaRad
    }

    fun rotateInputToWorld(input: PhysicsInput): PhysicsInput {
        val rot = worldRotationRad
        if (rot == 0f) {
            return input
        }

        val c = cos(-rot).toDouble()
        val s = sin(-rot).toDouble()
        val ax = input.ax.toDouble()
        val ay = input.ay.toDouble()

        val worldAx = clampToInt(ax * c - ay * s)
        val worldAy = clampToInt(ax * s + ay * c)
        return PhysicsInput(worldAx, worldAy)
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

    private fun packBodyInstances(
        params: WorldShaderParams,
        layout: ScreenLayout,
        outMatricesColMajor: FloatArray,
        outIds: FloatArray,
    ): Int {
        val bodies = params.bodies
        val n = min(MAX_BODIES, bodies.size)

        // Calculate view matrix once
        val matTmp = FloatArray(MAT4_FLOATS)
        val matT = FloatArray(MAT4_FLOATS)
        val matR = FloatArray(MAT4_FLOATS)
        val matS = FloatArray(MAT4_FLOATS)
        val matView = FloatArray(MAT4_FLOATS)

        // Rotate then Scale
        setRotationZ(matR, params.viewRotationRad)
        val worldPxSize = Vec2(layout.resolution.x, layout.resolution.y)
        val aspect = worldPxSize.x / worldPxSize.y
        val minAspect = min(aspect, 1f)
        val maxAspect = max(aspect, 1f)
        val scaleVecX = params.worldSize.x * 0.5f / minAspect / params.zoom
        val scaleVecY = -params.worldSize.y * 0.5f * maxAspect / params.zoom
        setScale(matS, scaleVecX, scaleVecY)
        multiply4x4(out = matTmp, a = matS, b = matR)

        // Translate
        val worldNdcMin = layout.pxToNdc(layout.worldPxMin)
        val worldNdcMax = layout.pxToNdc(layout.worldPxMax)
        val viewCenterX = (worldNdcMax.x + worldNdcMin.x) * 0.5f
        val viewCenterY = (worldNdcMax.y + worldNdcMin.y) * 0.5f
        setTranslation(matT, viewCenterX, viewCenterY)
        multiply4x4(out = matView, a = matT, b = matTmp)

        val matModel = FloatArray(MAT4_FLOATS)
        for (i in 0 until n) {
            val b = bodies[i]
            val bx = b.pos.x.toFloat()
            val by = b.pos.y.toFloat()

            // Scale then Rotate
            val bodyScale = b.radius.toFloat()
            setScale(matS, bodyScale, bodyScale)
            val bodyRotRad = -b.ang.toFloat() * 2f * PI.toFloat()
            setRotationZ(matR, bodyRotRad)
            multiply4x4(out = matTmp, a = matR, b = matS)

            // Translate
            val dx = wrapDelta(bx - params.viewFocus.x, params.worldSize.x)
            val dy = wrapDelta(by - params.viewFocus.y, params.worldSize.y)
            setTranslation(matT, dx, dy)
            multiply4x4(out = matModel, a = matT, b = matTmp)

            // Apply view
            multiply4x4(out = matTmp, a = matView, b = matModel)

            val base = i * MAT4_FLOATS
            copyMatrix(out = outMatricesColMajor, outOffset = base, src = matTmp)

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

    private fun clampToInt(value: Double): Int {
        if (value <= Int.MIN_VALUE.toDouble()) return Int.MIN_VALUE
        if (value >= Int.MAX_VALUE.toDouble()) return Int.MAX_VALUE
        return value.roundToLong().toInt()
    }

    private fun setTranslation(out: FloatArray, tx: Float, ty: Float) {
        setIdentity(out)
        out[12] = tx
        out[13] = ty
    }

    private fun setScale(out: FloatArray, sx: Float, sy: Float) {
        setIdentity(out)
        out[0] = sx
        out[5] = sy
    }

    private fun setRotationZ(out: FloatArray, rad: Float) {
        setIdentity(out)
        val c = cos(rad)
        val s = sin(rad)
        out[0] = c
        out[1] = s
        out[4] = -s
        out[5] = c
    }

    private fun setIdentity(out: FloatArray) {
        for (i in 0 until MAT4_FLOATS) out[i] = 0f
        out[0] = 1f
        out[5] = 1f
        out[10] = 1f
        out[15] = 1f
    }

    // Column-major 4x4 multiplication: out = a * b.
    private fun multiply4x4(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0..3) {
            val b0 = b[col * 4 + 0]
            val b1 = b[col * 4 + 1]
            val b2 = b[col * 4 + 2]
            val b3 = b[col * 4 + 3]
            out[col * 4 + 0] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
            out[col * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
            out[col * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
            out[col * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
        }
    }

    private fun copyMatrix(out: FloatArray, outOffset: Int, src: FloatArray) {
        src.copyInto(out, destinationOffset = outOffset, startIndex = 0, endIndex = MAT4_FLOATS)
    }
}
