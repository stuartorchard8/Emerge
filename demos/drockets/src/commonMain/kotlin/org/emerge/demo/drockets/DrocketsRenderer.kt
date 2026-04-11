package org.emerge.demo.drockets

import org.emerge.demo.drockets.shader.PlanetShader
import org.emerge.demo.drockets.shader.StarscapeShader
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.SpriteShader
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Vec2
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
    private val spriteAtlasTextureId: Int,
    private val spriteAtlasColumns: Int,
    private val spriteAtlasRows: Int,
) {
    private var zoom: Float = 10f

    @kotlin.concurrent.Volatile
    private var rotationRad: Float = 0f

    // Updated each frame from the first planet's position + surface offset
    private var viewFocus: Vec2 = Vec2(0f, 0f)

    private val vao = GPU.genAndBindVertexArrays()
    private val vbo: Int = GPU.genBuffers()
    private val starscapeShader = StarscapeShader()
    private val planetShader = PlanetShader()
    private val circleShader = CircleShader()
    private val spriteShader = SpriteShader()
    private var resolution: Vec2 = Vec2(1f, 1f)

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

    // Instance buffers for sprite shader (drockets)
    private val spriteMatrices = FloatArray(SpriteShader.MAX_INSTANCES * M4)
    private val spritePrimaryIds = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvXs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvYs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvWs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteUvHs = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteAlphas = FloatArray(SpriteShader.MAX_INSTANCES)
    private val spriteSquashs = FloatArray(SpriteShader.MAX_INSTANCES)

    private val matTmp = FloatArray(M4)
    private val matT = FloatArray(M4)
    private val matR = FloatArray(M4)
    private val matS = FloatArray(M4)
    private val matView = FloatArray(M4)
    private val matModel = FloatArray(M4)

    private val frameSizeX = 1f / spriteAtlasColumns
    private val frameSizeY = 1f / spriteAtlasRows

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
        rotateBy(0.03f)
    }

    fun rotateRight() {
        rotateBy(-0.03f)
    }

    fun rotateBy(deltaRad: Float) {
        if (!deltaRad.isFinite()) return
        rotationRad += deltaRad
    }

    fun draw(state: PhysicsState) {
        // Place the camera on the planet surface, orbiting with rotation
        updateViewFocus(state)

        val zoomInv = 1f / zoom
        computeViewMatrix(zoomInv)

        GPU.bindVertexArray(vao)

        // ── Layer 0: starscape background (fullscreen quad, no blending) ──
        starscapeShader.draw(
            vOffset = QUAD_VERTEX_OFFSET,
            bearing = -rotationRad,
            resolutionX = resolution.x,
            resolutionY = resolution.y,
        )

        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()

        // ── Layer 1: planets via PlanetShader ──
        var planetCount = 0
        for (entityId in state.raw.planets.keys()) {
            if (planetCount >= PlanetShader.MAX_INSTANCES) break
            val transform = state.raw.transforms[entityId] ?: continue
            val collider = state.raw.colliders[entityId] ?: continue
            val forceField = state.raw.forceFields[entityId]
            val teamId = state.raw.teams[entityId]?.teamId?.value

            // Render with atmosphere radius if force field is present, otherwise just collider
            val renderRadius = if (forceField != null) {
                (collider.radius + forceField.depth).toFloat()
            } else {
                collider.radius.toFloat() * PLANET_ATMOSPHERE_SCALE
            }

            setScale(matS, renderRadius, renderRadius)
            setRotationZ(matR, transform.ang.toFloat() * PI.toFloat())
            multiply4x4(out = matTmp, a = matR, b = matS)

            val dx = wrapDelta(transform.pos.x.toFloat() - viewFocus.x, worldSize.x)
            val dy = wrapDelta(transform.pos.y.toFloat() - viewFocus.y, worldSize.y)
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
        for ((entityId, drocketState) in DrocketsRegistry.drocketStates) {
            if (spriteCount >= SpriteShader.MAX_INSTANCES) break
            val transform = state.raw.transforms[entityId] ?: continue
            val collider = state.raw.colliders[entityId] ?: continue
            val facing = drocketState.walkDirection
            val squash = 0.06125 - cos((drocketState.ticksRemaining) * 2 * PI / 60f) * 0.06125
            val teamId = state.raw.teams[entityId]?.teamId?.value
            val (uvX, uvY) = spriteUvForEntity(entityId)
            val (uvW, uvH) = spriteSizeForEntity(entityId)

            spriteCount = packSpriteInstance(
                index = spriteCount,
                posX = transform.pos.x.toFloat(),
                posY = transform.pos.y.toFloat(),
                angleTurns = transform.ang.toFloat(),
                scaleX = collider.radius.toFloat() * SPRITE_SCALE_FACTOR,
                scaleY = collider.radius.toFloat() * SPRITE_SCALE_FACTOR * facing,
                primaryId = if (teamId != null) (teamId + 1).toFloat() else 0f,
                uvX = uvX,
                uvY = uvY,
                uvW = uvW,
                uvH = uvH,
                alpha = 1f,
                squash = squash.toFloat(),
            )
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
                textureId = spriteAtlasTextureId,
                frameSizeX = frameSizeX * 13f / 16f,
                frameSizeY = frameSizeY,
            )
        }

        // Restore main VAO
        GPU.bindVertexArray(vao)

        // ── Layer 3: particles via CircleShader (from bgRenderShapes where particles live) ──
        var circleCount = 0
        for ((entityId, particle) in state.raw.particles.entries()) {
            if (circleCount >= CircleShader.MAX_INSTANCES) break
            val transform = state.raw.transforms[entityId] ?: continue
            val collider = state.raw.colliders[entityId] ?: continue
            val teamId = state.raw.teams[entityId]?.teamId?.value

            val radius = collider.radius.toFloat()
            setScale(matS, radius, radius)
            setRotationZ(matR, transform.ang.toFloat() * PI.toFloat())
            multiply4x4(out = matTmp, a = matR, b = matS)

            val dx = wrapDelta(transform.pos.x.toFloat() - viewFocus.x, worldSize.x)
            val dy = wrapDelta(transform.pos.y.toFloat() - viewFocus.y, worldSize.y)
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
            )
        }
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
        val drocketId = DrocketsRegistry.drocketStates.keys.firstOrNull() ?: return
