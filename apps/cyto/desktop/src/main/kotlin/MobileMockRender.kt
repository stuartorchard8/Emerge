package org.emerge.desktop

import org.emerge.render.torus.ui.UiRectRenderer
import org.emerge.render.torus.ui.UiTextRenderer
import org.emerge.render.torus.GPU
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **THROWAWAY DESIGN MOCK** — not part of the game. Renders the proposed L3 "gene detail" screen from
 * `apps/cyto/UI_REDESIGN.md` at a phone framebuffer, to answer one question before any real work starts:
 * *does the sentence model actually read?*
 *
 * It hand-places rects/text using the **real** [UiRectRenderer] + [UiTextRenderer], so glyph widths,
 * the bitmap font and the colours are exactly what the game would draw — only the layout is faked.
 * Delete this file once the real primitives exist.
 *
 * `./gradlew :apps:cyto:desktop:mobileMock` → `agent-out/mock-l3-{typical,worst}.png`
 */
object MobileMockRender {

    // Reference device: 1080x2400 @ density 2.625 (a 411x914dp screen).
    private const val W = 1080
    private const val H = 2400
    private const val DENSITY = 2.625f

    private fun dp(v: Float) = v * DENSITY

    // Type scale + metrics, in dp/sp — the numbers UI_REDESIGN.md argues for.
    private val MARGIN = dp(16f)
    private val ROW = dp(48f)          // minimum touch target
    private val GAP = dp(8f)
    private val SECTION_GAP = dp(20f)
    private val TEXT_BODY = dp(16f)
    private val TEXT_LABEL = dp(13f)
    private val TITLE_BAR = dp(56f)
    private val STATUS_BAR = dp(24f)
    private val BOTTOM_BAR = dp(72f)

    // Palette — dark surfaces, the game's accent blues/greens.
    private const val BG = 0x0B0E14FFL
    private const val SURFACE = 0x151A24FFL
    private const val BAR = 0x1B2230FFL
    private const val CHIP = 0x2A3550FFL
    private const val CHIP_STRONG = 0x35507AFFL
    private const val SEG_ON = 0x3A6EA5FFL
    private const val SEG_OFF = 0x252C3AFFL
    private const val GHOST = 0x1E2634FFL
    private const val LABEL = 0x7A8699FFL
    private const val VALUE = 0xFFFFFFFFL
    private const val ACCENT = 0xAACCFFFFL
    private const val GREEN = 0x33AA33FFL
    private const val GREY = 0x50586AFFL

