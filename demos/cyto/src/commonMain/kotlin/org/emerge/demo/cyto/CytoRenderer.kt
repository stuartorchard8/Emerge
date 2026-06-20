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
import kotlin.math.sqrt
import kotlin.math.min

/** Cell display colour modes, cycled by the on-screen "Color" button (see [CytoRenderer.colorMode]). */
enum class CellColorMode(val label: String) {
    /** Hue from the biomass atom mix, saturation from the cytoplasm:biomass ratio (the original look). */
    Bio("BIO"),
    /** Hue from the cytoplasm atom mix — i.e. the ratio of a cell's cytoplasm contents; grey when empty. */
    Cyt("CYT"),
}

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
    /** How cells are coloured (Host-set from the controls' "Color" button). */
    var colorMode = CellColorMode.Bio
    /** EntityId.value of the focused cell (info panel open) — drawn at full value; -1 = none. Host-set. */
    var focusedCellId = -1
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
                // Perceptual ramp from pure black (no floor) up to the peak yellow (1.0, 0.9, 0.43).
                // sqrt lifts dim values so any non-zero light reads as clearly lit — ONLY t==0 is black
                // (so a cell on the fringe of the daylight band, e.g. 30% peak, no longer looks dark).
                val s = sqrt(t)
                val b = i * 4
                fieldColor[b] = s
                fieldColor[b + 1] = s * 0.90f
                fieldColor[b + 2] = s * 0.43f
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

            cellColor(cell, id.value == focusedCellId)

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

    /**
     * Colour a cell by its **contents**, per [colorMode]. **Value** is always 0.75, or 1.0 for the
     * focused cell (the one the info panel is open for).
     *
     * [CellColorMode.Bio]:
     *  - **Hue** from the a/b/c → R/G/B atom mix of its *biomass* (so a cell built of one two-atom
     *    molecule reads as a pure secondary — `ab`→yellow, `ac`→magenta, `bc`→cyan — and richer biomass
     *    shifts hue as its composition changes).
     *  - **Saturation** from the cytoplasm:biomass *instance* ratio (count species instances, not atoms):
     *    no cytoplasm → grey (0); cytoplasm ≥ biomass → full (1).
     *
     * [CellColorMode.Cyt]:
     *  - **Hue** from the a/b/c → R/G/B atom mix of its *cytoplasm* (the ratio of its cytoplasm contents),
     *    **ignoring monomer species** (single-atom molecules) — so the hue reflects the bonded-molecule mix.
     *  - **Saturation** full when it holds any non-monomer cytoplasm, grey (0) when empty.
     */
    private fun cellColor(cell: CytoCellComponent, focused: Boolean) {
        val value = if (focused) 1f else 0.75f
        var r = 0L; var g = 0L; var b = 0L
        when (colorMode) {
            CellColorMode.Bio -> {
                for ((species, count) in cell.biomass) for (ch in species) when (ch) {
                    'a' -> r += count; 'b' -> g += count; 'c' -> b += count
                }
                var cytInstances = 0; for (c in cell.cytoplasm.values) cytInstances += c
                var bioInstances = 0; for (c in cell.biomass.values) bioInstances += c
                val sat = when { cytInstances == 0 -> 0f; cytInstances >= bioInstances -> 1f; else -> cytInstances.toFloat() / bioInstances }
                hsvToRgb(hueOf(r.toFloat(), g.toFloat(), b.toFloat()), sat, value, colorTmp)
            }
            CellColorMode.Cyt -> {
                for ((species, count) in cell.cytoplasm) {
                    if (species.length < 2) continue   // ignore monomers (single-atom species)
                    for (ch in species) when (ch) {
                        'a' -> r += count; 'b' -> g += count; 'c' -> b += count
                    }
                }
                val sat = if (r + g + b > 0L) 1f else 0f
                hsvToRgb(hueOf(r.toFloat(), g.toFloat(), b.toFloat()), sat, value, colorTmp)
            }
        }
        colorTmp[3] = 1f
    }

    /** Hue (0..1) of an (r,g,b) atom-count mix; 0 (red) when colourless. */
    private fun hueOf(r: Float, g: Float, b: Float): Float {
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        if (d <= 0f) return 0f
        val h = when (max) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h / 6f
    }

    /** HSV (h,s,v all 0..1) → [out] RGB. */
    private fun hsvToRgb(h: Float, s: Float, v: Float, out: FloatArray) {
        if (s <= 0f) { out[0] = v; out[1] = v; out[2] = v; return }
        val hh = (h - kotlin.math.floor(h)) * 6f
        val i = hh.toInt(); val f = hh - i
        val p = v * (1f - s); val q = v * (1f - s * f); val t = v * (1f - s * (1f - f))
        when (i) {
            0 -> { out[0] = v; out[1] = t; out[2] = p }
            1 -> { out[0] = q; out[1] = v; out[2] = p }
            2 -> { out[0] = p; out[1] = v; out[2] = t }
            3 -> { out[0] = p; out[1] = q; out[2] = v }
            4 -> { out[0] = t; out[1] = p; out[2] = v }
            else -> { out[0] = v; out[1] = p; out[2] = q }
        }
    }

    private companion object {
        // Heatmap grid resolution per axis over one torus tile (the field is smooth, so coarse is
        // fine — a near-uniform tint up close, the 4 sources visible when zoomed out).
        const val FRES = 48
        const val FIELD_CELLS = FRES * FRES
    }
}
