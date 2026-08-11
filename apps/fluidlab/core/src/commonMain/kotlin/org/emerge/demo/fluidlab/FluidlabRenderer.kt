package org.emerge.demo.fluidlab

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.CircleShader
import kotlin.math.abs
import kotlin.math.max

/**
 * Draws the world, and owns the camera.
 *
 * Everything here runs on the GL thread and only on the GL thread — construct it inside
 * `onSurfaceCreated` / after `glfwMakeContextCurrent`, never in a field initialiser that runs
 * earlier, or shader compilation happens with no current context.
 *
 * The engine's [CircleShader] draws instanced discs: one draw call for every body in the world,
 * with per-instance transform, colour and alpha. Give it a matrix per body and it will happily
 * take thousands. Game-specific looks belong in your own shader beside this file — see
 * `apps/cyto/core/.../CytoCellShader.kt` or `apps/drockets/core/.../shader/PlanetShader.kt`,
 * and note the `registerShaderCodegen` line in a build file, which turns `.vert`/`.frag` files
 * into Kotlin sources so shaders work on desktop GL, GLES and WebGL alike.
 */
class FluidlabRenderer(private val cfg: FluidlabConfig = FluidlabConfig()) {

    // ── Camera ────────────────────────────────────────────────────────────────────
    // World-space point at the centre of the screen, and NDC units per world unit vertically.
    // zoom == 1 fits exactly `worldSize` world units into the screen height.
    private var camX = 0f
    private var camY = 0f
    private var zoom = 1f

    private var resW = 1f
    private var resH = 1f

    private val vao = GPU.genAndBindVertexArrays()
    private val vbo = GPU.genBuffers()
    private val circleShader = CircleShader()

    private var matrices = FloatArray(0)
    private var primaryIds = FloatArray(0)
    private var shapes = FloatArray(0)
    private var alphas = FloatArray(0)
    private var tints = FloatArray(0)

    private val matView = Mat4.scratch()
    private val matModel = Mat4.scratch()
    private val matT = Mat4.scratch()
    private val matS = Mat4.scratch()
    private val matOut = Mat4.scratch()

