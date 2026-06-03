package org.emerge.demo.scavengers

import org.emerge.demo.scavengers.shader.NoisePlanetShader
import org.emerge.demo.scavengers.shader.RocketShader
import org.emerge.render.torus.EdgeIndicator
import org.emerge.render.torus.GPU
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.RgbColor
import org.emerge.render.torus.ScreenLayout
import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.GuiShader
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Vec2
import org.emerge.sim.core.sim.SimState
import kotlin.math.*

/**
 * Scavengers' world renderer. Drives the engine's generic building blocks (torus
 * background, GUI, and the generic [CircleShader] for translucent discs / edge-indicator
 * arrows) and adds scavengers' own looks: [RocketShader] for triangle bodies and
 * [NoisePlanetShader] for full-alpha circular bodies.
 *
 * Camera/instance-packing math is shared with the (former engine) renderer; only the
 * per-shape *appearance* is scavengers-owned. Like the demo's sibling shaders, the
 * extra programs reuse the engine VAO and shared triangle.
 */
class ScavengersRenderer(val contentScale: Vec2) {
    private var zoom: Float = 10f

    @kotlin.concurrent.Volatile
    private var worldRotationRad: Float = 0f

    private val vao = GPU.genAndBindVertexArrays()
    private var vbo: Int = GPU.genBuffers()

