package org.emerge.render.torus.ui

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.HudGlyphShaderSources
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Minimal bitmap-font text renderer for the in-game UI toolkit ([Ui]) — a procedural 5×7 glyph atlas +
 * instanced NDC quads. Shared across games (moved out of cyto). Supports centered ([drawCentered]) and
 * left-aligned, top-anchored ([drawLeft]) multi-line (`\n`) strings in pixel coordinates; the companion
 * [measureWidthPx]/[measureHeightPx] let panels auto-size before drawing.
 */
class UiTextRenderer {
    private val program = ShaderFactory.createProgram(
        HudGlyphShaderSources.vertex(),
        HudGlyphShaderSources.fragment(),
    )
    private val uTexture = GPU.getUniformLocation(program, "uHudTexture")
    private val uColor = GPU.getUniformLocation(program, "uColor")

    private val vao = GPU.genAndBindVertexArrays()
    private val quadVbo = GPU.genBuffers()
    private val centerVbo = GPU.genBuffers()
    private val halfSizeVbo = GPU.genBuffers()
    private val uvRectVbo = GPU.genBuffers()

    private val centers = FloatArray(MAX_GLYPHS * 2)
    private val halfSizes = FloatArray(MAX_GLYPHS * 2)
    private val uvRects = FloatArray(MAX_GLYPHS * 4)
    private val centerBuf = GpuFloatBuffer(MAX_GLYPHS * 2)
    private val halfSizeBuf = GpuFloatBuffer(MAX_GLYPHS * 2)
    private val uvRectBuf = GpuFloatBuffer(MAX_GLYPHS * 4)

    private val fontTextureId = createFontTexture()

    init {
        uploadQuad()
        initFloatBuffer(centerVbo, 1, 2)
        initFloatBuffer(halfSizeVbo, 2, 2)
        initFloatBuffer(uvRectVbo, 3, 4)
    }

    /** Draws [text] (multi-line, `\n`) centred at ([centerXpx], [centerYpx]). */
    fun drawCentered(
        text: String, centerXpx: Float, centerYpx: Float, pixelHeight: Float,
        r: Float, g: Float, b: Float, resW: Float, resH: Float,
    ) {
        val lines = text.uppercase().split('\n')
        val glyphW = pixelHeight * GLYPH_ASPECT
        val lineGap = pixelHeight * LINE_GAP_RATIO
        val totalH = lines.size * pixelHeight + (lines.size - 1) * lineGap
        val count = layoutGlyphs(lines, glyphW, lineGap, pixelHeight, centerYpx - totalH * 0.5f, resW, resH) { lineW ->
            centerXpx - lineW * 0.5f
        }
        flush(count, r, g, b)
    }

    /** Draws [text] (multi-line, `\n`) left-aligned with its top edge at ([leftXpx], [topYpx]). */
    fun drawLeft(
        text: String, leftXpx: Float, topYpx: Float, pixelHeight: Float,
        r: Float, g: Float, b: Float, resW: Float, resH: Float,
    ) {
        val lines = text.uppercase().split('\n')
        val glyphW = pixelHeight * GLYPH_ASPECT
        val lineGap = pixelHeight * LINE_GAP_RATIO
        val count = layoutGlyphs(lines, glyphW, lineGap, pixelHeight, topYpx, resW, resH) { leftXpx }
        flush(count, r, g, b)
    }

