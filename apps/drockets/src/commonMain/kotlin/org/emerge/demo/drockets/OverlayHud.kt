package org.emerge.demo.drockets

import kotlinx.datetime.Clock
import org.emerge.render.torus.GPU
import org.emerge.sim.core.physics.primitives.Vec2
import kotlin.concurrent.Volatile

/**
 * Top-left text overlay drawn over the main scene.
 *
 * Owns its own state for two HUD-specific facets:
 *  - **Status messages**: time-limited toast strings ([setOverlayStatus]).
 *  - **Phenotype debug toggle**: whether per-entity gene debug lines are visible
 *    ([togglePhenotypeDebug]); the actual lines are produced by the caller.
 *
 * Rendering is layered: each line is drawn 4 times with a 1px black outline offset
 * and once in white on top for legibility against any background.
 */
class OverlayHud {
    private data class OverlayStatus(val message: String, val expiresAtMs: Long)

    @Volatile private var overlayStatus: OverlayStatus? = null
    @Volatile var showPhenotypeDebug: Boolean = true
        private set

    private val hudGlyphShader = HudGlyphShader()
    private val hudFontTextureId: Int = createHudFontTexture()

    private val hudCenters = FloatArray(HudGlyphShader.MAX_GLYPHS * 2)
    private val hudHalfSizes = FloatArray(HudGlyphShader.MAX_GLYPHS * 2)
    private val hudUvRects = FloatArray(HudGlyphShader.MAX_GLYPHS * 4)
    private val hudAlphas = FloatArray(HudGlyphShader.MAX_GLYPHS)

    private var resolution: Vec2 = Vec2(1f, 1f)

    fun setResolution(res: Vec2) { resolution = res }

    fun setOverlayStatus(message: String, durationMs: Long = 2_500) {
        val expiresAt = Clock.System.now().toEpochMilliseconds() + durationMs
        overlayStatus = OverlayStatus(message = message, expiresAtMs = expiresAt)
    }

    /** Returns the active status message, expiring it if past its timeout. */
    fun currentOverlayStatus(): String? {
        val status = overlayStatus ?: return null
        if (Clock.System.now().toEpochMilliseconds() > status.expiresAtMs) {
            overlayStatus = null
            return null
        }
        return status.message
    }

    fun togglePhenotypeDebug() {
        showPhenotypeDebug = !showPhenotypeDebug
        setOverlayStatus(
            if (showPhenotypeDebug) "Phenotype HUD ON (F3)" else "Phenotype HUD OFF (F3)",
            durationMs = 2_000,
        )
    }

    /**
     * Draws the current status (if any) followed by [extraLines], top-left, line-stacked.
     * Each line is uppercased and run through [sanitizeHudText] (replacing unsupported
     * characters with `?`) before glyph layout.
     */
    fun draw(extraLines: List<String>) {
        val glyphPixelHeight = (16f * resolution.y / 600f).coerceIn(12f, 28f)
        val marginX = 12f
        val marginY = 12f
        val lineGap = glyphPixelHeight * 0.35f

        val lines = ArrayList<String>(extraLines.size + 1)
        currentOverlayStatus()?.let { lines += sanitizeHudText(it.uppercase()) }
        for (line in extraLines) lines += sanitizeHudText(line.uppercase())
        if (lines.isEmpty()) return

        var baselineY = marginY + glyphPixelHeight
        for (line in lines) {
            drawHudTextLine(message = line, startX = marginX, baselineY = baselineY, glyphPixelHeight = glyphPixelHeight)
            baselineY += glyphPixelHeight + lineGap
        }
    }

    fun cleanup() {
        hudGlyphShader.deleteProgram()
        GPU.deleteTextures(hudFontTextureId)
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

        if (count == 0) return
        val outlinePx = (glyphPixelHeight * 0.12f).coerceIn(1f, 3f)
        drawHudGlyphBatch(count, offsetPxX = -outlinePx, offsetPxY = 0f, colorR = 0f, colorG = 0f, colorB = 0f, alphaScale = 0.85f)
        drawHudGlyphBatch(count, offsetPxX = outlinePx, offsetPxY = 0f, colorR = 0f, colorG = 0f, colorB = 0f, alphaScale = 0.85f)
        drawHudGlyphBatch(count, offsetPxX = 0f, offsetPxY = -outlinePx, colorR = 0f, colorG = 0f, colorB = 0f, alphaScale = 0.85f)
        drawHudGlyphBatch(count, offsetPxX = 0f, offsetPxY = outlinePx, colorR = 0f, colorG = 0f, colorB = 0f, alphaScale = 0.85f)
        drawHudGlyphBatch(count, offsetPxX = 0f, offsetPxY = 0f, colorR = 0.95f, colorG = 0.97f, colorB = 1.0f, alphaScale = 1f)
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

    companion object {
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
