package org.emerge.render.torus

import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.GuiShader
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Vec2
import kotlin.math.*

class ScreenRenderer(val contentScale: Vec2) {
    private var zoom: Float = 10f

    @kotlin.concurrent.Volatile
    private var worldRotationRad: Float = 0f

    private val vao = GPU.genAndBindVertexArrays()
    private var vbo: Int = GPU.genBuffers()

    private val worldShader = WorldShader()
    private val guiShader = GuiShader()
    private val circleShader = CircleShader()
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2(1f, 1f), contentScale)
    private val bodyInstanceMatrices = FloatArray(CircleShader.MAX_INSTANCES * MAT4_FLOATS)
    private val bodyInstancePrimaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceSecondaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceShapes = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceAlphas = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceRadii = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceTintColors = FloatArray(CircleShader.MAX_INSTANCES * 3)
    private val matTmp = FloatArray(MAT4_FLOATS)
    private val matT = FloatArray(MAT4_FLOATS)
    private val matR = FloatArray(MAT4_FLOATS)
    private val matS = FloatArray(MAT4_FLOATS)
    private val matView = FloatArray(MAT4_FLOATS)
    private val matModel = FloatArray(MAT4_FLOATS)

    companion object {
        private const val MAT4_FLOATS: Int = 16
        private const val ROTATION_STEP_RAD: Float = 0.03f
        private const val PLANET_INDICATOR_SCALE = 0.0125f
        private const val PLANET_INDICATOR_EDGE_INSET = 0.06f
        private const val PLANET_INDICATOR_ALPHA_MAX = 0.8f
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

    /**
     * Draw a frame. [focus] is the world-space camera anchor (caller chooses it — for
     * a player-centred view, pass the player entity's position; for a free camera,
     * pass whatever the demo's controller dictates). The renderer never derives focus
     * itself, so demos that need death-position camera holds or non-player anchors
     * compose them on their side.
     */
    fun draw(state: PhysicsState, myId: PlayerId?, focus: Vec2) {
        val params = WorldShaderParams.compute(focus, myId, zoom, worldRotationRad)
        worldShader.draw(params, segmentation = layout.worldSegmentation)
        guiShader.draw(vOffset = layout.guiVertexOffset)
        val n = packBodyInstances(
            state = state,
            shapes = state.renderShapes,
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
            tintColorsRgb = bodyInstanceTintColors,
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
        shapes: ComponentTable<RenderShapeComponent>,
        params: WorldShaderParams,
        layout: ScreenLayout,
        outMatricesColMajor: FloatArray,
        outPrimaryIds: FloatArray,
        outSecondaryIds: FloatArray,
        outShapes: FloatArray,
        outAlphas: FloatArray,
        outRadii: FloatArray,
        indexOffset: Int = 0,
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
        val worldPxWidth = layout.worldPxMax.x - layout.worldPxMin.x
        val worldPxHeight = layout.worldPxMax.y - layout.worldPxMin.y
        val worldPxMinDim = min(worldPxWidth, worldPxHeight)
        setTranslation(matT, viewCenterX, viewCenterY)
        multiply4x4(out = matView, a = matT, b = matTmp)

        var n = indexOffset
        for ((entityId, renderShape) in shapes.entries()) {
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val particle = state.particles[entityId]
            val primaryId = shaderId(state.teams[entityId]?.teamId?.value)
            val secondaryId = shaderId(entityId.value)
            n = packBodyInstance(
                index = n,
                primaryId = primaryId,
                secondaryId = secondaryId,
                posX = transform.pos.x.toFloat(),
                posY = transform.pos.y.toFloat(),
                angleTurns = transform.ang.toFloat(),
                radius = collider.radius.toFloat(),
                shape = renderShape.shape,
                alpha = 1f * ((particle?.life?.toFloat() ?: 1f) / (particle?.lifeTime?.toFloat() ?: 1f)),
                params = params,
                outMatricesColMajor = outMatricesColMajor,
                outPrimaryIds = outPrimaryIds,
                outSecondaryIds = outSecondaryIds,
                outShapes = outShapes,
                outAlphas = outAlphas,
                outRadii = outRadii,
            )
            if (n >= CircleShader.MAX_INSTANCES) {
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
                if (n >= CircleShader.MAX_INSTANCES) {
                    break
                }
            }
        }
        n = packPlanetIndicators(
            index = n,
            state = state,
            params = params,
            scaleVecX = scaleVecX,
            scaleVecY = scaleVecY,
            viewCenterX = viewCenterX,
            viewCenterY = viewCenterY,
            worldPxWidth = worldPxWidth,
            worldPxHeight = worldPxHeight,
            worldPxMinDim = worldPxMinDim,
            outMatricesColMajor = outMatricesColMajor,
            outPrimaryIds = outPrimaryIds,
            outSecondaryIds = outSecondaryIds,
            outShapes = outShapes,
            outAlphas = outAlphas,
            outRadii = outRadii,
        )
        return n
    }

    private fun packPlanetIndicators(
        index: Int,
        state: PhysicsState,
        params: WorldShaderParams,
        scaleVecX: Float,
        scaleVecY: Float,
        viewCenterX: Float,
        viewCenterY: Float,
        worldPxWidth: Float,
        worldPxHeight: Float,
        worldPxMinDim: Float,
        outMatricesColMajor: FloatArray,
        outPrimaryIds: FloatArray,
        outSecondaryIds: FloatArray,
        outShapes: FloatArray,
        outAlphas: FloatArray,
        outRadii: FloatArray,
    ): Int {
        if (params.myId == null) {
            return index
        }
        val playerEntityId = state.playerEntities[params.myId] ?: return index
        val playerTeamId = state.teams[playerEntityId]?.teamId?.value

        var n = index
        val indicatorScalePx = worldPxMinDim * PLANET_INDICATOR_SCALE
        val indicatorInsetPx = worldPxMinDim * PLANET_INDICATOR_EDGE_INSET
        val indicatorScaleX = indicatorScalePx * 2f / worldPxWidth
        val indicatorScaleY = indicatorScalePx * 2f / worldPxHeight
        val halfWidthPx = worldPxWidth * 0.5f - indicatorInsetPx
        val halfHeightPx = worldPxHeight * 0.5f - indicatorInsetPx
        for (entityId in state.planets.keys()) {
            if (n >= CircleShader.MAX_INSTANCES) break
            val planetTeamId = state.teams[entityId]?.teamId?.value

            val transform = state.transforms[entityId] ?: continue
            val dx = wrapDelta(transform.pos.x.toFloat() - params.viewFocus.x, params.worldSize.x)
            val dy = wrapDelta(transform.pos.y.toFloat() - params.viewFocus.y, params.worldSize.y)
            val viewDx = dx * cos(-params.viewRotationRad) + dy * sin(-params.viewRotationRad)
            val viewDy = dy * cos(-params.viewRotationRad) - dx * sin(-params.viewRotationRad)
            val ndcDx = viewDx * scaleVecX
            val ndcDy = viewDy * scaleVecY
            val pxDx = ndcDx * worldPxWidth * 0.5f
            val pxDy = ndcDy * worldPxHeight * 0.5f
            val len = hypot(pxDx, pxDy)
            if (!len.isFinite() || len <= 0f) continue
            // Don't show for planets that are mostly on screen
            if (abs(pxDx) <= halfWidthPx && abs(pxDy) <= halfHeightPx) continue

            val lenWorld = if (playerTeamId == planetTeamId) 0f else hypot(dx, dy)
            val dirPxX = pxDx / len
            val dirPxY = pxDy / len
            val edgeT = min(
                if (abs(dirPxX) > 1e-6f) halfWidthPx / abs(dirPxX) else Float.POSITIVE_INFINITY,
                if (abs(dirPxY) > 1e-6f) halfHeightPx / abs(dirPxY) else Float.POSITIVE_INFINITY,
            )
            val primaryId = shaderId(planetTeamId)
            n = packIndicatorInstance(
                index = n,
                primaryId = primaryId,
                posX = viewCenterX + (1f - abs(viewCenterX)) * dirPxX * edgeT * 2f / worldPxWidth,
                posY = viewCenterY + (1f - abs(viewCenterY)) * dirPxY * edgeT * 2f / worldPxHeight,
                angleRad = atan2(dirPxY, dirPxX),
                scaleX = indicatorScaleX,
                scaleY = indicatorScaleY,
                alpha = PLANET_INDICATOR_ALPHA_MAX * max(1f - lenWorld * 2f, 0f),
                outMatricesColMajor = outMatricesColMajor,
                outPrimaryIds = outPrimaryIds,
                outSecondaryIds = outSecondaryIds,
                outShapes = outShapes,
                outAlphas = outAlphas,
                outRadii = outRadii,
            )
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
        if (index >= CircleShader.MAX_INSTANCES) {
            return index
        }

        // Scale then rotate for this body.
        setScale(matS, radius, radius)
        setRotationZ(matR, angleTurns * PI.toFloat())
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

    private fun packIndicatorInstance(
        index: Int,
        primaryId: Float,
        posX: Float,
        posY: Float,
        angleRad: Float,
        scaleX: Float,
        scaleY: Float,
        alpha: Float,
        outMatricesColMajor: FloatArray,
        outPrimaryIds: FloatArray,
        outSecondaryIds: FloatArray,
        outShapes: FloatArray,
        outAlphas: FloatArray,
        outRadii: FloatArray,
    ): Int {
        if (index >= CircleShader.MAX_INSTANCES) {
            return index
        }

        setScale(matS, scaleX, scaleY)
        setRotationZ(matR, angleRad)
        // Rotate in local space first, then convert to per-axis NDC scale so the arrow keeps
        // a visually correct shape on non-square viewports.
        multiply4x4(out = matTmp, a = matS, b = matR)
        setTranslation(matT, posX, posY)
        multiply4x4(out = matModel, a = matT, b = matTmp)

        val base = index * MAT4_FLOATS
        copyMatrix(out = outMatricesColMajor, outOffset = base, src = matModel)
        outPrimaryIds[index] = primaryId
        outSecondaryIds[index] = 0f
        outShapes[index] = 1f
        outAlphas[index] = alpha
        outRadii[index] = max(scaleX, scaleY)
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
