package org.emerge.demo.drockets

import kotlinx.datetime.Clock
import org.emerge.demo.drockets.shader.PlanetShader
import org.emerge.demo.drockets.shader.StarscapeShader
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.SpriteShader
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.*
import kotlin.concurrent.Volatile
import kotlin.math.*

/**
 * Dedicated renderer for the Drockets demo.
 *
 * Renders in layers:
 * 0. Starscape background (fullscreen quad, no instancing)
 * 1. Planets via PlanetShader (procedural surface + atmosphere)
 * 2. Particles via CircleShader (fading circles)
 * 3. Drocket sprites via SpriteShader (textured quads from atlas)
 */
class DrocketsRenderer(
    contentScale: Vec2,
    private val drocketSpriteAtlasTextureId: Int,
    private val knightSpriteAtlasTextureId: Int,
) {
    private data class OverlayStatus(
        val message: String,
        val expiresAtMs: Long,
    )

    @Volatile
    private var overlayStatus: OverlayStatus? = null
    @Volatile
    private var showPhenotypeDebugHud: Boolean = true

    private var zoom: Float = 100f

    private var focusedId: EntityId? = null
    @kotlin.concurrent.Volatile
    private var focusRotationOffset = Coord(0)
    private var viewRotation = Coord(0)
    private var viewFocus = Coord2.zero
    private var priorFocusId: EntityId? = null
    private var focusSwitchFrameCounter = 60L
    private val focusSwitchFrames = 60

    private val vao = GPU.genAndBindVertexArrays()
    private val vbo: Int = GPU.genBuffers()
    private val starscapeShader = StarscapeShader()
    private val planetShader = PlanetShader()
    private val circleShader = CircleShader()
    private val spriteShader = SpriteShader()
    private val hudGlyphShader = HudGlyphShader()
    private var resolution: Vec2 = Vec2(1f, 1f)
    private val hudFontTextureId: Int
    private var lastDrawnState: PhysicsState? = null

    // Instance buffers for planet shader
    private val planetMatrices = FloatArray(PlanetShader.MAX_INSTANCES * M4)
    private val planetPrimaryIds = FloatArray(PlanetShader.MAX_INSTANCES)
    private val planetAlphas = FloatArray(PlanetShader.MAX_INSTANCES)

    // Instance buffers for circle shader (particles only)
    private val circleMatrices = FloatArray(CircleShader.MAX_INSTANCES * M4)
    private val circlePrimaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleSecondaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleShapes = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleAlphas = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleRadii = FloatArray(CircleShader.MAX_INSTANCES)
    private val circleTintColors = FloatArray(CircleShader.MAX_INSTANCES * 3)

    // Instance buffers for sprite shader (drockets)
    private val spriteMatrices = FloatArray(SpriteShader.MAX_INSTANCES * M4)
    private val spritePrimaryIds = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvXs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvYs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvWs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvHs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteAlphas = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteSquashs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteTintColors = FloatArray(SpriteShader.MAX_INSTANCES * 3)
    private val hudCenters = FloatArray(HudGlyphShader.MAX_GLYPHS * 2)
    private val hudHalfSizes = FloatArray(HudGlyphShader.MAX_GLYPHS * 2)
    private val hudUvRects = FloatArray(HudGlyphShader.MAX_GLYPHS * 4)
    private val hudAlphas = FloatArray(HudGlyphShader.MAX_GLYPHS)

    private val matTmp = FloatArray(M4)
    private val matT = FloatArray(M4)
    private val matR = FloatArray(M4)
    private val matS = FloatArray(M4)
    private val matView = FloatArray(M4)
    private val matModel = FloatArray(M4)

    private val worldSize = Vec2(2f, 2f)

    init {
        // SpriteShader creates its own VAO during construction, leaving it bound.
        // Rebind our main VAO before uploading vertex data for the other shaders.
        GPU.bindVertexArray(vao)
        uploadVerts()
        hudFontTextureId = createHudFontTexture()
    }

    fun setResolution(res: Vec2) {
        resolution = res
        GPU.setViewport(0, 0, res.x.toInt(), res.y.toInt())
    }

    fun zoomOut() {
        zoomByFactor(0.98f)
    }

    fun zoomIn() {
        zoomByFactor(1.02f)
    }

    fun zoomByFactor(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        zoom = (zoom * factor)//.coerceIn(1.5f, 20f)
    }

    fun rotateLeft() {
        rotateBy(Frac(1,1024))
    }

    fun rotateRight() {
        rotateBy(Frac(-1,1024))
    }

    fun focusPlanet() {
        if (focusedId != null) {
            priorFocusId = focusedId
            focusedId = null
            focusSwitchFrameCounter = 0
        }
    }

    fun rotateBy(turns: Frac) {
        focusRotationOffset += turns
    }

    fun tryFocusDrocketAt(state: PhysicsState, pixel: Vec2): Boolean {
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
            priorFocusId = focusedId
            focusedId = bestId
            focusSwitchFrameCounter = 0
            return true
        }
        return false
    }

    fun draw(state: PhysicsState) {
        lastDrawnState = state
        // Place the camera on the planet surface, orbiting with rotation
        updateViewFocus(state)

        val viewRotationRad = viewRotation.toFloat()*PI.toFloat()

        val zoomInv = 1f / zoom
        computeViewMatrix(zoomInv, viewRotationRad)

        GPU.bindVertexArray(vao)

        // ── Layer 0: starscape background (fullscreen quad, no blending) ──
        starscapeShader.draw(
            vOffset = QUAD_VERTEX_OFFSET,
            bearing = -viewRotationRad,
            resolutionX = resolution.x,
            resolutionY = resolution.y,
        )

        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()

        // ── Layer 1: planets via PlanetShader ──
        var planetCount = 0
        for (entityId in state.planets.keys()) {
            if (planetCount >= PlanetShader.MAX_INSTANCES) break
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val forceField = state.forceFields[entityId]
            val teamId = state.teams[entityId]?.teamId?.value

            // Render with atmosphere radius if force field is present, otherwise just collider
            val renderRadius = if (forceField != null) {
                (collider.radius + forceField.depth).toFloat()
            } else {
                collider.radius.toFloat() * PLANET_ATMOSPHERE_SCALE
            }

            setScale(matS, renderRadius, renderRadius)
            setRotationZ(matR, transform.ang.toFloat() * PI.toFloat())
            multiply4x4(out = matTmp, a = matR, b = matS)

            val dx = wrapDelta(transform.pos.x.toFloat() - viewFocus.x.toFloat(), worldSize.x)
            val dy = wrapDelta(transform.pos.y.toFloat() - viewFocus.y.toFloat(), worldSize.y)
            setTranslation(matT, dx, dy)
            multiply4x4(out = matModel, a = matT, b = matTmp)
            multiply4x4(out = matTmp, a = matView, b = matModel)

            val base = planetCount * M4
            matTmp.copyInto(planetMatrices, destinationOffset = base, startIndex = 0, endIndex = M4)
            planetPrimaryIds[planetCount] = if (teamId != null) (teamId + 1).toFloat() else 0f
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

        // ── Layer 2: sprites (drockets) ──
        var spriteCount = 0
        val drocketStates = state.components.getTable<DrocketStateComponent>().entries()
        val reproducers = state.components.getTable<ReproducerComponent>()
        val genomes = state.components.getTable<GenomeComponent>()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        for ((entityId, drocketState) in drocketStates) {
            if (spriteCount >= SpriteShader.MAX_INSTANCES) break
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val reproducer = reproducers[entityId] ?: continue
            val facing = drocketState.walkDirection
            val squash = 0.06125 - cos((drocketState.ticksRemaining) * 2 * PI / 60f) * 0.06125
            val teamId = state.teams[entityId]?.teamId?.value
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
                primaryId = if (teamId != null) (teamId + 1).toFloat() else 0f,
                uvX = uvX,
                uvY = uvY,
                uvW = uvW,
                uvH = uvH,
                alpha = 1f,
                squash = squash.toFloat(),
            )
            val tintBase = (spriteCount - 1) * 3
            val tintPrefix = "color_"
            val tintH = genome?.genes?.get("${tintPrefix}h")
            val tintS = genome?.genes?.get("${tintPrefix}s")
            val tintV = genome?.genes?.get("${tintPrefix}v")
            if (tintH != null && tintS != null && tintV != null) {
                val hue = decodeRangedGene(tintH, 0, 360, 0).toFloat()
                val sat = decodeRangedGene(tintS, 0, 1000, 1000).toFloat() / 1000f
                val value = decodeRangedGene(tintV, 0, 1000, 1000).toFloat() / 1000f
                val rgb = hsvToRgb(hue, sat, value)
                spriteTintColors[tintBase] = rgb.first
                spriteTintColors[tintBase + 1] = rgb.second
                spriteTintColors[tintBase + 2] = rgb.third
            } else if (
                genome?.genes?.containsKey("${tintPrefix}r") == true &&
                genome.genes.containsKey("${tintPrefix}g") &&
                genome.genes.containsKey("${tintPrefix}b")
            ) {
                // Backwards-compat for pre-HSV genomes.
                spriteTintColors[tintBase] = decodeGeneToUnit(genome.genes["${tintPrefix}r"] ?: 0)
                spriteTintColors[tintBase + 1] = decodeGeneToUnit(genome.genes["${tintPrefix}g"] ?: 0)
                spriteTintColors[tintBase + 2] = decodeGeneToUnit(genome.genes["${tintPrefix}b"] ?: 0)
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
        // Knights
        spriteCount = 0
        val knightStates = state.components.getTable<KnightStateComponent>().entries()
        for ((entityId, knightState) in knightStates) {
            if (spriteCount >= SpriteShader.MAX_INSTANCES) break
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val teamId = state.teams[entityId]?.teamId?.value
            val (uvX, uvY) = spriteUvForEntity(state, entityId)
            val (uvW, uvH) = spriteSizeForEntity(state, entityId)

            spriteCount = packSpriteInstance(
                index = spriteCount,
                posX = transform.pos.x.toFloat() - viewFocus.x.toFloat(),
                posY = transform.pos.y.toFloat() - viewFocus.y.toFloat(),
                angleTurns = transform.ang.toFloat(),
                scaleX = collider.radius.toFloat() * SPRITE_SCALE_FACTOR,
                scaleY = collider.radius.toFloat() * SPRITE_SCALE_FACTOR,
                primaryId = if (teamId != null) (teamId + 1).toFloat() else 0f,
                uvX = uvX,
                uvY = uvY,
                uvW = uvW,
                uvH = uvH,
                alpha = 1f,
                squash = 0f,
            )
            val tintBase = (spriteCount - 1) * 3
            spriteTintColors[tintBase] = 0f
            spriteTintColors[tintBase + 1] = 0f
            spriteTintColors[tintBase + 2] = 0f
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

        // Restore main VAO
        GPU.bindVertexArray(vao)

        // ── Layer 3: particles via CircleShader (from bgRenderShapes where particles live) ──
        var circleCount = 0
        val fireTintByTeam = buildFireTintByTeam(state)
        for ((entityId, particle) in state.particles.entries()) {
            if (circleCount >= CircleShader.MAX_INSTANCES) break
            val transform = state.transforms[entityId] ?: continue
            val collider = state.colliders[entityId] ?: continue
            val teamId = state.teams[entityId]?.teamId?.value

            val radius = collider.radius.toFloat()
            setScale(matS, radius, radius)
            setRotationZ(matR, transform.ang.toFloat() * PI.toFloat())
            multiply4x4(out = matTmp, a = matR, b = matS)

            val dx = wrapDelta(transform.pos.x.toFloat() - viewFocus.x.toFloat(), worldSize.x)
            val dy = wrapDelta(transform.pos.y.toFloat() - viewFocus.y.toFloat(), worldSize.y)
            setTranslation(matT, dx, dy)
            multiply4x4(out = matModel, a = matT, b = matTmp)
            multiply4x4(out = matTmp, a = matView, b = matModel)

            val base = circleCount * M4
            matTmp.copyInto(circleMatrices, destinationOffset = base, startIndex = 0, endIndex = M4)
            circlePrimaryIds[circleCount] = if (teamId != null) (teamId + 1).toFloat() else 0f
            circleSecondaryIds[circleCount] = (entityId.value + 1).toFloat()
            circleShapes[circleCount] = 0f
            circleAlphas[circleCount] = particle.life.toFloat() / particle.lifeTime.toFloat()
            circleRadii[circleCount] = radius
            val tintBase = circleCount * 3
            val teamTint = if (teamId != null) fireTintByTeam[teamId] else null
            if (teamTint != null) {
                circleTintColors[tintBase] = teamTint.first
                circleTintColors[tintBase + 1] = teamTint.second
                circleTintColors[tintBase + 2] = teamTint.third
            } else {
                circleTintColors[tintBase] = 0f
                circleTintColors[tintBase + 1] = 0f
                circleTintColors[tintBase + 2] = 0f
            }
            circleCount++
        }
        if (circleCount > 0) {
            circleShader.drawInstanced(
                vOffset = TRIANGLE_VERTEX_OFFSET,
                instanceCount = circleCount,
                matricesColMajor = circleMatrices,
                primaryIds = circlePrimaryIds,
                secondaryIds = circleSecondaryIds,
                shapes = circleShapes,
                alphas = circleAlphas,
                radii = circleRadii,
                tintColorsRgb = circleTintColors,
            )
        }
        drawOverlayHud()
        GPU.disableBlend()
    }

    fun setOverlayStatus(message: String, durationMs: Long = 2_500) {
        val expiresAt = Clock.System.now().toEpochMilliseconds() + durationMs
        overlayStatus = OverlayStatus(message = message, expiresAtMs = expiresAt)
    }

    fun currentOverlayStatus(): String? {
        val status = overlayStatus ?: return null
        if (Clock.System.now().toEpochMilliseconds() > status.expiresAtMs) {
            overlayStatus = null
            return null
        }
        return status.message
    }

    fun togglePhenotypeDebugHud() {
        showPhenotypeDebugHud = !showPhenotypeDebugHud
        setOverlayStatus(
            if (showPhenotypeDebugHud) "Phenotype HUD ON (F3)"
            else "Phenotype HUD OFF (F3)",
            durationMs = 2_000,
        )
    }

    fun cleanup() {
        starscapeShader.deleteProgram()
        planetShader.deleteProgram()
        circleShader.deleteProgram()
        spriteShader.deleteProgram()
        hudGlyphShader.deleteProgram()
        GPU.deleteTextures(hudFontTextureId)
        GPU.deleteBuffers(vbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    private fun drawOverlayHud() {
        val glyphPixelHeight = (16f * resolution.y / 600f).coerceIn(12f, 28f)
        val marginX = 12f
        val marginY = 12f
        val lineGap = glyphPixelHeight * 0.35f

        val lines = ArrayList<String>(8)
        val status = currentOverlayStatus()
        if (status != null) {
            lines += sanitizeHudText(status.uppercase())
        }
        if (showPhenotypeDebugHud) {
            val debugLines = focusedGenomeDebugLines()
            if (debugLines.isNotEmpty()) {
                for (line in debugLines) {
                    lines += sanitizeHudText(line.uppercase())
                }
            }
        }
        if (lines.isEmpty()) return

        var baselineY = marginY + glyphPixelHeight
        for (line in lines) {
            drawHudTextLine(
                message = line,
                startX = marginX,
                baselineY = baselineY,
                glyphPixelHeight = glyphPixelHeight,
            )
            baselineY += glyphPixelHeight + lineGap
        }
    }

    private fun drawHudTextLine(
        message: String,
        startX: Float,
        baselineY: Float,
        glyphPixelHeight: Float,
    ) {
        if (message.isEmpty()) return
        val glyphPixelWidth = glyphPixelHeight * 0.75f
        var cursorX = startX

        val atlasCols = HUD_FONT_COLS.toFloat()
        val uvW = 1f / atlasCols
        val uvH = 1f / HUD_FONT_ROWS.toFloat()

        var count = 0
        for (ch in message) {
            if (count >= HudGlyphShader.MAX_GLYPHS) break
            val glyphIndex = HUD_CHAR_TO_INDEX[ch] ?: HUD_CHAR_TO_INDEX['?'] ?: 0
            val col = glyphIndex % HUD_FONT_COLS
            val row = glyphIndex / HUD_FONT_COLS

            val centerX = ((cursorX + glyphPixelWidth * 0.5f) / resolution.x) * 2f - 1f
            val centerY = 1f - ((baselineY - glyphPixelHeight * 0.5f) / resolution.y) * 2f
            val halfW = (glyphPixelWidth / resolution.x)
            val halfH = (glyphPixelHeight / resolution.y)

            val base2 = count * 2
            hudCenters[base2] = centerX
            hudCenters[base2 + 1] = centerY
            hudHalfSizes[base2] = halfW
            hudHalfSizes[base2 + 1] = halfH

            val base4 = count * 4
            hudUvRects[base4] = col * uvW
            hudUvRects[base4 + 1] = row * uvH
            hudUvRects[base4 + 2] = uvW
            hudUvRects[base4 + 3] = uvH
            hudAlphas[count] = 1f

            count += 1
            cursorX += glyphPixelWidth
        }

        if (count > 0) {
            val outlinePx = (glyphPixelHeight * 0.12f).coerceIn(1f, 3f)
            drawHudGlyphBatch(
                glyphCount = count,
                offsetPxX = -outlinePx,
                offsetPxY = 0f,
                colorR = 0f,
                colorG = 0f,
                colorB = 0f,
                alphaScale = 0.85f,
            )
            drawHudGlyphBatch(
                glyphCount = count,
                offsetPxX = outlinePx,
                offsetPxY = 0f,
                colorR = 0f,
                colorG = 0f,
                colorB = 0f,
                alphaScale = 0.85f,
            )
            drawHudGlyphBatch(
                glyphCount = count,
                offsetPxX = 0f,
                offsetPxY = -outlinePx,
                colorR = 0f,
                colorG = 0f,
                colorB = 0f,
                alphaScale = 0.85f,
            )
            drawHudGlyphBatch(
                glyphCount = count,
                offsetPxX = 0f,
                offsetPxY = outlinePx,
                colorR = 0f,
                colorG = 0f,
                colorB = 0f,
                alphaScale = 0.85f,
            )
            drawHudGlyphBatch(
                glyphCount = count,
                offsetPxX = 0f,
                offsetPxY = 0f,
                colorR = 0.95f,
                colorG = 0.97f,
                colorB = 1.0f,
                alphaScale = 1f,
            )
        }
    }

    private fun focusedGenomeDebugLines(): List<String> {
        val state = lastDrawnState ?: return emptyList()
        val entityId = focusedId ?: return emptyList()
        val ds = state.components.getTable<DrocketStateComponent>()[entityId] ?: return emptyList()
        val genome = state.components.getTable<GenomeComponent>()[entityId] ?: return emptyList()
        val reproducer = state.components.getTable<ReproducerComponent>()[entityId]

        val minWalk = decodeRangedGene(genome.genes["ai_walk_min_ticks"], 1, 20_000, 120)
        val maxWalk = decodeRangedGene(genome.genes["ai_walk_max_ticks"], 1, 20_000, 600)
        val charge = decodeRangedGene(genome.genes["ai_charge_ticks"], 1, 20_000, 18)
        val fuel = decodeRangedGene(genome.genes["ai_fuel_ticks"], 1, 20_000, 200)
        val thrust = decodeRangedGene(genome.genes["ai_thrust_raw"], 0, Int.MAX_VALUE / 4096, Frac(1, 1024 * 256).raw.toInt())
        val bodyH = decodeRangedGene(genome.genes["color_h"], 0, 360, 0)
        val bodyS = decodeRangedGene(genome.genes["color_s"], 0, 1000, 0)
        val bodyV = decodeRangedGene(genome.genes["color_v"], 0, 1000, 0)
        val fireH = decodeRangedGene(genome.genes["fire_color_h"], 0, 360, 0)
        val fireS = decodeRangedGene(genome.genes["fire_color_s"], 0, 1000, 0)
        val fireV = decodeRangedGene(genome.genes["fire_color_v"], 0, 1000, 0)
        val sex = reproducer?.sex?.name ?: "NA"
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val pregnancy = when {
            reproducer?.spawn == null -> "NO"
            reproducer.spawn.birthdayMs > nowMs -> "YES DUE ${reproducer.spawn.birthdayMs - nowMs}MS"
            else -> "READY"
        }

        return listOf(
            "ID ${entityId.value} ${ds.phase}",
            "SEX ${sex} PREG ${pregnancy}",
            "WALK ${minWalk}-${maxWalk}",
            "CHARGE ${charge}  FUEL ${fuel}",
            "THRUST ${thrust}",
            "BODY HSV ${bodyH} ${bodyS} ${bodyV}",
            "FIRE HSV ${fireH} ${fireS} ${fireV}",
        )
    }

    private fun buildFireTintByTeam(state: PhysicsState): Map<Int, Triple<Float, Float, Float>> {
        val out = LinkedHashMap<Int, Triple<Float, Float, Float>>()
        val drockets = state.components.getTable<DrocketStateComponent>().entries()
        val genomes = state.components.getTable<GenomeComponent>()
        for ((entityId, _) in drockets) {
            val teamId = state.teams[entityId]?.teamId?.value ?: continue
            if (out.containsKey(teamId)) continue
            val genome = genomes[entityId] ?: continue
            val h = genome.genes["fire_color_h"] ?: continue
            val s = genome.genes["fire_color_s"] ?: continue
            val v = genome.genes["fire_color_v"] ?: continue
            val hue = decodeRangedGene(h, 0, 360, 0).toFloat()
            val sat = decodeRangedGene(s, 0, 1000, 1000).toFloat() / 1000f
            val value = decodeRangedGene(v, 0, 1000, 1000).toFloat() / 1000f
            out[teamId] = hsvToRgb(hue, sat, value)
        }
        return out
    }

    private fun drawHudGlyphBatch(
        glyphCount: Int,
        offsetPxX: Float,
        offsetPxY: Float,
        colorR: Float,
        colorG: Float,
        colorB: Float,
        alphaScale: Float,
    ) {
        val dx = if (resolution.x <= 0f) 0f else (offsetPxX / resolution.x) * 2f
        val dy = if (resolution.y <= 0f) 0f else -(offsetPxY / resolution.y) * 2f
        val count = glyphCount.coerceIn(0, HudGlyphShader.MAX_GLYPHS)
        for (i in 0 until count) {
            val base2 = i * 2
            hudCenters[base2] += dx
            hudCenters[base2 + 1] += dy
            hudAlphas[i] *= alphaScale
        }
        hudGlyphShader.drawInstanced(
            glyphCount = count,
            centers = hudCenters,
            halfSizes = hudHalfSizes,
            uvRects = hudUvRects,
            alphas = hudAlphas,
            textureId = hudFontTextureId,
            colorR = colorR,
            colorG = colorG,
            colorB = colorB,
        )
        for (i in 0 until count) {
            val base2 = i * 2
            hudCenters[base2] -= dx
            hudCenters[base2 + 1] -= dy
            hudAlphas[i] /= alphaScale
        }
    }

    // ── Vertex setup ─────────────────────────────────────────

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

    // ── Camera focus ─────────────────────────────────────────

    private fun updateViewFocus(state: PhysicsState) {
        val drocketStates = state.components.getTable<DrocketStateComponent>()

        val planetId = state.planets.keys().firstOrNull() ?: return
        val focusId = focusedId
            ?.takeIf { drocketStates[it] != null && state.transforms[it] != null }
            ?: run {
                planetId
            }

        val planetTransform = state.transforms[planetId] ?: return

        val focusPos = getFocusPos(state, focusId) ?: return
        val priorFocusId = this.priorFocusId ?: planetId
        if (focusSwitchFrameCounter < focusSwitchFrames) {
            val priorFocus = getFocusPos(state, priorFocusId)
            if (priorFocus == null) {
                viewFocus = focusPos
            } else {
                val newFocusRatio = Frac.parametric(Frac(focusSwitchFrameCounter, focusSwitchFrames))
                val oldFocusRatio = Frac(1,1) - newFocusRatio
                val focusLerp = focusPos.asFrac2()*newFocusRatio + priorFocus.asFrac2()*oldFocusRatio
                viewFocus = focusLerp.wrap()
                focusSwitchFrameCounter += 1
            }
        } else {
            viewFocus = focusPos
        }

        val planetOffset = viewFocus - planetTransform.pos
        viewRotation = Coord((
            (
                (atan2(planetOffset.x.toFloat(), -planetOffset.y.toFloat()))/-PI
            )*Int.MAX_VALUE
        ).toInt())
    }

    private fun getFocusPos(state: PhysicsState, entityId: EntityId): Coord2? {
        val transform = state.transforms[entityId] ?: return null
        val surfaceRadius = state.colliders[entityId]?.radius

        var focusPos = transform.pos
        // Place focus on the planet surface at the current rotation angle.
        // rotationRad=0 means focus is directly above the planet center (+Y).
        // The view rotation then orients "up" away from the planet.
        if (surfaceRadius == PLANET_RADIUS) {
            val viewRotationRad = Coord(focusRotationOffset.raw-transform.ang.raw).toFloat()*PI.toFloat()
            focusPos -= Frac2.raw(
                ((sin(viewRotationRad) * surfaceRadius.toFloat()) * Int.MAX_VALUE).toInt(),
                ((cos(viewRotationRad) * surfaceRadius.toFloat()) * Int.MAX_VALUE).toInt(),
            )
        }
        return focusPos
    }

    // ── View matrix ──────────────────────────────────────────

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
        setRotationZ(matR, viewRotation)
        val aspect = resolution.x / resolution.y
        val minAspect = min(aspect, 1f)
        val maxAspect = max(aspect, 1f)
        val sx = worldSize.x * 0.5f / minAspect / zoomInv
        val sy = -worldSize.y * 0.5f * maxAspect / zoomInv
        setScale(matS, sx, sy)
        multiply4x4(out = matTmp, a = matS, b = matR)
        setTranslation(matT, 0f, 0f)
        multiply4x4(out = matView, a = matT, b = matTmp)
    }

    // ── Sprite packing ───────────────────────────────────────

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

        setScale(matS, scaleX, scaleY)
        setRotationZ(matR, angleTurns * PI.toFloat())
        multiply4x4(out = matTmp, a = matR, b = matS)

        val dx = wrapDelta(posX, worldSize.x)
        val dy = wrapDelta(posY, worldSize.y)
        setTranslation(matT, dx, dy)
        multiply4x4(out = matModel, a = matT, b = matTmp)
        multiply4x4(out = matTmp, a = matView, b = matModel)

        val base = index * M4
        matTmp.copyInto(spriteMatrices, destinationOffset = base, startIndex = 0, endIndex = M4)
        spritePrimaryIds[index] = primaryId
        spriteUvXs[index] = uvX
        spriteUvYs[index] = uvY
        spriteUvWs[index] = uvW
        spriteUvHs[index] = uvH
        spriteAlphas[index] = alpha
        spriteSquashs[index] = squash
        return index + 1
    }

    private fun spriteUvForEntity(state: PhysicsState, entityId: EntityId): Pair<Float, Float> {
        val animationStates = state.components.getTable<SpriteAnimationState>()
        val animState = animationStates[entityId]
        if (animState != null) {
            val atlasFrame = SpriteAnimationSystem.currentAtlasFrame(animState)
            return animState.sheet.frameUV(atlasFrame)
        }
        return Pair(0f, 0f)
    }

    private fun spriteSizeForEntity(state: PhysicsState, entityId: EntityId): Pair<Float, Float> {
        val animationStates = state.components.getTable<SpriteAnimationState>()
        val animState = animationStates[entityId]
        if (animState != null) {
            val atlasFrame = SpriteAnimationSystem.currentAtlasFrame(animState)
            return animState.sheet.frameWH(atlasFrame)
        }
        return Pair(1f, 1f)
    }

    // ── Matrix helpers ───────────────────────────────────────

    private fun wrapDelta(d: Float, size: Float): Float {
        val half = 0.5f * size
        val a = d + half
        val m = a - floor(a / size) * size
        return m - half
    }

    private fun setIdentity(out: FloatArray) {
        for (i in 0 until M4) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
    }

    private fun setTranslation(out: FloatArray, tx: Float, ty: Float) {
        setIdentity(out); out[12] = tx; out[13] = ty
    }

    private fun setScale(out: FloatArray, sx: Float, sy: Float) {
        setIdentity(out); out[0] = sx; out[5] = sy
    }

    private fun setRotationZ(out: FloatArray, rad: Float) {
        setIdentity(out)
        val c = cos(rad)
        val s = sin(rad)
        out[0] = c; out[1] = s; out[4] = -s; out[5] = c
    }

    private fun multiply4x4(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0..3) {
            val b0 = b[col * 4]
            val b1 = b[col * 4 + 1]
            val b2 = b[col * 4 + 2]
            val b3 = b[col * 4 + 3]
            out[col * 4 + 0] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
            out[col * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
            out[col * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
            out[col * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
        }
    }

    private fun decodeGeneToUnit(gene: Int): Float {
        val norm = ((gene.toLong() - Int.MIN_VALUE.toLong()) / 4294967295.0).coerceIn(0.0, 1.0)
        return norm.toFloat()
    }

    private fun decodeRangedGene(raw: Int?, min: Int, max: Int, fallback: Int): Int {
        if (raw == null) return fallback
        val norm = ((raw.toLong() - Int.MIN_VALUE.toLong()) / 4294967295.0).coerceIn(0.0, 1.0)
        return (min + ((max - min) * norm)).toInt().coerceIn(min, max)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
        if (s <= 0f) return Triple(v, v, v)
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs(((hh / 60f) % 2f) - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    companion object {
        private const val M4 = 16
        private const val TRIANGLE_VERTEX_OFFSET = 0
        private const val QUAD_VERTEX_OFFSET = 3
        private const val SPRITE_SCALE_FACTOR = 1f
        private const val PICK_RADIUS_SCALE = 5f
        private const val PLANET_ATMOSPHERE_SCALE = 1.15f
        private const val HUD_FONT_COLS = 8
        private const val HUD_FONT_ROWS = 8
        private val HUD_GLYPHS = listOf(
            ' ', 'A', 'B', 'C', 'D', 'E', 'F', 'G',
            'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
            'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W',
            'X', 'Y', 'Z', '0', '1', '2', '3', '4',
            '5', '6', '7', '8', '9', '(', ')', ':',
            '.', ',', '-', '_', '/', '\\', '+', '=',
            '%', '!', '?', '\'', '"', '#', '*', '&',
        )
        private val HUD_CHAR_TO_INDEX: Map<Char, Int> =
            HUD_GLYPHS.withIndex().associate { it.value to it.index }
        private val HUD_PATTERN: Map<Char, Array<String>> = mapOf(
            ' ' to arrayOf("00000","00000","00000","00000","00000","00000","00000"),
            'A' to arrayOf("01110","10001","10001","11111","10001","10001","10001"),
            'B' to arrayOf("11110","10001","10001","11110","10001","10001","11110"),
            'C' to arrayOf("01110","10001","10000","10000","10000","10001","01110"),
            'D' to arrayOf("11110","10001","10001","10001","10001","10001","11110"),
            'E' to arrayOf("11111","10000","10000","11110","10000","10000","11111"),
            'F' to arrayOf("11111","10000","10000","11110","10000","10000","10000"),
            'G' to arrayOf("01111","10000","10000","10111","10001","10001","01111"),
            'H' to arrayOf("10001","10001","10001","11111","10001","10001","10001"),
            'I' to arrayOf("11111","00100","00100","00100","00100","00100","11111"),
            'J' to arrayOf("00111","00010","00010","00010","10010","10010","01100"),
            'K' to arrayOf("10001","10010","10100","11000","10100","10010","10001"),
            'L' to arrayOf("10000","10000","10000","10000","10000","10000","11111"),
            'M' to arrayOf("10001","11011","10101","10001","10001","10001","10001"),
            'N' to arrayOf("10001","11001","10101","10011","10001","10001","10001"),
            'O' to arrayOf("01110","10001","10001","10001","10001","10001","01110"),
            'P' to arrayOf("11110","10001","10001","11110","10000","10000","10000"),
            'Q' to arrayOf("01110","10001","10001","10001","10101","10010","01101"),
            'R' to arrayOf("11110","10001","10001","11110","10100","10010","10001"),
            'S' to arrayOf("01111","10000","10000","01110","00001","00001","11110"),
            'T' to arrayOf("11111","00100","00100","00100","00100","00100","00100"),
            'U' to arrayOf("10001","10001","10001","10001","10001","10001","01110"),
            'V' to arrayOf("10001","10001","10001","10001","10001","01010","00100"),
            'W' to arrayOf("10001","10001","10001","10001","10101","11011","10001"),
            'X' to arrayOf("10001","10001","01010","00100","01010","10001","10001"),
            'Y' to arrayOf("10001","10001","01010","00100","00100","00100","00100"),
            'Z' to arrayOf("11111","00001","00010","00100","01000","10000","11111"),
            '0' to arrayOf("01110","10001","10011","10101","11001","10001","01110"),
            '1' to arrayOf("00100","01100","00100","00100","00100","00100","01110"),
            '2' to arrayOf("01110","10001","00001","00010","00100","01000","11111"),
            '3' to arrayOf("11110","00001","00001","01110","00001","00001","11110"),
            '4' to arrayOf("00010","00110","01010","10010","11111","00010","00010"),
            '5' to arrayOf("11111","10000","10000","11110","00001","00001","11110"),
            '6' to arrayOf("01110","10000","10000","11110","10001","10001","01110"),
            '7' to arrayOf("11111","00001","00010","00100","01000","01000","01000"),
            '8' to arrayOf("01110","10001","10001","01110","10001","10001","01110"),
            '9' to arrayOf("01110","10001","10001","01111","00001","00001","01110"),
            '(' to arrayOf("00010","00100","01000","01000","01000","00100","00010"),
            ')' to arrayOf("01000","00100","00010","00010","00010","00100","01000"),
            ':' to arrayOf("00000","00100","00100","00000","00100","00100","00000"),
            '.' to arrayOf("00000","00000","00000","00000","00000","00100","00100"),
            ',' to arrayOf("00000","00000","00000","00000","00100","00100","01000"),
            '-' to arrayOf("00000","00000","00000","11111","00000","00000","00000"),
            '_' to arrayOf("00000","00000","00000","00000","00000","00000","11111"),
            '/' to arrayOf("00001","00010","00100","01000","10000","00000","00000"),
            '\\' to arrayOf("10000","01000","00100","00010","00001","00000","00000"),
            '+' to arrayOf("00000","00100","00100","11111","00100","00100","00000"),
            '=' to arrayOf("00000","11111","00000","11111","00000","00000","00000"),
            '%' to arrayOf("11001","11010","00100","01000","10110","00110","00000"),
            '!' to arrayOf("00100","00100","00100","00100","00100","00000","00100"),
            '?' to arrayOf("01110","10001","00001","00010","00100","00000","00100"),
            '\'' to arrayOf("00100","00100","00000","00000","00000","00000","00000"),
            '"' to arrayOf("01010","01010","00000","00000","00000","00000","00000"),
            '#' to arrayOf("01010","11111","01010","01010","11111","01010","00000"),
            '*' to arrayOf("00000","10101","01110","11111","01110","10101","00000"),
            '&' to arrayOf("01100","10010","10100","01000","10101","10010","01101"),
        )

        private fun sanitizeHudText(input: String): String {
            if (input.isEmpty()) return ""
            val out = StringBuilder(input.length)
            for (ch in input) {
                if (HUD_CHAR_TO_INDEX.containsKey(ch)) {
                    out.append(ch)
                } else {
                    out.append('?')
                }
            }
            return out.toString()
        }

        private fun createHudFontTexture(): Int {
            val glyphW = 6
            val glyphH = 8
            val texW = HUD_FONT_COLS * glyphW
            val texH = HUD_FONT_ROWS * glyphH
            val pixels = ByteArray(texW * texH * 4)

            for ((index, ch) in HUD_GLYPHS.withIndex()) {
                val pattern = HUD_PATTERN[ch] ?: HUD_PATTERN[' ']!!
                val col = index % HUD_FONT_COLS
                val row = index / HUD_FONT_COLS
                val x0 = col * glyphW
                val y0 = row * glyphH
                for (y in 0 until 7) {
                    val line = pattern[y]
                    for (x in 0 until 5) {
                        if (line[x] != '1') continue
                        val px = x0 + x
                        val py = y0 + y
                        val base = (py * texW + px) * 4
                        pixels[base] = 0xFF.toByte()
                        pixels[base + 1] = 0xFF.toByte()
                        pixels[base + 2] = 0xFF.toByte()
                        pixels[base + 3] = 0xFF.toByte()
                    }
                }
            }

            val textureId = GPU.genTextures()
            GPU.bindTexture2D(textureId)
            GPU.uploadTextureRGBA8(texW, texH, pixels)
            GPU.configureTexture2DClampNearest()
            return textureId
        }
    }
}