    private class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val color: Long)
    private class Txt(val s: String, val x: Float, val y: Float, val h: Float, val color: Long, val centered: Boolean)

    // Two layers: content scrolls (and is scissor-clipped to the viewport); chrome never does.
    private val rects = ArrayList<Rect>()
    private val texts = ArrayList<Txt>()
    private val chromeRects = ArrayList<Rect>()
    private val chromeTexts = ArrayList<Txt>()
    private var toChrome = false

    private fun rect(x: Float, y: Float, w: Float, h: Float, c: Long) {
        (if (toChrome) chromeRects else rects).add(Rect(x, y, w, h, c))
    }
    private fun textLeft(s: String, x: Float, topY: Float, h: Float, c: Long) {
        (if (toChrome) chromeTexts else texts).add(Txt(s, x, topY, h, c, false))
    }
    private fun textCenter(s: String, cx: Float, topY: Float, h: Float, c: Long) {
        (if (toChrome) chromeTexts else texts).add(Txt(s, cx, topY, h, c, true))
    }

    /** Right-align text at [rightX] — the bitmap font is monospace, so width is exact. */
    private fun textRight(s: String, rightX: Float, topY: Float, h: Float, c: Long) =
        textLeft(s, rightX - UiTextRenderer.measureWidthPx(s, h), topY, h, c)

    /** A tappable value: filled rect, label centred, a "V" affordance on the right (the game's picker cue). */
    private fun chip(x: Float, y: Float, w: Float, label: String, color: Long = CHIP) {
        rect(x, y, w, ROW, color)
        textCenter(label, x + w * 0.5f, y + (ROW - TEXT_BODY) * 0.5f, TEXT_BODY, VALUE)
        textLeft("V", x + w - dp(14f), y + (ROW - TEXT_LABEL) * 0.5f, TEXT_LABEL, ACCENT)
    }

    /** A section heading ("WHEN" / "DO" / "POWERED BY"). Returns the y below it. */
    private fun section(label: String, y: Float): Float {
        textLeft(label, MARGIN, y, TEXT_LABEL, LABEL)
        return y + TEXT_LABEL + GAP
    }

    /** `LABEL              [ value V ]` — a right-aligned chip with a left caption. */
    private fun labelledChip(label: String, value: String, y: Float): Float {
        textLeft(label, MARGIN, y + (ROW - TEXT_LABEL) * 0.5f, TEXT_LABEL, LABEL)
        val w = dp(190f)
        chip(W - MARGIN - w, y, w, value)
        return y + ROW + GAP
    }

    /** `LABEL              [ ON | off ]` — an inline 2-way segmented control. Segments size to the widest
     *  label (monospace, so exact) — "DAUGHTER" doesn't fit a fixed 95dp segment. */
    private fun segmented(label: String, a: String, b: String, aOn: Boolean, y: Float): Float {
        textLeft(label, MARGIN, y + (ROW - TEXT_LABEL) * 0.5f, TEXT_LABEL, LABEL)
        val segW = maxOf(
            dp(95f),
            maxOf(UiTextRenderer.measureWidthPx(a, TEXT_BODY), UiTextRenderer.measureWidthPx(b, TEXT_BODY)) + dp(16f),
        )
        val x = W - MARGIN - segW * 2 - 2f
        rect(x, y, segW, ROW, if (aOn) SEG_ON else SEG_OFF)
        textCenter(a, x + segW * 0.5f, y + (ROW - TEXT_BODY) * 0.5f, TEXT_BODY, if (aOn) VALUE else LABEL)
        rect(x + segW + 2f, y, segW, ROW, if (!aOn) SEG_ON else SEG_OFF)
        textCenter(b, x + segW * 1.5f + 2f, y + (ROW - TEXT_BODY) * 0.5f, TEXT_BODY, if (!aOn) VALUE else LABEL)
        return y + ROW + GAP
    }

    /** One AND-clause as a single row of three chips: `[lhs] [cmp] [rhs]`. The core claim of the redesign. */
    private fun clauseRow(lhs: String, cmp: String, rhs: String, y: Float): Float {
        val content = W - MARGIN * 2
        val cmpW = dp(56f)
        val sideW = (content - cmpW - GAP * 2) * 0.5f
        chip(MARGIN, y, sideW, lhs)
        rect(MARGIN + sideW + GAP, y, cmpW, ROW, CHIP_STRONG)
        textCenter(cmp, MARGIN + sideW + GAP + cmpW * 0.5f, y + (ROW - TEXT_BODY) * 0.5f, TEXT_BODY, VALUE)
        chip(MARGIN + sideW + GAP + cmpW + GAP, y, sideW, rhs)
        return y + ROW + GAP
    }

    /** Builds the whole L3 screen. [worst] = 4 clauses + oriented asymmetric Mitosis (the densest gene). */
    private fun build(worst: Boolean) {
        rects.clear(); texts.clear(); chromeRects.clear(); chromeTexts.clear()

        // The surface is the clear colour (chrome draws after content, so a full-screen rect here would
        // cover it). Chrome bars are opaque and at the edges — occluding scrolled content is the point.
        toChrome = true
        rect(0f, 0f, W.toFloat(), STATUS_BAR, BG)                       // status bar (inset-safe)

        // Title bar: back, title, overflow ("..." — the font has no vertical-ellipsis glyph).
        rect(0f, STATUS_BAR, W.toFloat(), TITLE_BAR, BAR)
        val titleMid = STATUS_BAR + (TITLE_BAR - TEXT_BODY) * 0.5f
        textLeft("<", MARGIN, titleMid, TEXT_BODY, ACCENT)
        textLeft("GENE 1 · DIVISION", MARGIN + dp(28f), titleMid, TEXT_BODY, VALUE)
        textRight("...", W - MARGIN, titleMid, TEXT_BODY, ACCENT)
        toChrome = false

        var y = STATUS_BAR + TITLE_BAR + MARGIN

        // ── WHEN: the condition, one row per AND-clause ──
        y = section("WHEN", y)
        y = clauseRow("BIO", ">", "2000", y)
        if (worst) {
            y = clauseRow("CONC RG", "<", "500", y)
            y = clauseRow("TOUCH", "<", "3", y)
            y = clauseRow("CHEM AB", ">", "120", y)
        }
        rect(MARGIN, y, W - MARGIN * 2, ROW, GHOST)
        textCenter(if (worst) "+ AND CLAUSE  (4/4)" else "+ AND CLAUSE", W * 0.5f, y + (ROW - TEXT_BODY) * 0.5f, TEXT_BODY, LABEL)
        y += ROW + SECTION_GAP

        // ── DO: the action, then only the fields that action actually has ──
        y = section("DO", y)
        chip(MARGIN, y, W - MARGIN * 2, "DIVIDE (MITOSIS)", CHIP_STRONG)
        y += ROW + GAP
        y = labelledChip("MORPHOGEN", if (worst) "RG" else "(NONE)", y)
        if (worst) {
            y = segmented("KEEP", "MOTHER", "DAUGHTER", false, y)
            y = labelledChip("AXIS", "AB", y)
            y = segmented("ORIENT", "ALONG", "ACROSS", true, y)
        }
        y = segmented("SEVER", "YES", "NO", true, y)
        y += SECTION_GAP - GAP

        // ── POWERED BY: the energy source ──
        y = section("POWERED BY", y)
        chip(MARGIN, y, W - MARGIN * 2, "BREAK RG")
        y += ROW + SECTION_GAP

        y = labelledChip("GROUP", "DIVISION", y)

        // Bottom bar: only the two safe actions. DUP/DELETE live in the "..." overflow, away from DONE.
        toChrome = true
        val by = H - BOTTOM_BAR
        rect(0f, by, W.toFloat(), BOTTOM_BAR, BAR)
        val bw = (W - MARGIN * 3) * 0.5f
        val byy = by + (BOTTOM_BAR - ROW) * 0.5f
        rect(MARGIN, byy, bw, ROW, GREY)
        textCenter("CANCEL", MARGIN + bw * 0.5f, byy + (ROW - TEXT_BODY) * 0.5f, TEXT_BODY, VALUE)
        rect(MARGIN * 2 + bw, byy, bw, ROW, GREEN)
        textCenter("DONE", MARGIN * 2 + bw * 1.5f, byy + (ROW - TEXT_BODY) * 0.5f, TEXT_BODY, VALUE)
        toChrome = false
    }

    /** Draw one layer's batched rects + text. */
    private fun drawLayer(rectR: UiRectRenderer, textR: UiTextRenderer, rs: List<Rect>, ts: List<Txt>) {
        val n = rs.size
        if (n > 0) {
            val centers = FloatArray(n * 2); val halves = FloatArray(n * 2); val cols = FloatArray(n * 4)
            for (i in 0 until n) {
                val r = rs[i]
                centers[i * 2] = (r.x + r.w * 0.5f) / W * 2f - 1f
                centers[i * 2 + 1] = 1f - (r.y + r.h * 0.5f) / H * 2f
                halves[i * 2] = r.w / W
                halves[i * 2 + 1] = r.h / H
                cols[i * 4] = ((r.color ushr 24) and 0xFF) / 255f
                cols[i * 4 + 1] = ((r.color ushr 16) and 0xFF) / 255f
                cols[i * 4 + 2] = ((r.color ushr 8) and 0xFF) / 255f
                cols[i * 4 + 3] = (r.color and 0xFF) / 255f
            }
            rectR.drawInstanced(n, centers, halves, cols)
        }
        for (t in ts) {
            val r = ((t.color ushr 24) and 0xFF) / 255f
            val g = ((t.color ushr 16) and 0xFF) / 255f
            val b = ((t.color ushr 8) and 0xFF) / 255f
            if (t.centered) textR.drawCentered(t.s, t.x, t.y + t.h * 0.5f, t.h, r, g, b, W.toFloat(), H.toFloat())
            else textR.drawLeft(t.s, t.x, t.y, t.h, r, g, b, W.toFloat(), H.toFloat())
        }
    }

    fun run(outDir: File) {
        outDir.mkdirs()
        check(glfwInit()) { "GLFW init failed" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)
        val win = glfwCreateWindow(W, H, "mock", NULL, NULL)
        check(win != NULL) { "no GL context" }
        glfwMakeContextCurrent(win)
        org.lwjgl.opengl.GL.createCapabilities()

        val rectR = UiRectRenderer()
        val textR = UiTextRenderer()

        for ((name, worst) in listOf("mock-l3-typical" to false, "mock-l3-worst" to true)) {
            build(worst)
            for (t in texts + chromeTexts) require(t.s.all { UiTextRenderer.supports(it) }) { "unsupported glyph in '${t.s}'" }

            glViewport(0, 0, W, H)
            glClearColor(0x15 / 255f, 0x1A / 255f, 0x24 / 255f, 1f)   // SURFACE
            glClear(GL_COLOR_BUFFER_BIT)
            GPU.enableBlend()
            GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()

            // Content is clipped to the scroll viewport with a real scissor — the same mechanism the
            // proposed scroll container needs, so this also de-risks that primitive. GL is bottom-up.
            val viewTop = STATUS_BAR + TITLE_BAR
            glEnable(GL_SCISSOR_TEST)
            glScissor(0, BOTTOM_BAR.toInt(), W, (H - BOTTOM_BAR - viewTop).toInt())
            drawLayer(rectR, textR, rects, texts)
            glDisable(GL_SCISSOR_TEST)
            drawLayer(rectR, textR, chromeRects, chromeTexts)

            GPU.disableBlend()
            glFinish()

            val buf = BufferUtils.createByteBuffer(W * H * 4)
            glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until H) {
                val src = H - 1 - y
                for (x in 0 until W) {
                    val i = (src * W + x) * 4
                    img.setRGB(x, y, ((buf.get(i).toInt() and 0xFF) shl 16) or
                        ((buf.get(i + 1).toInt() and 0xFF) shl 8) or (buf.get(i + 2).toInt() and 0xFF))
                }
            }
            val out = File(outDir, "$name.png")
            ImageIO.write(img, "png", out)
            println("[mock] $name -> ${out.absolutePath}")
        }
        rectR.deleteProgram(); textR.cleanup()
        glfwDestroyWindow(win); glfwTerminate()
    }
}

fun main(args: Array<String>) = MobileMockRender.run(File(args.getOrElse(0) { "agent-out" }))