    private val worldShader = WorldShader()
    private val guiShader = GuiShader()
    private val circleShader = CircleShader()
    private val rocketShader = RocketShader()
    private val noisePlanetShader = NoisePlanetShader()
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2(1f, 1f), contentScale)

    // Generic discs / flat triangles (particles, force fields, edge indicators).
    private val circleMatrices = FloatArray(CircleShader.MAX_INSTANCES * Mat4.FLOATS)
    private val circlePrimaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleShapes = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleAlphas = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleTintColors = FloatArray(CircleShader.MAX_INSTANCES * 3)
    private var circleN = 0

    // Rocket bodies (triangle shape, full alpha).
    private val rocketMatrices = FloatArray(RocketShader.MAX_INSTANCES * Mat4.FLOATS)
    private val rocketPrimaryIds = FloatArray(RocketShader.MAX_INSTANCES)
    private val rocketSecondaryColors = FloatArray(RocketShader.MAX_INSTANCES * 3)
    private val rocketTintColors = FloatArray(RocketShader.MAX_INSTANCES * 3)
    private var rocketN = 0

    // Procedural planets (circle shape, full alpha).
    private val planetMatrices = FloatArray(NoisePlanetShader.MAX_INSTANCES * Mat4.FLOATS)
    private val planetPrimaryIds = FloatArray(NoisePlanetShader.MAX_INSTANCES)
    private val planetSecondaryIds = FloatArray(NoisePlanetShader.MAX_INSTANCES)
    private val planetRadii = FloatArray(NoisePlanetShader.MAX_INSTANCES)
    private val planetTintColors = FloatArray(NoisePlanetShader.MAX_INSTANCES * 3)
    private var planetN = 0

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

    fun zoomOut() = zoomByFactor(0.98f)

    fun zoomIn() = zoomByFactor(1.02f)

    fun zoomByFactor(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        zoom = (zoom * factor).coerceIn(1.5f, 20f)
    }

    fun rotateLeft() = rotateBy(ROTATION_STEP_RAD)

    fun rotateRight() = rotateBy(-ROTATION_STEP_RAD)

    fun rotateBy(deltaRad: Float) {
        if (!deltaRad.isFinite()) return
        worldRotationRad += deltaRad
    }

    /**
     * Draw a frame. Data-driven over the engine component tables; everything
     * domain-flavoured comes from the caller:
     *
     *  - [focus] is the world-space camera anchor.
     *  - [primaryColorOf] supplies an optional tint per entity; the shaders override
     *    their hash-derived fallback when the returned color is non-zero.
     *  - [edgeIndicators] is the explicit list of off-screen markers to draw.
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
        packBodyInstances(
            state = state,
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

        // Opaque-ish bodies first, then the translucent generic pass (discs + indicators).
        val vOffset = layout.circleVertexOffset
        if (planetN > 0) {
            noisePlanetShader.drawInstanced(
                vOffset, planetN, planetMatrices, planetPrimaryIds,
                planetSecondaryIds, planetRadii, planetTintColors,
            )
        }
        if (rocketN > 0) {
            rocketShader.drawInstanced(
                vOffset, rocketN, rocketMatrices, rocketPrimaryIds,
                rocketSecondaryColors, rocketTintColors,
            )
        }
        if (circleN > 0) {
            circleShader.drawInstanced(
                vOffset, circleN, circleMatrices, circlePrimaryIds,
                circleShapes, circleAlphas, circleTintColors,
            )
        }
        GPU.disableBlend()
        GPU.disableScissorTest()
    }

    fun cleanup() {
        worldShader.deleteProgram()
        guiShader.deleteProgram()
        circleShader.deleteProgram()
        rocketShader.deleteProgram()
        noisePlanetShader.deleteProgram()
        GPU.deleteBuffers(vbo)
        if (vao != null) {
            GPU.deleteVertexArrays(vao)
        }
    }

    private fun packBodyInstances(
        state: SimState,
        params: WorldShaderParams,
        layout: ScreenLayout,
        primaryColorOf: (EntityId) -> RgbColor,
        edgeIndicators: List<EdgeIndicator>,
    ) {
        circleN = 0
        rocketN = 0
        planetN = 0

        // View matrix (rotate, scale, translate) — computed once per frame.
        matR.setRotationZ(params.viewRotationRad)
        val aspect = layout.resolution.x / layout.resolution.y
        val minAspect = min(aspect, 1f)
        val maxAspect = max(aspect, 1f)
        val scaleVecX = params.worldSize.x * 0.5f / minAspect / params.zoom
        val scaleVecY = -params.worldSize.y * 0.5f * maxAspect / params.zoom
        matS.setScale(scaleVecX, scaleVecY)
        matTmp.setProduct(matS, matR)

        val worldNdcMin = layout.pxToNdc(layout.worldPxMin)
        val worldNdcMax = layout.pxToNdc(layout.worldPxMax)
        val viewCenterX = (worldNdcMax.x + worldNdcMin.x) * 0.5f
        val viewCenterY = (worldNdcMax.y + worldNdcMin.y) * 0.5f
        val worldPxWidth = layout.worldPxMax.x - layout.worldPxMin.x
        val worldPxHeight = layout.worldPxMax.y - layout.worldPxMin.y
        val worldPxMinDim = min(worldPxWidth, worldPxHeight)
        matT.setTranslation(viewCenterX, viewCenterY)
        matView.setProduct(matT, matTmp)

        val transforms = state.components.getTable<TransformComponent>()
        val colliders = state.components.getTable<ColliderComponent>()
        val particles = state.components.getTable<ParticleComponent>()
        val forceFields = state.components.getTable<ForceFieldComponent>()
        val landingSurfaces = state.components.getTable<LandingSurfaceComponent>()
        for ((entityId, renderShape) in state.components.getTable<RenderShapeComponent>().entries()) {
            val transform = transforms[entityId] ?: continue
            val collider = colliders[entityId] ?: continue
            val particle = particles[entityId]
            val id = shaderId(entityId.value)
            val tint = primaryColorOf(entityId)
            // A triangle body is a rocket; a circle that's a landing surface is a planet;
            // anything else is a plain disc. Appearance follows intent, not the alpha value.
            val kind = when {
                renderShape.shape == BodyShape.TRIANGLE -> BodyKind.ROCKET
                landingSurfaces[entityId] != null -> BodyKind.PLANET
                else -> BodyKind.DISC
            }
            packBodyInstance(
                primaryId = id,
                secondaryId = id,
                tint = tint,
                posX = transform.pos.x.toFloat(),
                posY = transform.pos.y.toFloat(),
                angleTurns = transform.ang.toFloat(),
                radius = collider.radius.toFloat(),
                kind = kind,
                alpha = (particle?.life?.toFloat() ?: 1f) / (particle?.lifeTime?.toFloat() ?: 1f),
                params = params,
            )
            val forceField = forceFields[entityId]
            if (forceField != null && renderShape.shape == BodyShape.CIRCLE) {
                // The field overlay is always a translucent disc, even over a planet.
                packBodyInstance(
                    primaryId = id,
                    secondaryId = id,
                    tint = tint,
                    posX = transform.pos.x.toFloat(),
                    posY = transform.pos.y.toFloat(),
                    angleTurns = transform.ang.toFloat(),
                    radius = (collider.radius + forceField.depth).toFloat(),
                    kind = BodyKind.DISC,
                    alpha = forceField.alpha.toFloat(),
                    params = params,
                )
            }
        }
        packEdgeIndicators(
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
    }

    private fun packEdgeIndicators(
        indicators: List<EdgeIndicator>,
        params: WorldShaderParams,
        scaleVecX: Float,
        scaleVecY: Float,
        viewCenterX: Float,
        viewCenterY: Float,
        worldPxWidth: Float,
        worldPxHeight: Float,
        worldPxMinDim: Float,
    ) {
        if (indicators.isEmpty()) return

        val indicatorScalePx = worldPxMinDim * INDICATOR_SCALE
        val indicatorInsetPx = worldPxMinDim * INDICATOR_EDGE_INSET
        val indicatorScaleX = indicatorScalePx * 2f / worldPxWidth
        val indicatorScaleY = indicatorScalePx * 2f / worldPxHeight
        val halfWidthPx = worldPxWidth * 0.5f - indicatorInsetPx
        val halfHeightPx = worldPxHeight * 0.5f - indicatorInsetPx
        for (indicator in indicators) {
            if (circleN >= CircleShader.MAX_INSTANCES) break
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
            matS.setScale(indicatorScaleX, indicatorScaleY)
            matR.setRotationZ(atan2(dirPxY, dirPxX))
            matTmp.setProduct(matS, matR)
            matT.setTranslation(
                viewCenterX + (1f - abs(viewCenterX)) * dirPxX * edgeT * 2f / worldPxWidth,
                viewCenterY + (1f - abs(viewCenterY)) * dirPxY * edgeT * 2f / worldPxHeight,
            )
            matModel.setProduct(matT, matTmp)
            appendCircle(matModel, primaryId = 0f, shape = 1f, alpha = indicator.alpha, tint = indicator.color)
        }
    }

    /** What look a body is drawn with — chosen by the caller from the entity's components. */
    private enum class BodyKind { ROCKET, PLANET, DISC }

    private fun packBodyInstance(
        primaryId: Float,
        secondaryId: Float,
        tint: RgbColor,
        posX: Float,
        posY: Float,
        angleTurns: Float,
        radius: Float,
        kind: BodyKind,
        alpha: Float,
        params: WorldShaderParams,
    ) {
        // Scale then rotate, then translate into wrapped world space relative to focus.
        matS.setScale(radius, radius)
        matR.setRotationZ(angleTurns * PI.toFloat())
        matTmp.setProduct(matR, matS)
        val dx = wrapDelta(posX - params.viewFocus.x, params.worldSize.x)
        val dy = wrapDelta(posY - params.viewFocus.y, params.worldSize.y)
        matT.setTranslation(dx, dy)
        matModel.setProduct(matT, matTmp)
        matTmp.setProduct(matView, matModel)

        when (kind) {
            BodyKind.ROCKET -> appendRocket(matTmp, primaryId, bodyToneColor(secondaryId), tint)
            BodyKind.PLANET -> appendPlanet(matTmp, primaryId, secondaryId, radius, tint)
            BodyKind.DISC -> appendCircle(matTmp, primaryId, shape = 0f, alpha = alpha, tint = tint)
        }
    }

    private fun appendCircle(m: Mat4, primaryId: Float, shape: Float, alpha: Float, tint: RgbColor) {
        if (circleN >= CircleShader.MAX_INSTANCES) return
        m.copyInto(circleMatrices, circleN * Mat4.FLOATS)
        circlePrimaryIds[circleN] = primaryId
        circleShapes[circleN] = shape
        circleAlphas[circleN] = alpha
        val t = circleN * 3
        circleTintColors[t] = tint.r
        circleTintColors[t + 1] = tint.g
        circleTintColors[t + 2] = tint.b
        circleN++
    }

    private fun appendRocket(m: Mat4, primaryId: Float, bodyTone: RgbColor, tint: RgbColor) {
        if (rocketN >= RocketShader.MAX_INSTANCES) return
        m.copyInto(rocketMatrices, rocketN * Mat4.FLOATS)
        rocketPrimaryIds[rocketN] = primaryId
        val s = rocketN * 3
        rocketSecondaryColors[s] = bodyTone.r
        rocketSecondaryColors[s + 1] = bodyTone.g
        rocketSecondaryColors[s + 2] = bodyTone.b
        val t = rocketN * 3
        rocketTintColors[t] = tint.r
        rocketTintColors[t + 1] = tint.g
        rocketTintColors[t + 2] = tint.b
        rocketN++
    }

    /**
     * Body tone for a rocket: the per-entity hue the rocket shader used to derive from
     * `secondaryId` itself. The hash now lives here — the shader is a pure color consumer —
     * reproducing the old `mod(vec3(w/1.9, w/2.9, w/4.9), 1.0)` with `w = -secondaryId - 1`.
     */
    private fun bodyToneColor(secondaryId: Float): RgbColor {
        val w = -secondaryId - 1f
        return RgbColor(
            positiveFractional(w / 1.9f),
            positiveFractional(w / 2.9f),
            positiveFractional(w / 4.9f),
        )
    }

    private fun positiveFractional(x: Float): Float = x - floor(x)

    private fun appendPlanet(m: Mat4, primaryId: Float, secondaryId: Float, radius: Float, tint: RgbColor) {
        if (planetN >= NoisePlanetShader.MAX_INSTANCES) return
        m.copyInto(planetMatrices, planetN * Mat4.FLOATS)
        planetPrimaryIds[planetN] = primaryId
        planetSecondaryIds[planetN] = secondaryId
        planetRadii[planetN] = radius
        val t = planetN * 3
        planetTintColors[t] = tint.r
        planetTintColors[t + 1] = tint.g
        planetTintColors[t + 2] = tint.b
        planetN++
    }

    private fun shaderId(rawId: Int?): Float = if (rawId == null) 0f else (rawId + 1).toFloat()

    private fun wrapDelta(d: Float, size: Float): Float {
        val half = 0.5f * size
        val a = d + half
        val m = a - floor(a / size) * size
        return m - half
    }
}