    init {
        GPU.bindVertexArray(vao)
        uploadVerts()
        ensureCapacity(256)
    }

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = max(1f, widthPx)
        resH = max(1f, heightPx)
        GPU.setViewport(0, 0, resW.toInt(), resH.toInt())
    }

    /** Pixels per world unit at the current zoom — identical on both axes, so circles stay circular. */
    private val pixelsPerWorldUnit: Float get() = zoom * resH * 0.5f

    /** Drag-to-pan: moves the camera so the world appears to follow the pointer. */
    fun panByPixels(dxPixels: Float, dyPixels: Float) {
        val s = pixelsPerWorldUnit
        camX -= dxPixels / s
        camY -= dyPixels / s
        camX = wrap(camX, cfg.worldSize * 0.5f)
        camY = wrap(camY, cfg.worldSize * 0.5f)
    }

    /** Wheel/pinch zoom that keeps the world point under [px],[py] pinned there. */
    fun zoomAtScreen(px: Float, py: Float, factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val before = screenToWorld(px, py)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val after = screenToWorld(px, py)
        camX = wrap(camX + wrapDelta(before[0] - after[0], cfg.worldSize), cfg.worldSize * 0.5f)
        camY = wrap(camY + wrapDelta(before[1] - after[1], cfg.worldSize), cfg.worldSize * 0.5f)
    }

    /** Framebuffer pixel → world coordinates. Pair this with [worldToScreen] for labels/pick tests. */
    fun screenToWorld(px: Float, py: Float): FloatArray {
        val ndcX = px / resW * 2f - 1f
        val ndcY = 1f - py / resH * 2f
        val aspect = resW / resH
        return floatArrayOf(
            camX + ndcX * aspect / zoom,
            camY - ndcY / zoom,
        )
    }

    /** World coordinates → framebuffer pixel. */
    fun worldToScreen(wx: Float, wy: Float): FloatArray {
        val aspect = resW / resH
        val dx = wrapDelta(wx - camX, cfg.worldSize)
        val dy = wrapDelta(wy - camY, cfg.worldSize)
        val ndcX = dx * zoom / aspect
        val ndcY = -dy * zoom
        return floatArrayOf((ndcX + 1f) * 0.5f * resW, (1f - ndcY) * 0.5f * resH)
    }

    /** Draws one frame of [state]. Call before the UI draws, so panels sit on top. */
    fun draw(state: FluidlabState) {
        GPU.setClearColor(0.04f, 0.05f, 0.08f, 1f)
        GPU.clearColorBuffer()

        GPU.bindVertexArray(vao)
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()

        computeViewMatrix()
        ensureCapacity(state.bodies.size)

        var n = 0
        for (b in state.bodies) {
            if (n >= CircleShader.MAX_INSTANCES) break
            // Cull what is off-screen. At fluidlab scale this is free; at Cyto scale it is the
            // difference between a smooth frame and a slideshow, so the habit is worth forming.
            val dx = wrapDelta(b.x - camX, cfg.worldSize)
            val dy = wrapDelta(b.y - camY, cfg.worldSize)
            val halfW = (resW / resH) / zoom + b.radius
            val halfH = 1f / zoom + b.radius
            if (abs(dx) > halfW || abs(dy) > halfH) continue

            matT.setTranslation(dx, dy)
            matS.setScale(b.radius, b.radius)
            matModel.setProduct(matT, matS)
            matOut.setProduct(matView, matModel)
            matOut.copyInto(matrices, n * Mat4.FLOATS)

            primaryIds[n] = 0f
            shapes[n] = CircleShader.SHAPE_DISC
            alphas[n] = 1f
            val rgb = hueToRgb(b.hue)
            tints[n * 3] = rgb[0]
            tints[n * 3 + 1] = rgb[1]
            tints[n * 3 + 2] = rgb[2]
            n++
        }

        circleShader.drawInstanced(
            vOffset = 0,
            instanceCount = n,
            matricesColMajor = matrices,
            primaryIds = primaryIds,
            shapes = shapes,
            alphas = alphas,
            tintColorsRgb = tints,
        )
        GPU.disableBlend()
    }

    /** Frees GPU objects. Desktop hosts call this on window close; mobile hosts let the context die. */
    fun cleanup() {
        circleShader.deleteProgram()
        GPU.deleteBuffers(vbo)
        vao?.let { GPU.deleteVertexArrays(it) }
    }

    // ── Internals ─────────────────────────────────────────────────────────────────

    /**
     * World → clip space. Only a scale: the per-body translation is folded into the model matrix so
     * it can be the *wrapped* delta to the camera, which is what makes a body at the far edge of the
     * torus draw just off the near edge instead of flying across the screen.
     */
    private fun computeViewMatrix() {
        val aspect = resW / resH
        // Negative Y so world +y points down the screen, matching pointer coordinates.
        matView.setScale(zoom / aspect, -zoom)
    }

    private fun ensureCapacity(count: Int) {
        val n = count.coerceAtMost(CircleShader.MAX_INSTANCES)
        if (n <= alphas.size) return
        val cap = maxOf(n, alphas.size * 2, 256).coerceAtMost(CircleShader.MAX_INSTANCES)
        matrices = FloatArray(cap * Mat4.FLOATS)
        primaryIds = FloatArray(cap)
        shapes = FloatArray(cap)
        alphas = FloatArray(cap)
        tints = FloatArray(cap * 3)
    }

    private fun uploadVerts() {
        // One triangle that circumscribes the unit circle. The shader discards fragments outside the
        // disc, so three vertices draw a perfectly round, antialiased circle at any zoom.
        val verts = floatArrayOf(
            -1f, 1.7320508f,
            2f, 0f,
            -1f, -1.7320508f,
        )
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun hueToRgb(hue: Float): FloatArray {
        val h = (hue - kotlin.math.floor(hue)) * 6f
        val i = h.toInt()
        val f = h - i
        val q = 1f - f
        return when (i) {
            0 -> floatArrayOf(1f, f, 0f)
            1 -> floatArrayOf(q, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, f)
            3 -> floatArrayOf(0f, q, 1f)
            4 -> floatArrayOf(f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, q)
        }
    }

    companion object {
        private const val MIN_ZOOM = 0.2f
        private const val MAX_ZOOM = 60f
    }
}
