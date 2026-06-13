package org.emerge.render.torus.ui

import org.emerge.render.torus.GPU

/** Screen corner a panel is anchored to. */
enum class Anchor { TopLeft, TopRight, BottomLeft, BottomRight }

/**
 * A tiny **immediate-mode** in-game UI toolkit, shared across games. Rebuild the widget tree every
 * frame, then draw + route input:
 * ```
 * ui.frame { panel(Anchor.TopRight) { title("CELL"); keyValue("type", "Stem"); button("X", 0xCC3333FF) { ... } } }
 * ui.draw()                          // inside the GL frame, on top
 * if (ui.hitTest(px, py)) consumePress()
 * ```
 * The toolkit owns the shared [UiRectRenderer] + [UiTextRenderer] and the current resolution, so
 * widgets carry no GPU state. A panel is one auto-sized vertical stack anchored to a screen corner;
 * only [PanelBuilder.button] rows are interactive.
 */
class Ui {
    private val rectRenderer = UiRectRenderer()
    private val textRenderer = UiTextRenderer()
    private var resW = 1f
    private var resH = 1f

    // Per-frame accumulated draw commands (pixel coords) + interactive regions.
    private class RectCmd(val x: Float, val y: Float, val w: Float, val h: Float, val color: Long)
    private class TextCmd(val text: String, val x: Float, val y: Float, val h: Float, val color: Long, val centered: Boolean, val centerX: Float)
    private class ClickRegion(val x: Float, val y: Float, val w: Float, val h: Float, val onClick: () -> Unit)

    private val rects = ArrayList<RectCmd>()
    private val texts = ArrayList<TextCmd>()
    private val clicks = ArrayList<ClickRegion>()

    val resWidth: Float get() = resW
    val resHeight: Float get() = resH

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = widthPx.coerceAtLeast(1f)
        resH = heightPx.coerceAtLeast(1f)
    }

    /** Rebuilds this frame's widget tree (clears the previous frame's geometry). */
    fun frame(block: UiBuilder.() -> Unit) {
        rects.clear(); texts.clear(); clicks.clear()
        UiBuilder(this).block()
    }

    /** Draws this frame's widgets — all fills, then all text — self-contained blend. Call last (on top). */
    fun draw() {
        if (rects.isEmpty() && texts.isEmpty()) return
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        if (rects.isNotEmpty()) {
            val n = rects.size
            val centers = FloatArray(n * 2)
            val halfSizes = FloatArray(n * 2)
            val colors = FloatArray(n * 4)
            for (i in 0 until n) {
                val r = rects[i]
                centers[i * 2] = (r.x + r.w * 0.5f) / resW * 2f - 1f
                centers[i * 2 + 1] = 1f - (r.y + r.h * 0.5f) / resH * 2f
                halfSizes[i * 2] = r.w / resW
                halfSizes[i * 2 + 1] = r.h / resH
                packColor(r.color, colors, i * 4)
            }
            rectRenderer.drawInstanced(n, centers, halfSizes, colors)
        }
        for (t in texts) {
            val (cr, cg, cb) = rgb(t.color)
            if (t.centered) {
                textRenderer.drawCentered(t.text, t.centerX, t.y + t.h * 0.5f, t.h, cr, cg, cb, resW, resH)
            } else {
                textRenderer.drawLeft(t.text, t.x, t.y, t.h, cr, cg, cb, resW, resH)
            }
        }
        GPU.disableBlend()
    }

    /** Routes a pointer-down: invokes the topmost interactive region containing the point. */
    fun hitTest(px: Float, py: Float): Boolean {
        for (i in clicks.indices.reversed()) {
            val c = clicks[i]
            if (px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h) {
                c.onClick()
                return true
            }
        }
        return false
    }

    fun cleanup() {
        rectRenderer.deleteProgram()
        textRenderer.cleanup()
    }

    // ── internal emit API (called by the builders) ─────────────────────────────
    internal fun emitRect(x: Float, y: Float, w: Float, h: Float, color: Long) {
        rects.add(RectCmd(x, y, w, h, color))
    }

    internal fun emitTextLeft(text: String, x: Float, topY: Float, h: Float, color: Long) {
        texts.add(TextCmd(text, x, topY, h, color, centered = false, centerX = 0f))
    }

    internal fun emitTextCentered(text: String, centerX: Float, topY: Float, h: Float, color: Long) {
        texts.add(TextCmd(text, 0f, topY, h, color, centered = true, centerX = centerX))
    }

    internal fun emitClick(x: Float, y: Float, w: Float, h: Float, onClick: () -> Unit) {
        clicks.add(ClickRegion(x, y, w, h, onClick))
    }

    private fun rgb(rgba: Long): Triple<Float, Float, Float> = Triple(
        ((rgba ushr 24) and 0xFF).toFloat() / 255f,
        ((rgba ushr 16) and 0xFF).toFloat() / 255f,
        ((rgba ushr 8) and 0xFF).toFloat() / 255f,
    )

    private fun packColor(rgba: Long, out: FloatArray, base: Int) {
        out[base] = ((rgba ushr 24) and 0xFF).toFloat() / 255f
        out[base + 1] = ((rgba ushr 16) and 0xFF).toFloat() / 255f
        out[base + 2] = ((rgba ushr 8) and 0xFF).toFloat() / 255f
        out[base + 3] = (rgba and 0xFF).toFloat() / 255f
    }
}