    /** Populates the glyph arrays; [lineStartX] gives the left pixel of a line from its pixel width.
     *  Returns the glyph count. [topYpx] is the top of the first line.
     *
     *  **Every glyph quad is snapped to whole pixels**, and this is load-bearing, not a nicety. The atlas is
     *  sampled `GL_NEAREST` with the UV rect running edge-to-edge along the cell's texel boundaries, so output
     *  pixel `i` of a `P`-pixel-tall quad reads texel `floor(t * 8)` for `t = (i + 0.5) / P`. With integer
     *  edges `t` is strictly inside `(0, 1)`, so the texel is always inside the cell — provably, on any GPU.
     *  Let the quad land on a fractional pixel (and it would: sizes are dp x scale, and centred panels sit at
     *  `(resW - w) * 0.5`) and `t` can hit 0 or pass 1, sampling exactly ON a cell boundary, where rounding
     *  decides whether you get this cell or its neighbour. Read one texel low and the glyph loses its top row
     *  and picks up the next cell's row 0 — the top row of the glyph BELOW it in the atlas. The artifact is
     *  directional (each cell has a blank gutter below its 5x7 glyph but none above), so drifting up is
     *  invisible while drifting down is glaringly wrong, and whether it bites at all depends on the GPU's
     *  rounding — it showed on a playtester's machine and not on Stu's.
     *
     *  Positions still ADVANCE in float (the cursor and line top are unrounded) so that snapping can't
     *  accumulate drift across a line or push text out of its panel; only the emitted quad is rounded. */
    private inline fun layoutGlyphs(
        lines: List<String>, glyphW: Float, lineGap: Float, pixelHeight: Float, topYpx: Float,
        resW: Float, resH: Float, lineStartX: (lineW: Float) -> Float,
    ): Int {
        var count = 0
        val uvW = 1f / FONT_COLS
        val uvH = 1f / FONT_ROWS
        var lineTop = topYpx
        for (line in lines) {
            var cursorX = lineStartX(line.length * glyphW)
            // Snap the line's pixel band once, so every glyph on it shares identical top/bottom edges.
            val yTop = snap(lineTop)
            val hPx = maxOf(1f, snap(lineTop + pixelHeight) - yTop)
            for (ch in line) {
                if (count >= MAX_GLYPHS) break
                val idx = CHAR_TO_INDEX[ch] ?: CHAR_TO_INDEX['?'] ?: 0
                val col = idx % FONT_COLS
                val row = idx / FONT_COLS
                val xL = snap(cursorX)
                val wPx = maxOf(1f, snap(cursorX + glyphW) - xL)
                centers[count * 2] = (xL + wPx * 0.5f) / resW * 2f - 1f
                centers[count * 2 + 1] = 1f - (yTop + hPx * 0.5f) / resH * 2f
                halfSizes[count * 2] = wPx / resW
                halfSizes[count * 2 + 1] = hPx / resH
                uvRects[count * 4] = col * uvW
                uvRects[count * 4 + 1] = row * uvH
                uvRects[count * 4 + 2] = uvW
                uvRects[count * 4 + 3] = uvH
                count++
                cursorX += glyphW
            }
            lineTop += pixelHeight + lineGap
        }
        return count
    }

    /** Round a pixel coordinate to the pixel grid (see [layoutGlyphs] — glyph quads must have integer edges).
     *  `kotlin.math.round` halves-to-even; plain `floor(x + 0.5)` is what we want and is platform-identical. */
    private fun snap(px: Float): Float = kotlin.math.floor(px + 0.5f)

    private fun flush(count: Int, r: Float, g: Float, b: Float) {
        if (count == 0) return
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        GPU.activeTexture(TEXTURE_UNIT)
        GPU.bindTexture2D(fontTextureId)
        GPU.putUniform1i(uTexture, TEXTURE_UNIT)
        GPU.putUniform4fv(uColor, floatArrayOf(r, g, b, 1f), 1)
        bind(centerVbo, centerBuf, centers, count * 2)
        bind(halfSizeVbo, halfSizeBuf, halfSizes, count * 2)
        bind(uvRectVbo, uvRectBuf, uvRects, count * 4)
        GPU.drawTrianglesInstanced(0, QUAD_VERTEX_COUNT, count)
    }

