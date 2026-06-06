package org.emerge.demo.drockets

import org.emerge.demo.drockets.shader.PlanetShader
import org.emerge.demo.drockets.shader.StarscapeShader
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.SpriteShader
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.physics.primitives.*
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the world layer: starscape background, planets, drocket sprites, knight sprites,
 * and exhaust/destruction particles. Owns its own camera state (zoom, rotation, focused
 * entity) and the input handlers that mutate it.
 *
 * The camera focuses on a drocket if one is selected; otherwise it sits on the planet's
 * surface at [focusRotationOffset]. Between focus switches a parametric easing curve blends
 * the two positions over [FOCUS_SWITCH_FRAMES] frames.
 */
class WorldRenderer(
    private val drocketSpriteAtlasTextureId: Int,
    private val knightSpriteAtlasTextureId: Int,
) {
    private var zoom: Float = 100f
    var focusedEntityId: EntityId? = null
        private set
    @Volatile
    private var focusRotationOffset = Coord(0)
    private var viewRotation = Coord(0)
    private var viewFocus = Coord2.zero
    private var priorFocusId: EntityId? = null
    private var focusSwitchFrameCounter = FOCUS_SWITCH_FRAMES.toLong()
    private var deadFocusPos: Coord2? = null

    private val vao = GPU.genAndBindVertexArrays()
    private val vbo: Int = GPU.genBuffers()
    private val starscapeShader = StarscapeShader()
    private val planetShader = PlanetShader()
    private val circleShader = CircleShader()
    private val spriteShader = SpriteShader()

    private var resolution: Vec2 = Vec2(1f, 1f)
    private var lastDrawnState: SimState? = null

    // Instance buffers for planet shader
    private val planetMatrices = FloatArray(PlanetShader.MAX_INSTANCES * Mat4.FLOATS)
    private val planetPrimaryIds = FloatArray(PlanetShader.MAX_INSTANCES)
    private val planetAlphas = FloatArray(PlanetShader.MAX_INSTANCES)

    // Instance buffers for circle shader (particles only)
    private val circleMatrices = FloatArray(CircleShader.MAX_INSTANCES * Mat4.FLOATS)
    private val circlePrimaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleShapes = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleAlphas = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleTintColors = FloatArray(CircleShader.MAX_INSTANCES * 3)

    // Instance buffers for sprite shader (drockets + knights)
    private val spriteMatrices = FloatArray(SpriteShader.MAX_INSTANCES * Mat4.FLOATS)
    private val spritePrimaryIds = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvXs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvYs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvWs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvHs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteAlphas = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteSquashs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteTintColors = FloatArray(SpriteShader.MAX_INSTANCES * 3)

    private val matTmp = Mat4.scratch()
    private val matT = Mat4.scratch()
    private val matR = Mat4.scratch()
    private val matS = Mat4.scratch()
    private val matView = Mat4.scratch()
    private val matModel = Mat4.scratch()

    private val worldSize = Vec2(2f, 2f)

    init {
        // SpriteShader creates its own VAO during construction, leaving it bound.
        // Rebind our main VAO before uploading vertex data for the other shaders.
        GPU.bindVertexArray(vao)
        uploadVerts()
    }

    fun setResolution(res: Vec2) {
        resolution = res
        GPU.setViewport(0, 0, res.x.toInt(), res.y.toInt())
    }

    fun zoomByFactor(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        zoom = zoom * factor
    }

    fun rotateBy(turns: Frac) {
        focusRotationOffset += turns
    }

    fun focusPlanet() {
        if (focusedEntityId != null) {
            priorFocusId = focusedEntityId
            focusedEntityId = null
            focusSwitchFrameCounter = 0
        }
    }

    /** External focus request, e.g. from a cladogram-panel click. */
    fun focusOn(entityId: EntityId) {
        priorFocusId = focusedEntityId
        focusedEntityId = entityId
        focusSwitchFrameCounter = 0
    }

    /**
     * World-space pick at screen pixel. Returns true if a drocket was found and focused on.
     * Hit radius is [PICK_RADIUS_SCALE] times the entity's collider radius.
     */
    fun tryFocusDrocketAt(state: SimState, pixel: Vec2): Boolean {
        val world = screenToWorld(pixel) ?: return false
        val drocketStates = state.components.getTable<DrocketStateComponent>()

        var bestId: EntityId? = null
        var bestDistSq = Float.POSITIVE_INFINITY
        for ((entityId, _) in drocketStates.entries()) {
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val dx = wrapDelta(transform.pos.x.toFloat() - world.x, worldSize.x)
            val dy = wrapDelta(transform.pos.y.toFloat() - world.y, worldSize.y)
            val distSq = dx * dx + dy * dy
            val pickRadius = collider.radius.toFloat() * PICK_RADIUS_SCALE
            if (distSq <= pickRadius * pickRadius && distSq < bestDistSq) {
                bestId = entityId
                bestDistSq = distSq
            }
        }

        if (bestId != null) {
            priorFocusId = focusedEntityId
            focusedEntityId = bestId
            focusSwitchFrameCounter = 0
            return true
        }
        return false
    }

    fun draw(state: SimState) {
        lastDrawnState = state
        updateViewFocus(state)

        val viewRotationRad = viewRotation.toFloat() * PI.toFloat()
        val zoomInv = 1f / zoom
        computeViewMatrix(zoomInv, viewRotationRad)

        GPU.bindVertexArray(vao)

        // ── Layer 0: starscape background ──
        starscapeShader.draw(
            vOffset = QUAD_VERTEX_OFFSET,
            bearing = -viewRotationRad,
            resolutionX = resolution.x,
            resolutionY = resolution.y,
        )

        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()

        drawPlanets(state)
        drawDrocketSprites(state)
        drawKnightSprites(state)

        GPU.bindVertexArray(vao)
        drawParticles(state)
        GPU.disableBlend()
    }

    fun cleanup() {
        starscapeShader.deleteProgram()
        planetShader.deleteProgram()
        circleShader.deleteProgram()
        spriteShader.deleteProgram()
        GPU.deleteBuffers(vbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    /** HUD line provider: returns debug info for the currently focused drocket. */
    fun focusedDrocketDebugLines(): List<String> {
        val state = lastDrawnState ?: return emptyList()
        val entityId = focusedEntityId ?: return emptyList()
        val ds = state.components.getTable<DrocketStateComponent>()[entityId] ?: return emptyList()
        val genome = state.components.getTable<GenomeComponent>()[entityId] ?: return emptyList()
        val reproducer = state.components.getTable<ReproducerComponent>()[entityId]

        val p = genome.genome.phenotype()
        val sex = reproducer?.sex?.name ?: "NA"
        val nowMs = nowMsForTick(state.tick)
        val pregnancy = when {
            reproducer?.spawn == null -> "NO"
            reproducer.spawn.birthdayMs > nowMs -> "YES DUE ${reproducer.spawn.birthdayMs - nowMs}MS"
            else -> "READY"
        }

        return listOf(
            "ID ${entityId.value} ${ds.phase}",
            "SEX $sex PREG $pregnancy",
            "WALK ${p.aiWalkMinTicks}-${p.aiWalkMaxTicks}",
            "CHARGE ${p.aiChargeTicks}  FUEL ${p.aiFuelTicks}",
            "THRUST ${p.aiThrust}",
            "BODY HSV ${p.bodyColor.h} ${p.bodyColor.s} ${p.bodyColor.v}",
            "FIRE HSV ${p.fireColor.h} ${p.fireColor.s} ${p.fireColor.v}",
        )
    }

    // ── Layer drawing ──────────────────────────────────────────────────────────

    private fun drawPlanets(state: SimState) {
        var planetCount = 0
        for (entityId in state.atmosphereSources.keys()) {
            if (planetCount >= PlanetShader.MAX_INSTANCES) break
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val forceField = state.forceFields[entityId]

            val renderRadius = if (forceField != null) {
                (collider.radius + forceField.depth).toFloat()
            } else {
                collider.radius.toFloat() * PLANET_ATMOSPHERE_SCALE
            }

            matS.setScale(renderRadius, renderRadius)
            matR.setRotationZ(transform.ang.toFloat() * PI.toFloat())
            matTmp.setProduct(matR, matS)

            val dx = wrapDelta(transform.pos.x.toFloat() - viewFocus.x.toFloat(), worldSize.x)
            val dy = wrapDelta(transform.pos.y.toFloat() - viewFocus.y.toFloat(), worldSize.y)
            matT.setTranslation(dx, dy)
            matModel.setProduct(matT, matTmp)
            matTmp.setProduct(matView, matModel)

            val base = planetCount * Mat4.FLOATS
            matTmp.copyInto(planetMatrices, base)
            planetPrimaryIds[planetCount] = (entityId.value + 1).toFloat()
            planetAlphas[planetCount] = 1f
            planetCount++
        }
        if (planetCount > 0) {
            planetShader.drawInstanced(
                vOffset = TRIANGLE_VERTEX_OFFSET,
                instanceCount = planetCount,
                matricesColMajor = planetMatrices,
                primaryIds = planetPrimaryIds,
                alphas = planetAlphas,
            )
        }
    }

    private fun drawDrocketSprites(state: SimState) {
        var spriteCount = 0
        val drocketStates = state.components.getTable<DrocketStateComponent>().entries()
        val reproducers = state.components.getTable<ReproducerComponent>()
        val genomes = state.components.getTable<GenomeComponent>()
        val nowMs = nowMsForTick(state.tick)
        for ((entityId, drocketState) in drocketStates) {
            if (spriteCount >= SpriteShader.MAX_INSTANCES) break
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val reproducer = reproducers[entityId] ?: continue
            val facing = drocketState.walkDirection
            val squash = 0.06125 - cos((drocketState.ticksRemaining) * 2 * PI / 60f) * 0.06125
            val genome = genomes[entityId]
            val (uvX, uvY) = spriteUvForEntity(state, entityId)
            val (uvW, uvH) = spriteSizeForEntity(state, entityId)
            val maturity = reproducer.getMaturityRatio(nowMs)

            spriteCount = packSpriteInstance(
                index = spriteCount,
                posX = transform.pos.x.toFloat() - viewFocus.x.toFloat(),
                posY = transform.pos.y.toFloat() - viewFocus.y.toFloat(),
                angleTurns = transform.ang.toFloat(),
                scaleX = collider.radius.toFloat() * SPRITE_SCALE_FACTOR * maturity,
                scaleY = collider.radius.toFloat() * SPRITE_SCALE_FACTOR * facing * maturity,
                primaryId = (entityId.value + 1).toFloat(),
                uvX = uvX,
                uvY = uvY,
                uvW = uvW,
                uvH = uvH,
                alpha = 1f,
                squash = squash.toFloat(),
            )
            val tintBase = (spriteCount - 1) * 3
            val bodyColor = genome?.genome?.phenotype()?.bodyColor
            if (bodyColor != null) {
                val rgb = bodyColor.toRgb()
                spriteTintColors[tintBase] = rgb.first
                spriteTintColors[tintBase + 1] = rgb.second
                spriteTintColors[tintBase + 2] = rgb.third
            } else {
                spriteTintColors[tintBase] = 0f
                spriteTintColors[tintBase + 1] = 0f
                spriteTintColors[tintBase + 2] = 0f
            }
        }
        if (spriteCount > 0) {
            spriteShader.drawInstanced(
                instanceCount = spriteCount,
                matricesColMajor = spriteMatrices,
                primaryIds = spritePrimaryIds,
                uvXs = spriteUvXs,
                uvYs = spriteUvYs,
                uvWs = spriteUvWs,
                uvHs = spriteUvHs,
                alphas = spriteAlphas,
                squashs = spriteSquashs,
                tintColorsRgb = spriteTintColors,
                textureId = drocketSpriteAtlasTextureId,
                frameSizeX = SpriteSheet.DROCKET.frameSizeU,
                frameSizeY = SpriteSheet.DROCKET.frameSizeV,
            )
        }
    }

    private fun drawKnightSprites(state: SimState) {
        var spriteCount = 0
        val knightStates = state.components.getTable<KnightStateComponent>().entries()
        for ((entityId, _) in knightStates) {
            if (spriteCount >= SpriteShader.MAX_INSTANCES) break
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val (uvX, uvY) = spriteUvForEntity(state, entityId)
            val (uvW, uvH) = spriteSizeForEntity(state, entityId)

            spriteCount = packSpriteInstance(
                index = spriteCount,
                posX = transform.pos.x.toFloat() - viewFocus.x.toFloat(),
                posY = transform.pos.y.toFloat() - viewFocus.y.toFloat(),
                angleTurns = transform.ang.toFloat(),
                scaleX = collider.radius.toFloat() * SPRITE_SCALE_FACTOR,
                scaleY = collider.radius.toFloat() * SPRITE_SCALE_FACTOR,
                primaryId = (entityId.value + 1).toFloat(),
                uvX = uvX,
                uvY = uvY,
                uvW = uvW,
                uvH = uvH,
                alpha = 1f,
                squash = 0f,
            )
            // Preserve the knights' per-entity coloring, but compute the id hash here
            // rather than in the sprite shader (which is now a pure tint consumer).
            val tintBase = (spriteCount - 1) * 3
            val (r, g, b) = idHashColor((entityId.value + 1).toFloat())
            spriteTintColors[tintBase] = r
            spriteTintColors[tintBase + 1] = g
            spriteTintColors[tintBase + 2] = b
        }
        if (spriteCount > 0) {
            spriteShader.drawInstanced(
                instanceCount = spriteCount,
                matricesColMajor = spriteMatrices,
                primaryIds = spritePrimaryIds,
                uvXs = spriteUvXs,
                uvYs = spriteUvYs,
                uvWs = spriteUvWs,
                uvHs = spriteUvHs,
                alphas = spriteAlphas,
                squashs = spriteSquashs,
                tintColorsRgb = spriteTintColors,
                textureId = knightSpriteAtlasTextureId,
                frameSizeX = SpriteSheet.KNIGHT.frameSizeU,
                frameSizeY = SpriteSheet.KNIGHT.frameSizeV,
            )
        }
    }

    private fun drawParticles(state: SimState) {
        var circleCount = 0
        val particleTints = state.components.getTable<ParticleTintComponent>()
        for ((entityId, particle) in state.particles.entries()) {
            if (circleCount >= CircleShader.MAX_INSTANCES) break
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue

            val radius = collider.radius.toFloat()
            matS.setScale(radius, radius)
            matR.setRotationZ(transform.ang.toFloat() * PI.toFloat())
            matTmp.setProduct(matR, matS)

            val dx = wrapDelta(transform.pos.x.toFloat() - viewFocus.x.toFloat(), worldSize.x)
            val dy = wrapDelta(transform.pos.y.toFloat() - viewFocus.y.toFloat(), worldSize.y)
            matT.setTranslation(dx, dy)
            matModel.setProduct(matT, matTmp)
            matTmp.setProduct(matView, matModel)

            val base = circleCount * Mat4.FLOATS
            matTmp.copyInto(circleMatrices, base)
            circlePrimaryIds[circleCount] = (entityId.value + 1).toFloat()
            circleShapes[circleCount] = 0f
            circleAlphas[circleCount] = particle.life.toFloat() / particle.lifeTime.toFloat()
            val tintBase = circleCount * 3
            // Fire-color tint from the emitting drocket; white if a particle has none
            // (the circle shader no longer hashes the entity id into a fallback hue).
            val tint = particleTints[entityId]?.color
            if (tint != null) {
                val (r, g, b) = tint.toRgb()
                circleTintColors[tintBase] = r
                circleTintColors[tintBase + 1] = g
                circleTintColors[tintBase + 2] = b
            } else {
                circleTintColors[tintBase] = 1f
                circleTintColors[tintBase + 1] = 1f
                circleTintColors[tintBase + 2] = 1f
            }
            circleCount++
        }
        if (circleCount > 0) {
            circleShader.drawInstanced(
                vOffset = TRIANGLE_VERTEX_OFFSET,
                instanceCount = circleCount,
                matricesColMajor = circleMatrices,
                primaryIds = circlePrimaryIds,
                shapes = circleShapes,
                alphas = circleAlphas,
                tintColorsRgb = circleTintColors,
            )
        }
    }

    // ── Camera focus ───────────────────────────────────────────────────────────

    private fun updateViewFocus(state: SimState) {
        val drocketStates = state.components.getTable<DrocketStateComponent>()

        val planetId = state.atmosphereSources.keys().firstOrNull() ?: return
        val planetTransform = state.transforms[planetId] ?: return

        val focusedId = focusedEntityId
        val focusedAlive = focusedId != null
            && drocketStates[focusedId] != null
            && state.transforms[focusedId] != null

        val focusPos: Coord2 = if (focusedId != null && !focusedAlive) {
            // Focused drocket died — pin the camera at its last on-screen position
            // (snapshotted once at death) rather than falling back to the planet.
            if (deadFocusPos == null) {
                deadFocusPos = viewFocus
                focusSwitchFrameCounter = FOCUS_SWITCH_FRAMES.toLong()
            }
            deadFocusPos!!
        } else {
            deadFocusPos = null
            val focusId = if (focusedAlive) focusedId!! else planetId
            getFocusPos(state, focusId) ?: return
        }
        val priorFocus = priorFocusId ?: planetId
        if (focusSwitchFrameCounter < FOCUS_SWITCH_FRAMES) {
            val priorFocusPos = getFocusPos(state, priorFocus)
            if (priorFocusPos == null) {
                viewFocus = focusPos
            } else {
                val newFocusRatio = Frac.parametric(Frac(focusSwitchFrameCounter, FOCUS_SWITCH_FRAMES))
                val oldFocusRatio = Frac(1, 1) - newFocusRatio
                val focusLerp = focusPos.asFrac2() * newFocusRatio + priorFocusPos.asFrac2() * oldFocusRatio
                viewFocus = focusLerp.wrap()
                focusSwitchFrameCounter += 1
            }
        } else {
            viewFocus = focusPos
        }

        val planetOffset = viewFocus - planetTransform.pos
        viewRotation = Coord(
            (
                (atan2(planetOffset.x.toFloat(), -planetOffset.y.toFloat()) / -PI)
                    * Int.MAX_VALUE
                ).toInt()
        )
    }

    private fun getFocusPos(state: SimState, entityId: EntityId): Coord2? {
        val transform = state.transforms[entityId] ?: return null
        val surfaceRadius = state.colliders[entityId]?.radius

        var focusPos = transform.pos
        // Project focus to the planet surface at the current rotation angle.
        if (surfaceRadius == PLANET_RADIUS) {
            val viewRotationRad = Coord(focusRotationOffset.raw - transform.ang.raw).toFloat() * PI.toFloat()
            focusPos -= Frac2.raw(
                ((sin(viewRotationRad) * surfaceRadius.toFloat()) * Int.MAX_VALUE).toInt(),
                ((cos(viewRotationRad) * surfaceRadius.toFloat()) * Int.MAX_VALUE).toInt(),
            )
        }
        return focusPos
    }

    private fun screenToWorld(pixel: Vec2): Vec2? {
        if (resolution.x <= 0f || resolution.y <= 0f || zoom <= 0f) return null

        val ndcX = pixel.x / resolution.x * 2f - 1f
        val ndcY = 1f - pixel.y / resolution.y * 2f
        val viewRotationRad = viewRotation.toFloat() * PI.toFloat()

        val zoomInv = 1f / zoom
        val aspect = resolution.x / resolution.y
        val minAspect = min(aspect, 1f)
        val maxAspect = max(aspect, 1f)
        val sx = worldSize.x * 0.5f / minAspect / zoomInv
        val sy = -worldSize.y * 0.5f * maxAspect / zoomInv
        if (sx == 0f || sy == 0f) return null

        val rx = ndcX / sx
        val ry = ndcY / sy
        val c = cos(viewRotationRad)
        val s = sin(viewRotationRad)
        return Vec2(
            viewFocus.x.toFloat() + c * rx + s * ry,
            viewFocus.y.toFloat() - s * rx + c * ry,
        )
    }

    private fun computeViewMatrix(zoomInv: Float, viewRotation: Float) {
        matR.setRotationZ(viewRotation)
        val aspect = resolution.x / resolution.y
        val minAspect = min(aspect, 1f)
        val maxAspect = max(aspect, 1f)
        val sx = worldSize.x * 0.5f / minAspect / zoomInv
        val sy = -worldSize.y * 0.5f * maxAspect / zoomInv
        matS.setScale(sx, sy)
        matTmp.setProduct(matS, matR)
        matT.setTranslation(0f, 0f)
        matView.setProduct(matT, matTmp)
    }

    private fun packSpriteInstance(
        index: Int,
        posX: Float,
        posY: Float,
        angleTurns: Float,
        scaleX: Float,
        scaleY: Float,
        primaryId: Float,
        uvX: Float,
        uvY: Float,
        uvW: Float,
        uvH: Float,
        alpha: Float,
        squash: Float,
    ): Int {
        if (index >= SpriteShader.MAX_INSTANCES) return index

        matS.setScale(scaleX, scaleY)
        matR.setRotationZ(angleTurns * PI.toFloat())
        matTmp.setProduct(matR, matS)

        val dx = wrapDelta(posX, worldSize.x)
        val dy = wrapDelta(posY, worldSize.y)
        matT.setTranslation(dx, dy)
        matModel.setProduct(matT, matTmp)
        matTmp.setProduct(matView, matModel)

        val base = index * Mat4.FLOATS
        matTmp.copyInto(spriteMatrices, base)
        spritePrimaryIds[index] = primaryId
        spriteUvXs[index] = uvX
        spriteUvYs[index] = uvY
        spriteUvWs[index] = uvW
        spriteUvHs[index] = uvH
        spriteAlphas[index] = alpha
        spriteSquashs[index] = squash
        return index + 1
    }

    private fun spriteUvForEntity(state: SimState, entityId: EntityId): Pair<Float, Float> {
        val animState = state.components.getTable<SpriteAnimationState>()[entityId] ?: return Pair(0f, 0f)
        val atlasFrame = SpriteAnimationSystem.currentAtlasFrame(animState)
        return animState.sheet.frameUV(atlasFrame)
    }

    private fun spriteSizeForEntity(state: SimState, entityId: EntityId): Pair<Float, Float> {
        val animState = state.components.getTable<SpriteAnimationState>()[entityId] ?: return Pair(1f, 1f)
        val atlasFrame = SpriteAnimationSystem.currentAtlasFrame(animState)
        return animState.sheet.frameWH(atlasFrame)
    }

    private fun uploadVerts() {
        val verts = floatArrayOf(
            // [0..2] Triangle strip for CircleShader / PlanetShader (3 verts)
            -1f, 1.7320508f,
            2f, 0f,
            -1f, -1.7320508f,
            // [3..6] Fullscreen quad for StarscapeShader (4 verts, triangle strip)
            -1f, 1f,
            -1f, -1f,
            1f, 1f,
            1f, -1f,
        )
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    companion object {
        private const val SPRITE_SCALE_FACTOR = 1f
        private const val PICK_RADIUS_SCALE = 5f
        private const val PLANET_ATMOSPHERE_SCALE = 1.15f
        private const val FOCUS_SWITCH_FRAMES = 60
    }
}