/** Frame-scoped builder: add panels. */
class UiBuilder internal constructor(private val ui: Ui) {
    /** An auto-sized panel anchored to a screen [anchor] corner, [margin] px from the edges. */
    fun panel(
        anchor: Anchor,
        margin: Float = 12f,
        padding: Float = 8f,
        background: Long = 0x000000C0,
        rowHeight: Float = 18f,
        block: PanelBuilder.() -> Unit,
    ) {
        val pb = PanelBuilder(rowHeight).apply(block)
        if (pb.items.isEmpty()) return
        val textH = rowHeight * 0.68f
        val contentW = pb.items.maxOf { it.measureWidth(textH) }
        val contentH = pb.items.sumOf { it.height.toDouble() }.toFloat()
        val w = padding * 2 + contentW
        val h = padding * 2 + contentH
        val x = when (anchor) {
            Anchor.TopLeft, Anchor.BottomLeft -> margin
            Anchor.TopRight, Anchor.BottomRight -> ui.resWidth - margin - w
        }
        val y = when (anchor) {
            Anchor.TopLeft, Anchor.TopRight -> margin
            Anchor.BottomLeft, Anchor.BottomRight -> ui.resHeight - margin - h
        }
        ui.emitRect(x, y, w, h, background)
        var rowY = y + padding
        for (item in pb.items) {
            item.emit(ui, x + padding, rowY, contentW, textH)
            rowY += item.height
        }
    }
}

/** Collects a panel's vertical stack of items, then [UiBuilder.panel] lays them out. */
class PanelBuilder internal constructor(private val rowHeight: Float) {
    internal val items = ArrayList<Item>()

    fun title(text: String, color: Long = 0xFFFFFFFFL) = items.add(TextItem(text, color, rowHeight))
    fun row(text: String, color: Long = 0xC8C8C8FFL) = items.add(TextItem(text, color, rowHeight))
    fun keyValue(key: String, value: String, keyColor: Long = 0x9A9A9AFFL, valueColor: Long = 0xFFFFFFFFL) =
        items.add(KeyValueItem(key, value, keyColor, valueColor, rowHeight))
    fun button(label: String, color: Long, onClick: () -> Unit) = items.add(ButtonItem(label, color, rowHeight, onClick))
    fun gap(height: Float = 6f) = items.add(GapItem(height))

    internal interface Item {
        val height: Float
        fun measureWidth(textH: Float): Float
        fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float)
    }

    private class TextItem(val text: String, val color: Long, override val height: Float) : Item {
        override fun measureWidth(textH: Float) = UiTextRenderer.measureWidthPx(text, textH)
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) =
            ui.emitTextLeft(text, x, topY + (height - textH) * 0.5f, textH, color)
    }

    private class KeyValueItem(val key: String, val value: String, val keyColor: Long, val valueColor: Long, override val height: Float) : Item {
        override fun measureWidth(textH: Float): Float =
            UiTextRenderer.measureWidthPx(key, textH) + textH * 2f + UiTextRenderer.measureWidthPx(value, textH)
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            ui.emitTextLeft(key, x, ty, textH, keyColor)
            ui.emitTextLeft(value, x + contentW - UiTextRenderer.measureWidthPx(value, textH), ty, textH, valueColor)
        }
    }

    private class ButtonItem(val label: String, val color: Long, override val height: Float, val onClick: () -> Unit) : Item {
        override fun measureWidth(textH: Float) = UiTextRenderer.measureWidthPx(label, textH) + textH * 2f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val inset = 1f
            ui.emitRect(x, topY + inset, contentW, height - inset * 2f, color)
            ui.emitTextCentered(label, x + contentW * 0.5f, topY + (height - textH) * 0.5f, textH, contrast(color))
            ui.emitClick(x, topY + inset, contentW, height - inset * 2f, onClick)
        }
        private fun contrast(rgba: Long): Long {
            val r = ((rgba ushr 24) and 0xFF).toFloat() / 255f
            val g = ((rgba ushr 16) and 0xFF).toFloat() / 255f
            val b = ((rgba ushr 8) and 0xFF).toFloat() / 255f
            return if (0.299f * r + 0.587f * g + 0.114f * b < 0.5f) 0xFFFFFFFFL else 0x000000FFL
        }
    }

    private class GapItem(override val height: Float) : Item {
        override fun measureWidth(textH: Float) = 0f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) = Unit
    }
}