    fun cleanup() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(quadVbo)
        GPU.deleteBuffers(centerVbo)
        GPU.deleteBuffers(halfSizeVbo)
        GPU.deleteBuffers(uvRectVbo)
        GPU.deleteTextures(fontTextureId)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    private fun uploadQuad() {
        val verts = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f)
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, quadVbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun initFloatBuffer(vbo: Int, attribute: Int, sizeX: Int) {
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(attribute)
        GPU.putVertexAttribPointer(attribute, sizeX, GPU.FLOAT, false, sizeX * 4, 0)
        GPU.vertexAttribDivisor(attribute, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun bind(vbo: Int, buffer: GpuFloatBuffer, array: FloatArray, count: Int) {
        buffer.clear().put(array, 0, count).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    companion object {
        const val MAX_GLYPHS = 256
        const val GLYPH_ASPECT = 0.75f       // glyph width / pixel height
        const val LINE_GAP_RATIO = 0.35f     // gap between lines / pixel height
        private const val TEXTURE_UNIT = 2
        private const val QUAD_VERTEX_COUNT = 4
        private const val FONT_COLS = 8
        private const val FONT_ROWS = 8

        /** Whether [ch] has a glyph (text is uppercase-folded before drawing, so lowercase counts as its
         *  uppercase). Unsupported characters render as `?` — callers/authors can assert against this to keep
         *  copy within the bitmap font. */
        fun supports(ch: Char): Boolean = CHAR_TO_INDEX.containsKey(ch.uppercaseChar())

        /** Widest line's pixel width (for panel auto-sizing). */
        fun measureWidthPx(text: String, pixelHeight: Float): Float {
            val glyphW = pixelHeight * GLYPH_ASPECT
            var longest = 0
            for (line in text.split('\n')) if (line.length > longest) longest = line.length
            return longest * glyphW
        }

        /** Pixel height of [lineCount] stacked lines. */
        fun measureHeightPx(lineCount: Int, pixelHeight: Float): Float =
            if (lineCount <= 0) 0f else lineCount * pixelHeight + (lineCount - 1) * (pixelHeight * LINE_GAP_RATIO)

        private val GLYPHS = listOf(
            ' ', 'A', 'B', 'C', 'D', 'E', 'F', 'G',
            'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
            'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W',
            'X', 'Y', 'Z', '0', '1', '2', '3', '4',
            '5', '6', '7', '8', '9', '(', ')', ':',
            '.', ',', '-', '_', '/', '\\', '+', '=',
            '<', '>', '*', '&', '[', ']', '%', '?',
            '→', '·', '\'',   // → (asym-divide), · (FormBond junction), ' (apostrophe/contractions)
        )
        private val CHAR_TO_INDEX: Map<Char, Int> = GLYPHS.withIndex().associate { it.value to it.index }
        private val PATTERN: Map<Char, Array<String>> = mapOf(
            ' ' to arrayOf("00000", "00000", "00000", "00000", "00000", "00000", "00000"),
            'A' to arrayOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
            'B' to arrayOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
            'C' to arrayOf("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
            'D' to arrayOf("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
            'E' to arrayOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
            'F' to arrayOf("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
            'G' to arrayOf("01111", "10000", "10000", "10111", "10001", "10001", "01111"),
            'H' to arrayOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
            'I' to arrayOf("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
            'J' to arrayOf("00111", "00010", "00010", "00010", "10010", "10010", "01100"),
            'K' to arrayOf("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
            'L' to arrayOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
            'M' to arrayOf("10001", "11011", "10101", "10001", "10001", "10001", "10001"),
            'N' to arrayOf("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
            'O' to arrayOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
            'P' to arrayOf("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
            'Q' to arrayOf("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
            'R' to arrayOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
            'S' to arrayOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
            'T' to arrayOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
            'U' to arrayOf("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
            'V' to arrayOf("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
            'W' to arrayOf("10001", "10001", "10001", "10001", "10101", "11011", "10001"),
            'X' to arrayOf("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
            'Y' to arrayOf("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
            'Z' to arrayOf("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
            '0' to arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
            '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to arrayOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
            '3' to arrayOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            '5' to arrayOf("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
            '6' to arrayOf("01110", "10000", "10000", "11110", "10001", "10001", "01110"),
            '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00001", "01110"),
            '(' to arrayOf("00010", "00100", "01000", "01000", "01000", "00100", "00010"),
            ')' to arrayOf("01000", "00100", "00010", "00010", "00010", "00100", "01000"),
            ':' to arrayOf("00000", "00100", "00100", "00000", "00100", "00100", "00000"),
            '.' to arrayOf("00000", "00000", "00000", "00000", "00000", "00100", "00100"),
            ',' to arrayOf("00000", "00000", "00000", "00000", "00100", "00100", "01000"),
            '-' to arrayOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
            '_' to arrayOf("00000", "00000", "00000", "00000", "00000", "00000", "11111"),
            '/' to arrayOf("00001", "00010", "00100", "01000", "10000", "00000", "00000"),
            '\\' to arrayOf("10000", "01000", "00100", "00010", "00001", "00000", "00000"),
            '+' to arrayOf("00000", "00100", "00100", "11111", "00100", "00100", "00000"),
            '=' to arrayOf("00000", "11111", "00000", "11111", "00000", "00000", "00000"),
            '<' to arrayOf("00010", "00100", "01000", "10000", "01000", "00100", "00010"),
            '>' to arrayOf("01000", "00100", "00010", "00001", "00010", "00100", "01000"),
            '*' to arrayOf("00000", "00100", "10101", "01110", "10101", "00100", "00000"),
            '&' to arrayOf("01100", "10010", "10010", "01100", "10101", "10010", "01101"),
            '[' to arrayOf("01110", "01000", "01000", "01000", "01000", "01000", "01110"),
            ']' to arrayOf("01110", "00010", "00010", "00010", "00010", "00010", "01110"),
            '%' to arrayOf("11000", "11001", "00010", "00100", "01000", "10011", "00011"),
            '?' to arrayOf("01110", "10001", "00001", "00010", "00100", "00000", "00100"),
            '→' to arrayOf("00000", "00100", "00010", "11111", "00010", "00100", "00000"),
            '·' to arrayOf("00000", "00000", "00000", "00100", "00000", "00000", "00000"),
            '\'' to arrayOf("00100", "00100", "01000", "00000", "00000", "00000", "00000"),
        )

        private fun createFontTexture(): Int {
            val glyphW = 6
            val glyphH = 8
            val texW = FONT_COLS * glyphW
            val texH = FONT_ROWS * glyphH
            val pixels = ByteArray(texW * texH * 4)
            for ((index, ch) in GLYPHS.withIndex()) {
                val pattern = PATTERN[ch] ?: PATTERN[' ']!!
                val x0 = (index % FONT_COLS) * glyphW
                val y0 = (index / FONT_COLS) * glyphH
                for (y in 0 until 7) {
                    val rowBits = pattern[y]
                    for (x in 0 until 5) {
                        if (rowBits[x] != '1') continue
                        val base = ((y0 + y) * texW + (x0 + x)) * 4
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
