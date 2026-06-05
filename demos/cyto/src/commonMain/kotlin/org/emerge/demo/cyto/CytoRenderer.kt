package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoUnits
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

    fun cleanup() {
        shader.deleteProgram()
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
}