//        val planetId = state.raw.planets.keys().firstOrNull() ?: return
        val transform = state.raw.transforms[drocketId] ?: return
        val collider = state.raw.colliders[drocketId] ?: return

        val focusX = transform.pos.x.toFloat()
        val focusY = transform.pos.y.toFloat()
        val surfaceRadius = collider.radius.toFloat()

        // Place focus on the planet surface at the current rotation angle.
        // rotationRad=0 means focus is directly above the planet center (+Y).
        // The view rotation then orients "up" away from the planet.
        viewFocus = Vec2(
            focusX - sin(rotationRad) * surfaceRadius,
            focusY - cos(rotationRad) * surfaceRadius,
        )
    }

    // ── View matrix ──────────────────────────────────────────

    private fun computeViewMatrix(zoomInv: Float) {
        setRotationZ(matR, rotationRad)
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

        val dx = wrapDelta(posX - viewFocus.x, worldSize.x)
        val dy = wrapDelta(posY - viewFocus.y, worldSize.y)
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

    private fun spriteUvForEntity(entityId: EntityId): Pair<Float, Float> {
        val animState = DrocketsRegistry.animationStates[entityId]
        if (animState != null) {
            val atlasFrame = SpriteAnimationSystem.currentAtlasFrame(animState, DROCKET_SPRITE_SHEET)
            return DROCKET_SPRITE_SHEET.frameUV(atlasFrame)
        }
        return Pair(0f, 0f)
    }

    private fun spriteSizeForEntity(entityId: EntityId): Pair<Float, Float> {
        val animState = DrocketsRegistry.animationStates[entityId]
        if (animState != null) {
            val atlasFrame = SpriteAnimationSystem.currentAtlasFrame(animState, DROCKET_SPRITE_SHEET)
            return DROCKET_SPRITE_SHEET.frameWH(atlasFrame)
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

    companion object {
        private const val M4 = 16
        private const val TRIANGLE_VERTEX_OFFSET = 0
        private const val QUAD_VERTEX_OFFSET = 3
        private const val SPRITE_SCALE_FACTOR = 1f
        private const val PLANET_ATMOSPHERE_SCALE = 1.15f
    }
}
