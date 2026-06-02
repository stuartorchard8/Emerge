package org.emerge.render.torus

import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.GuiShader
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Vec2
import org.emerge.sim.core.sim.SimState
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
    private val bodyInstanceMatrices = FloatArray(CircleShader.MAX_INSTANCES * Mat4.FLOATS)
    private val bodyInstancePrimaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceSecondaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceShapes = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceAlphas = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceRadii = FloatArray(CircleShader.MAX_INSTANCES)
    private val bodyInstanceTintColors = FloatArray(CircleShader.MAX_INSTANCES * 3)
    private val matTmp = Mat4.scratch()
    private val matT = Mat4.scratch()
    private val matR = Mat4.scratch()
    private val matS = Mat4.scratch()
    private val matView = Mat4.scratch()
    private val matModel = Mat4.scratch()

    companion object {
        private const val ROTATION_STEP_RAD: Float = 0.03f
        private const val INDICATOR_SCALE = 0.0125f
        private const val INDICATOR_EDGE_INSET = 0.06f
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
     * Draw a frame.
     *
     * The renderer is purely data-driven: it iterates the engine component tables
     * for shapes/transforms/colliders/particles/force-fields, but everything
     * domain-flavoured comes from the caller:
     *
     *  - [focus] is the world-space camera anchor (player-centred, free, …).
     *  - [primaryColorOf] supplies an optional tint per entity; the body shader
     *    overrides its hash-derived fallback when the returned color is non-zero.
     *  - [edgeIndicators] is the explicit list of off-screen markers to draw, each
     *    already coloured and alpha-faded by the demo's own rules.
     */
    fun draw(
        state: SimState,
        focus: Vec2,
        primaryColorOf: (EntityId) -> RgbColor = { RgbColor.Transparent },
        edgeIndicators: List<EdgeIndicator> = emptyList(),
    ) {
        val params = WorldShaderParams.compute(focus, zoom, worldRotationRad)
        worldShader.draw(params, segmentation = layout.worldSegmentation)
        guiShader.draw(vOffset = layout.guiVertexOffset)
        val n = packBodyInstances(
            state = state,
            shapes = state.renderShapes,
            params = params,
            layout = layout,
            primaryColorOf = primaryColorOf,
            edgeIndicators = edgeIndicators,
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
        state: SimState,
        shapes: ComponentTable<RenderShapeComponent>,
        params: WorldShaderParams,
        layout: ScreenLayout,
        primaryColorOf: (EntityId) -> RgbColor,
        edgeIndicators: List<EdgeIndicator>,
        indexOffset: Int = 0,
    ): Int {
        // Calculate view matrix once
        // Rotate then Scale
        matR.setRotationZ(params.viewRotationRad)
        val worldPxSize = Vec2(layout.resolution.x, layout.resolution.y)
        val aspect = worldPxSize.x / worldPxSize.y
        val minAspect = min(aspect, 1f)
        val maxAspect = max(aspect, 1f)
        val scaleVecX = params.worldSize.x * 0.5f / minAspect / params.zoom
        val scaleVecY = -params.worldSize.y * 0.5f * maxAspect / params.zoom
        matS.setScale(scaleVecX, scaleVecY)
        matTmp.setProduct(matS, matR)

        // Translate
        val worldNdcMin = layout.pxToNdc(layout.worldPxMin)
        val worldNdcMax = layout.pxToNdc(layout.worldPxMax)
        val viewCenterX = (worldNdcMax.x + worldNdcMin.x) * 0.5f
        val viewCenterY = (worldNdcMax.y + worldNdcMin.y) * 0.5f
        val worldPxWidth = layout.worldPxMax.x - layout.worldPxMin.x
        val worldPxHeight = layout.worldPxMax.y - layout.worldPxMin.y
        val worldPxMinDim = min(worldPxWidth, worldPxHeight)
        matT.setTranslation(viewCenterX, viewCenterY)
        matView.setProduct(matT, matTmp)

        var n = indexOffset
        for ((entityId, renderShape) in shapes.entries()) {
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val particle = state.particles[entityId]
            val secondaryId = shaderId(entityId.value)
            val tint = primaryColorOf(entityId)
            n = packBodyInstance(
                index = n,
                primaryId = secondaryId,
                secondaryId = secondaryId,
                tint = tint,
                posX = transform.pos.x.toFloat(),
                posY = transform.pos.y.toFloat(),
                angleTurns = transform.ang.toFloat(),
                radius = collider.radius.toFloat(),
                shape = renderShape.shape,
                alpha = 1f * ((particle?.life?.toFloat() ?: 1f) / (particle?.lifeTime?.toFloat() ?: 1f)),
                params = params,
            )
            if (n >= CircleShader.MAX_INSTANCES) {
                break
            }
            val forceField = state.forceFields[entityId]
            if (forceField != null && renderShape.shape == BodyShape.CIRCLE) {
                n = packBodyInstance(
                    index = n,
                    primaryId = secondaryId,
                    secondaryId = secondaryId,
                    tint = tint,
                    posX = transform.pos.x.toFloat(),
                    posY = transform.pos.y.toFloat(),
                    angleTurns = transform.ang.toFloat(),
                    radius = (collider.radius + forceField.depth).toFloat(),
                    shape = renderShape.shape,
                    alpha = forceField.alpha.toFloat(),
                    params = params,
                )
                if (n >= CircleShader.MAX_INSTANCES) {
                    break
                }
            }
        }
        n = packEdgeIndicators(
            index = n,
            indicators = edgeIndicators,
            params = params,
            scaleVecX = scaleVecX,
            scaleVecY = scaleVecY,
            viewCenterX = viewCenterX,
            viewCenterY = viewCenterY,
            worldPxWidth = worldPxWidth,
            worldPxHeight = worldPxHeight,
            worldPxMinDim = worldPxMinDim,
        )
        return n
    }

    private fun packEdgeIndicators(
        index: Int,
        indicators: List<EdgeIndicator>,
        params: WorldShaderParams,
        scaleVecX: Float,
        scaleVecY: Float,
        viewCenterX: Float,
        viewCenterY: Float,
        worldPxWidth: Float,
        worldPxHeight: Float,
        worldPxMinDim: Float,
    ): Int {
        if (indicators.isEmpty()) return index

        var n = index
        val indicatorScalePx = worldPxMinDim * INDICATOR_SCALE
        val indicatorInsetPx = worldPxMinDim * INDICATOR_EDGE_INSET
        val indicatorScaleX = indicatorScalePx * 2f / worldPxWidth
        val indicatorScaleY = indicatorScalePx * 2f / worldPxHeight
        val halfWidthPx = worldPxWidth * 0.5f - indicatorInsetPx
        val halfHeightPx = worldPxHeight * 0.5f - indicatorInsetPx
        for (indicator in indicators) {
            if (n >= CircleShader.MAX_INSTANCES) break
            val dx = wrapDelta(indicator.worldPos.x.toFloat() - params.viewFocus.x, params.worldSize.x)
            val dy = wrapDelta(indicator.worldPos.y.toFloat() - params.viewFocus.y, params.worldSize.y)
            val viewDx = dx * cos(-params.viewRotationRad) + dy * sin(-params.viewRotationRad)
            val viewDy = dy * cos(-params.viewRotationRad) - dx * sin(-params.viewRotationRad)
            val ndcDx = viewDx * scaleVecX
            val ndcDy = viewDy * scaleVecY
            val pxDx = ndcDx * worldPxWidth * 0.5f
            val pxDy = ndcDy * worldPxHeight * 0.5f
            val len = hypot(pxDx, pxDy)
            if (!len.isFinite() || len <= 0f) continue
            // Skip if mostly on-screen — there's nothing off-edge to point at.
            if (abs(pxDx) <= halfWidthPx && abs(pxDy) <= halfHeightPx) continue

            val dirPxX = pxDx / len
            val dirPxY = pxDy / len
            val edgeT = min(
                if (abs(dirPxX) > 1e-6f) halfWidthPx / abs(dirPxX) else Float.POSITIVE_INFINITY,
                if (abs(dirPxY) > 1e-6f) halfHeightPx / abs(dirPxY) else Float.POSITIVE_INFINITY,
            )
            n = packIndicatorInstance(
                index = n,
                tint = indicator.color,
                posX = viewCenterX + (1f - abs(viewCenterX)) * dirPxX * edgeT * 2f / worldPxWidth,
                posY = viewCenterY + (1f - abs(viewCenterY)) * dirPxY * edgeT * 2f / worldPxHeight,
                angleRad = atan2(dirPxY, dirPxX),
                scaleX = indicatorScaleX,
                scaleY = indicatorScaleY,
                alpha = indicator.alpha,
            )
        }
        return n
    }

    private fun packBodyInstance(
        index: Int,
        primaryId: Float,
        secondaryId: Float,
        tint: RgbColor,
        posX: Float,
        posY: Float,
        angleTurns: Float,
        radius: Float,
        shape: BodyShape,
        alpha: Float,
        params: WorldShaderParams,
    ): Int {
        if (index >= CircleShader.MAX_INSTANCES) {
            return index
        }

        // Scale then rotate for this body.
        matS.setScale(radius, radius)
        matR.setRotationZ(angleTurns * PI.toFloat())
        matTmp.setProduct(matR, matS)

        // Translate into wrapped world space relative to the current focus.
        val dx = wrapDelta(posX - params.viewFocus.x, params.worldSize.x)
        val dy = wrapDelta(posY - params.viewFocus.y, params.worldSize.y)
        matT.setTranslation(dx, dy)
        matModel.setProduct(matT, matTmp)
        matTmp.setProduct(matView, matModel)

        val base = index * Mat4.FLOATS
        matTmp.copyInto(bodyInstanceMatrices, base)
        bodyInstancePrimaryIds[index] = primaryId
        bodyInstanceSecondaryIds[index] = secondaryId
        bodyInstanceShapes[index] = if (shape == BodyShape.TRIANGLE) 1f else 0f
        bodyInstanceAlphas[index] = alpha
        bodyInstanceRadii[index] = radius
        val tintBase = index * 3
        bodyInstanceTintColors[tintBase] = tint.r
        bodyInstanceTintColors[tintBase + 1] = tint.g
        bodyInstanceTintColors[tintBase + 2] = tint.b
        return index + 1
    }

    private fun packIndicatorInstance(
        index: Int,
        tint: RgbColor,
        posX: Float,
        posY: Float,
        angleRad: Float,
        scaleX: Float,
        scaleY: Float,
        alpha: Float,
    ): Int {
        if (index >= CircleShader.MAX_INSTANCES) {
            return index
        }

        matS.setScale(scaleX, scaleY)
        matR.setRotationZ(angleRad)
        // Rotate in local space first, then convert to per-axis NDC scale so the arrow keeps
        // a visually correct shape on non-square viewports.
        matTmp.setProduct(matS, matR)
        matT.setTranslation(posX, posY)
        matModel.setProduct(matT, matTmp)

        val base = index * Mat4.FLOATS
        matModel.copyInto(bodyInstanceMatrices, base)
        bodyInstancePrimaryIds[index] = 0f
        bodyInstanceSecondaryIds[index] = 0f
        bodyInstanceShapes[index] = 1f
        bodyInstanceAlphas[index] = alpha
        bodyInstanceRadii[index] = max(scaleX, scaleY)
        val tintBase = index * 3
        bodyInstanceTintColors[tintBase] = tint.r
        bodyInstanceTintColors[tintBase + 1] = tint.g
        bodyInstanceTintColors[tintBase + 2] = tint.b
        return index + 1
    }

    private fun shaderId(rawId: Int?): Float = if (rawId == null) 0f else (rawId + 1).toFloat()

    private fun wrapDelta(d: Float, size: Float): Float {
        val half = 0.5f * size
        val a = d + half
        val m = a - floor(a / size) * size
        return m - half
    }
}
