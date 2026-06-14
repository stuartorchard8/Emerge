package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.render.torus.ui.UiRectRenderer
import org.emerge.render.torus.GPU
import org.emerge.render.torus.Mat4
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the native Cyto world on Emerge's GPU. Reads the [org.emerge.sim.core.sim.SimState]
 * component tables (transform, collider, cell type, spring connections) and routes each
 * cell through [CytoCellShader]. Owns a flat 2D camera in *logical* Cyto units (the engine
 * fixed-point positions are converted back via [CytoUnits]); the membrane-blend neighbour
 * data is the torus-aware delta to each connected cell, with y flipped to match the shader.
 */
class CytoRenderer {
    private val shader = CytoCellShader()

    // Full-screen background fill, drawn first each frame — clears the previous frame
    // without a platform-specific glClear (the engine GPU doesn't expose one), so this
    // works identically on desktop, Android, and web.
    private val bgShader = UiRectRenderer()

    // The cell shader does `min(u_color, texture)`, so a flat white texture yields the
    // cell's colour; the disc shape + shading come from the shader, not the texture. Built
    // procedurally so no PNG asset is needed (works identically on desktop/Android/web).
    private val cellTextureId = createWhiteTexture()

    private var resW = 1f
    private var resH = 1f

    private var centerX = 0f
    private var centerY = 0f
    private var viewHeight = 100f

    private val matP = Mat4.scratch()
    private val matS = Mat4.scratch()
    private val matT = Mat4.scratch()
    private val matM = Mat4.scratch()
    private val matMS = Mat4.scratch()
    private val matMT = Mat4.scratch()
    private val mvp = Mat4.scratch()
    private val colorTmp = FloatArray(4)
    private val neighbourTmp = FloatArray(CytoCellShader.MAX_NEIGHBOURS * 4)

    private val BG_CENTER = floatArrayOf(0f, 0f)
    private val BG_HALF_SIZE = floatArrayOf(1f, 1f)
    private val BG_COLOR = floatArrayOf(0f, 0f, 0f, 1f)

    // ── light-field heatmap (the energy landscape, drawn as the background) ──────────────
    // Reuses the proven instanced-rect shader: the static field grid (one torus tile) baked to
    // heat colours once, projected to NDC + culled to the visible region each frame. Toggle with L.
    var showLightField = true
    private val fieldShader = UiRectRenderer(maxRects = FIELD_CELLS)
    private val fieldCx = FloatArray(FIELD_CELLS)
    private val fieldCy = FloatArray(FIELD_CELLS)
    private val fieldColor = FloatArray(FIELD_CELLS * 4)
    private val fieldHalfLogical = CytoLightField.SPAN / FRES * 0.5f
    private val fInstCenter = FloatArray(FIELD_CELLS * 2)
    private val fInstHalf = FloatArray(FIELD_CELLS * 2)
    private val fInstColor = FloatArray(FIELD_CELLS * 4)

    init { bakeFieldColors(0L) }

