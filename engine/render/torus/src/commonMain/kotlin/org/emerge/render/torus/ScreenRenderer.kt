package org.emerge.render.torus

import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.GuiShader
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.physics.BodyShape
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
import kotlin.math.sin

class ScreenRenderer(val contentScale: Vec2) {
    private var zoom: Float = 10f
    @Volatile private var worldRotationRad: Float = 0f

    private val vao = GPU.genAndBindVertexArrays()
    private var vbo: Int = GPU.genBuffers()

    private val worldShader = WorldShader()
    private val guiShader = GuiShader()
    private val circleShader = CircleShader()
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2(1f,1f), contentScale)
    private val bodyInstanceMatrices = FloatArray(MAX_RENDER_BODIES * MAT4_FLOATS)
    private val bodyInstancePrimaryIds = FloatArray(MAX_RENDER_BODIES)
    private val bodyInstanceSecondaryIds = FloatArray(MAX_RENDER_BODIES)
    private val bodyInstanceShapes = FloatArray(MAX_RENDER_BODIES)
    private val bodyInstanceAlphas = FloatArray(MAX_RENDER_BODIES)
    private val bodyInstanceRadii = FloatArray(MAX_RENDER_BODIES)
    private val matTmp = FloatArray(MAT4_FLOATS)
    private val matT = FloatArray(MAT4_FLOATS)
    private val matR = FloatArray(MAT4_FLOATS)
    private val matS = FloatArray(MAT4_FLOATS)
    private val matView = FloatArray(MAT4_FLOATS)
    private val matModel = FloatArray(MAT4_FLOATS)

    companion object {
        const val MAX_BODIES: Int = 100
        private const val MAX_RENDER_BODIES: Int = MAX_BODIES * 2
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
        return input
    }

    fun draw(state: PhysicsState, myId: PlayerId?) {
        val params = WorldShaderParams.compute(state, myId, zoom, worldRotationRad)
        worldShader.draw(params, segmentation=layout.worldSegmentation)
        guiShader.draw(vOffset=layout.guiVertexOffset)
        val n =
            packBodyInstances(
                state = state,
                params = params,
                layout = layout,
                outMatricesColMajor = bodyInstanceMatrices,
                outPrimaryIds = bodyInstancePrimaryIds,
                outSecondaryIds = bodyInstanceSecondaryIds,
                outShapes = bodyInstanceShapes,
                outAlphas = bodyInstanceAlphas,
                outRadii = bodyInstanceRadii,
            )

        val x0 = floor(layout.worldPxMin.x).toInt()
        val y0 = floor(layout.worldPxMin.y).toInt()
        val x1 = ceil(layout.worldPxMax.x).toInt()
        val y1 = ceil(layout.worldPxMax.y).toInt()
        val w = max(0, x1 - x0)
        val h = max(0, y1 - y0)
        GPU.enableScissorTest()
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        GPU.setScissor(x0, y0, w, h)
        circleShader.drawInstanced(
            vOffset = layout.circleVertexOffset,
            instanceCount = n,
            matricesColMajor = bodyInstanceMatrices,
            primaryIds = bodyInstancePrimaryIds,
            secondaryIds = bodyInstanceSecondaryIds,
            shapes = bodyInstanceShapes,
            alphas = bodyInstanceAlphas,
            radii = bodyInstanceRadii,
        )
        GPU.disableBlend()
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
        state: PhysicsState,
        params: WorldShaderParams,
        layout: ScreenLayout,
        outMatricesColMajor: FloatArray,
        outPrimaryIds: FloatArray,
        outSecondaryIds: FloatArray,
        outShapes: FloatArray,
        outAlphas: FloatArray,
        outRadii: FloatArray,
    ): Int {
        // Calculate view matrix once
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

        var n = 0
        for (entityId in state.world.entities) {
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val renderShape = state.renderShapes[entityId] ?: continue
            val primaryId = shaderId(state.teams[entityId]?.teamId?.value)
            val secondaryId = shaderId(state.playerOwned[entityId]?.playerId?.value)
            n = packBodyInstance(
                index = n,
                primaryId = primaryId,
                secondaryId = secondaryId,
                posX = transform.pos.x.toFloat(),
                posY = transform.pos.y.toFloat(),
                angleTurns = transform.ang.toFloat(),
                radius = collider.radius.toFloat(),
                shape = renderShape.shape,
                alpha = 1f,
                params = params,
                outMatricesColMajor = outMatricesColMajor,
                outPrimaryIds = outPrimaryIds,
                outSecondaryIds = outSecondaryIds,
                outShapes = outShapes,
                outAlphas = outAlphas,
                outRadii = outRadii,
            )
            if (n >= MAX_RENDER_BODIES) {
                break
            }
            val forceField = state.forceFields[entityId]
            if (forceField != null && renderShape.shape == BodyShape.CIRCLE) {
                n = packBodyInstance(
                    index = n,
                    primaryId = primaryId,
                    secondaryId = secondaryId,
                    posX = transform.pos.x.toFloat(),
                    posY = transform.pos.y.toFloat(),
                    angleTurns = transform.ang.toFloat(),
                    radius = (collider.radius + forceField.depth).toFloat(),
                    shape = renderShape.shape,
                    alpha = forceField.alpha.toFloat(),
                    params = params,
                    outMatricesColMajor = outMatricesColMajor,
                    outPrimaryIds = outPrimaryIds,
                    outSecondaryIds = outSecondaryIds,
                    outShapes = outShapes,
                    outAlphas = outAlphas,
                    outRadii = outRadii,
                )
                if (n >= MAX_RENDER_BODIES) {
                    break
                }
            }
        }
        return n
    }

    private fun packBodyInstance(
        index: Int,
        primaryId: Float,
        secondaryId: Float,
        posX: Float,
        posY: Float,
        angleTurns: Float,
        radius: Float,
        shape: BodyShape,
        alpha: Float,
        params: WorldShaderParams,
        outMatricesColMajor: FloatArray,
        outPrimaryIds: FloatArray,
        outSecondaryIds: FloatArray,
        outShapes: FloatArray,
        outAlphas: FloatArray,
        outRadii: FloatArray,
    ): Int {
        if (index >= MAX_RENDER_BODIES) {
            return index
        }

        // Scale then rotate for this body.
        setScale(matS, radius, radius)
        setRotationZ(matR, angleTurns * 2f * PI.toFloat())
        multiply4x4(out = matTmp, a = matR, b = matS)

        // Translate into wrapped world space relative to the current focus.
        val dx = wrapDelta(posX - params.viewFocus.x, params.worldSize.x)
        val dy = wrapDelta(posY - params.viewFocus.y, params.worldSize.y)
        setTranslation(matT, dx, dy)
        multiply4x4(out = matModel, a = matT, b = matTmp)
        multiply4x4(out = matTmp, a = matView, b = matModel)

        val base = index * MAT4_FLOATS
        copyMatrix(out = outMatricesColMajor, outOffset = base, src = matTmp)
        outPrimaryIds[index] = primaryId
        outSecondaryIds[index] = secondaryId
        outShapes[index] = if (shape == BodyShape.TRIANGLE) 1f else 0f
        outAlphas[index] = alpha
        outRadii[index] = radius
        return index + 1
    }

    private fun shaderId(rawId: Int?): Float = if (rawId == null) 0f else (rawId + 1).toFloat()

    private fun wrapDelta(d: Float, size: Float): Float {
        val half = 0.5f * size
        val a = d + half
        val m = a - floor(a / size) * size
        return m - half
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