    /** Bake the light-field heatmap colours for sim-time [tick]. Static field → baked once at init; the
     *  moving field → re-baked each frame in [draw] so the daylight band animates. */
    private fun bakeFieldColors(tick: Long) {
        val field = CytoLightField.default()
        val cell = CytoLightField.SPAN / FRES
        var i = 0
        for (gy in 0 until FRES) {
            val wy = -CytoLightField.HALF + (gy + 0.5f) * cell
            for (gx in 0 until FRES) {
                val wx = -CytoLightField.HALF + (gx + 0.5f) * cell
                fieldCx[i] = wx; fieldCy[i] = wy
                val t = (field.sampleAt(wx, wy, tick).toFloat() / CytoLightField.STRENGTH.toFloat()).coerceIn(0f, 1f)
                val b = i * 4
                fieldColor[b] = 0.06f + t * 0.94f
                fieldColor[b + 1] = 0.05f + t * 0.85f
                fieldColor[b + 2] = 0.10f + t * 0.33f
                fieldColor[b + 3] = 1f
                i++
            }
        }
    }

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = max(1f, widthPx)
        resH = max(1f, heightPx)
        GPU.setViewport(0, 0, resW.toInt(), resH.toInt())
    }

    fun panByPixels(dxPx: Float, dyPx: Float) {
        val worldPerPx = viewHeight / resH
        centerX -= dxPx * worldPerPx
        centerY += dyPx * worldPerPx
    }

    fun zoomByFactor(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        viewHeight = (viewHeight / factor).coerceIn(0.5f, 100_000f)
    }

    /** Zoom by [factor] while keeping the world point under screen pixel ([px], [py]) fixed. */
    fun zoomAtScreen(px: Float, py: Float, factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val before = screenToWorld(px, py)
        viewHeight = (viewHeight / factor).coerceIn(0.5f, 100_000f)
        val after = screenToWorld(px, py)
        centerX += before[0] - after[0]
        centerY += before[1] - after[1]
    }

    /** Framebuffer pixel -> logical world `[x, y]`. */
    fun screenToWorld(px: Float, py: Float): FloatArray {
        val aspect = resW / resH
        val viewWidth = viewHeight * aspect
        val ndcX = px / resW * 2f - 1f
        val ndcY = 1f - py / resH * 2f
        return floatArrayOf(
            centerX + ndcX * viewWidth * 0.5f,
            centerY + ndcY * viewHeight * 0.5f,
        )
    }

    /** Logical world (x, y) -> framebuffer pixel `[px, py]` (inverse of [screenToWorld]). */
    fun worldToScreen(worldX: Float, worldY: Float): FloatArray {
        val aspect = resW / resH
        val viewWidth = viewHeight * aspect
        val ndcX = (worldX - centerX) / (viewWidth * 0.5f)
        val ndcY = (worldY - centerY) / (viewHeight * 0.5f)
        return floatArrayOf(
            (ndcX + 1f) * 0.5f * resW,
            (1f - ndcY) * 0.5f * resH,
        )
    }

    fun draw(frame: CytoFrame) {
        computeProjection()

        // Background fill (opaque) — clears the frame.
        GPU.disableBlend()
        bgShader.drawInstanced(1, BG_CENTER, BG_HALF_SIZE, BG_COLOR)
        // Light-field heatmap over the world (opaque, on top of the clear, under the cells).
        if (org.emerge.demo.cyto.sim.CytoTuning.LIGHT_MOVING) bakeFieldColors(frame.tick)   // animate the band
        drawLightField()

        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        shader.begin(cellTextureId)

        val components = frame.state.components
        val cells = components.getTable<CytoCellComponent>().asMap()
        val transforms = components.getTable<TransformComponent>()
        val colliders = components.getTable<ColliderComponent>()
        val springs = components.getTable<SpringConstraintComponent>()

        for ((id, cell) in cells) {
            val transform = transforms[id] ?: continue
            val collider = colliders[id] ?: continue
            val radius = CytoUnits.toLogical(collider.radius)
            val cx = CytoUnits.toLogical(transform.pos.x)
            val cy = CytoUnits.toLogical(transform.pos.y)

            matMS.setScale(2f * radius, 2f * radius)
            matMT.setTranslation(cx, cy)
            matM.setProduct(matMT, matMS)
            mvp.setProduct(matP, matM)

            packColor(cell.type.color)

            var count = 0
            val neighbours = springs[id]?.springs
            if (neighbours != null) {
                for (spring in neighbours) {
                    if (count >= CytoCellShader.MAX_NEIGHBOURS) break
                    val nt = transforms[spring.other] ?: continue
                    val nr = colliders[spring.other] ?: continue
                    // Torus-aware delta (Coord2 - Coord2 = shortest Frac2), y flipped for the shader.
                    val delta = nt.pos - transform.pos
                    val base = count * 4
                    neighbourTmp[base] = CytoUnits.toLogical(delta.x)
                    neighbourTmp[base + 1] = -CytoUnits.toLogical(delta.y)
                    neighbourTmp[base + 2] = CytoUnits.toLogical(nr.radius)
                    neighbourTmp[base + 3] = 0f
                    count++
                }
            }

            shader.draw(
                mvp = mvp,
                radiusUniform = radius * 2f,
                color = colorTmp,
                neighbours = neighbourTmp,
                count = count,
            )
        }

        GPU.disableBlend()
    }

    /** Draw the static light field as a heatmap: project each grid cell (one torus tile) to NDC,
     *  cull off-screen, instance-draw the visible ones. Camera has no rotation, so world→NDC is a
     *  pure scale+translate and the axis-aligned cells stay axis-aligned. */
    private fun drawLightField() {
        if (!showLightField) return
        val aspect = resW / resH
        val hwx = viewHeight * aspect * 0.5f
        val hwy = viewHeight * 0.5f
        if (hwx <= 0f || hwy <= 0f) return
        val chx = fieldHalfLogical / hwx
        val chy = fieldHalfLogical / hwy
        var n = 0
        for (i in 0 until FIELD_CELLS) {
            val ndcX = (fieldCx[i] - centerX) / hwx
            val ndcY = (fieldCy[i] - centerY) / hwy
            if (ndcX < -1f - chx || ndcX > 1f + chx || ndcY < -1f - chy || ndcY > 1f + chy) continue
            val c2 = n * 2; val c4 = n * 4; val s4 = i * 4
            fInstCenter[c2] = ndcX; fInstCenter[c2 + 1] = ndcY
            fInstHalf[c2] = chx; fInstHalf[c2 + 1] = chy
            fInstColor[c4] = fieldColor[s4]; fInstColor[c4 + 1] = fieldColor[s4 + 1]
            fInstColor[c4 + 2] = fieldColor[s4 + 2]; fInstColor[c4 + 3] = 1f
            n++
        }
        if (n > 0) fieldShader.drawInstanced(n, fInstCenter, fInstHalf, fInstColor)
    }

    fun cleanup() {
        shader.deleteProgram()
        bgShader.deleteProgram()
        fieldShader.deleteProgram()
        GPU.deleteTextures(cellTextureId)
    }

    private fun createWhiteTexture(): Int {
        val data = ByteArray(2 * 2 * 4) { 0xFF.toByte() }
        val id = GPU.genTextures()
        GPU.activeTexture(0)
        GPU.bindTexture2D(id)
        GPU.configureTexture2DRepeatLinear()
        GPU.uploadTextureRGBA8(2, 2, data)
        GPU.bindTexture2D(0)
        return id
    }

    private fun computeProjection() {
        val aspect = resW / resH
        val viewWidth = viewHeight * aspect
        matT.setTranslation(-centerX, -centerY)
        matS.setScale(2f / viewWidth, 2f / viewHeight)
        matP.setProduct(matS, matT)
    }

    private fun packColor(rgba: Long) {
        colorTmp[0] = ((rgba ushr 24) and 0xFF).toFloat() / 255f
        colorTmp[1] = ((rgba ushr 16) and 0xFF).toFloat() / 255f
        colorTmp[2] = ((rgba ushr 8) and 0xFF).toFloat() / 255f
        colorTmp[3] = (rgba and 0xFF).toFloat() / 255f
    }

    private companion object {
        // Heatmap grid resolution per axis over one torus tile (the field is smooth, so coarse is
        // fine — a near-uniform tint up close, the 4 sources visible when zoomed out).
        const val FRES = 48
        const val FIELD_CELLS = FRES * FRES
    }
}
