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
 * widgets carry no GPU state.
 *
 * Panels are auto-sized vertical stacks; **multiple panels at the same [Anchor] stack** (a second
 * TopRight panel sits below the first). Beyond plain rows it offers composite rows — [PanelBuilder.picker]
 * (a click-to-expand dropdown drawn in an **overlay** layer on top of everything), [PanelBuilder.stepper]
 * (**hold-to-repeat** ± with accelerating step), and [PanelBuilder.actionRow] (a row of small buttons).
 * Hold-to-repeat needs the host to feed pointer state each frame: [hitTest] on press, [updateHold] while
 * held, [releaseHold] on release.
 */
class Ui {
    private val rectRenderer = UiRectRenderer()
    private val textRenderer = UiTextRenderer()
    private var resW = 1f
    private var resH = 1f

    // Per-frame accumulated draw commands (pixel coords) + interactive regions. A separate *overlay* layer
    // is drawn (and hit-tested) on top of the base layer — used for dropdown popups.
    private class RectCmd(val x: Float, val y: Float, val w: Float, val h: Float, val color: Long)
    private class TextCmd(val text: String, val x: Float, val y: Float, val h: Float, val color: Long, val centered: Boolean, val centerX: Float)
    private class ClickRegion(
        val x: Float, val y: Float, val w: Float, val h: Float, val onClick: () -> Unit,
        /** ±1 for a hold-to-repeat stepper button (0 = plain click). */
        val holdSign: Int = 0,
        /** Called with the (accelerating) signed step on each repeat tick; null for plain buttons. */
        val onStep: ((Int) -> Unit)? = null,
    )

    private val rects = ArrayList<RectCmd>()
    private val texts = ArrayList<TextCmd>()
    private val clicks = ArrayList<ClickRegion>()
    private val overlayRects = ArrayList<RectCmd>()
    private val overlayTexts = ArrayList<TextCmd>()
    private val overlayClicks = ArrayList<ClickRegion>()
    /** Per-anchor running offset (px from the anchored edge) so panels at one corner stack. */
    private val anchorCursor = HashMap<Anchor, Float>()
    /** Per-anchor horizontal base (px from the anchored edge to the current column's near side) and the
     *  current column's extent (max inset+width). A panel with `newColumn` starts a fresh column past the
     *  previous one (to the left for right anchors), so two stacks can sit side-by-side. */
    private val anchorInset = HashMap<Anchor, Float>()
    private val anchorColumnExtent = HashMap<Anchor, Float>()

    // Hold-to-repeat state (persists across frames while a stepper button is held).
    private var heldRegion: ClickRegion? = null
    private var heldSeconds = 0f
    private var repeatTimer = 0f

    val resWidth: Float get() = resW
    val resHeight: Float get() = resH

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = widthPx.coerceAtLeast(1f)
        resH = heightPx.coerceAtLeast(1f)
    }

    /** Rebuilds this frame's widget tree (clears the previous frame's geometry; hold state persists). */
    fun frame(block: UiBuilder.() -> Unit) {
        rects.clear(); texts.clear(); clicks.clear()
        overlayRects.clear(); overlayTexts.clear(); overlayClicks.clear()
        anchorCursor.clear(); anchorInset.clear(); anchorColumnExtent.clear()
        UiBuilder(this).block()
    }

    /** Draws this frame's widgets — base layer then overlay (dropdowns) on top. Call last (on top). */
    fun draw() {
        drawLayer(rects, texts)
        drawLayer(overlayRects, overlayTexts)
    }

    private fun drawLayer(rs: List<RectCmd>, ts: List<TextCmd>) {
        if (rs.isEmpty() && ts.isEmpty()) return
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        if (rs.isNotEmpty()) {
            val n = rs.size
            val centers = FloatArray(n * 2)
            val halfSizes = FloatArray(n * 2)
            val colors = FloatArray(n * 4)
            for (i in 0 until n) {
                val r = rs[i]
                centers[i * 2] = (r.x + r.w * 0.5f) / resW * 2f - 1f
                centers[i * 2 + 1] = 1f - (r.y + r.h * 0.5f) / resH * 2f
                halfSizes[i * 2] = r.w / resW
                halfSizes[i * 2 + 1] = r.h / resH
                packColor(r.color, colors, i * 4)
            }
            rectRenderer.drawInstanced(n, centers, halfSizes, colors)
        }
        for (t in ts) {
            val (cr, cg, cb) = rgb(t.color)
            if (t.centered) {
                textRenderer.drawCentered(t.text, t.centerX, t.y + t.h * 0.5f, t.h, cr, cg, cb, resW, resH)
            } else {
                textRenderer.drawLeft(t.text, t.x, t.y, t.h, cr, cg, cb, resW, resH)
            }
        }
        GPU.disableBlend()
    }

    /** Routes a pointer-down: invokes the topmost interactive region (overlay first) containing the point.
     *  Starting a hold on a stepper button is handled here too. */
    fun hitTest(px: Float, py: Float): Boolean {
        for (i in overlayClicks.indices.reversed()) if (fire(overlayClicks[i], px, py)) return true
        for (i in clicks.indices.reversed()) if (fire(clicks[i], px, py)) return true
        return false
    }

    private fun fire(c: ClickRegion, px: Float, py: Float): Boolean {
        if (px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h) return false
        if (c.holdSign != 0) { heldRegion = c; heldSeconds = 0f; repeatTimer = 0f }
        c.onClick()
        return true
    }

    /** While a stepper button is held, repeats its step with an accelerating magnitude. Call each frame
     *  with the current pointer position; repeats pause while the pointer is off the button. */
    fun updateHold(px: Float, py: Float, dtSeconds: Float) {
        val r = heldRegion ?: return
        val step = r.onStep ?: return
        if (px < r.x || px > r.x + r.w || py < r.y || py > r.y + r.h) return
        heldSeconds += dtSeconds
        repeatTimer += dtSeconds
        if (heldSeconds < INITIAL_DELAY) return
        if (repeatTimer >= REPEAT_INTERVAL) {
            repeatTimer = 0f
            step(r.holdSign * magnitude(heldSeconds))
        }
    }

    /** End any in-progress hold (call on pointer release). */
    fun releaseHold() { heldRegion = null; heldSeconds = 0f; repeatTimer = 0f }

    /** Accelerating step magnitude by how long the button's been held (for ×1000-scale values). */
    private fun magnitude(t: Float): Int = when {
        t < 1f -> 1
        t < 2f -> 10
        t < 3.5f -> 100
        else -> 1000
    }

    fun cleanup() {
        rectRenderer.deleteProgram()
        textRenderer.cleanup()
    }

    // ── internal emit API (called by the builders) ─ all return Unit (so Item.emit overrides stay Unit) ─
    internal fun emitRect(x: Float, y: Float, w: Float, h: Float, color: Long) { rects.add(RectCmd(x, y, w, h, color)) }
    internal fun emitTextLeft(text: String, x: Float, topY: Float, h: Float, color: Long) {
        texts.add(TextCmd(text, x, topY, h, color, centered = false, centerX = 0f))
    }
    internal fun emitTextCentered(text: String, centerX: Float, topY: Float, h: Float, color: Long) {
        texts.add(TextCmd(text, 0f, topY, h, color, centered = true, centerX = centerX))
    }
    internal fun emitClick(x: Float, y: Float, w: Float, h: Float, onClick: () -> Unit) {
        clicks.add(ClickRegion(x, y, w, h, onClick))
    }
    /** A hold-to-repeat stepper button: fires `onStep(sign)` on press, then `onStep(sign·magnitude)` while held. */
    internal fun emitStepper(x: Float, y: Float, w: Float, h: Float, sign: Int, onStep: (Int) -> Unit) {
        clicks.add(ClickRegion(x, y, w, h, { onStep(sign) }, holdSign = sign, onStep = onStep))
    }

    internal fun emitOverlayRect(x: Float, y: Float, w: Float, h: Float, color: Long) { overlayRects.add(RectCmd(x, y, w, h, color)) }
    internal fun emitOverlayTextLeft(text: String, x: Float, topY: Float, h: Float, color: Long) {
        overlayTexts.add(TextCmd(text, x, topY, h, color, centered = false, centerX = 0f))
    }
    internal fun emitOverlayClick(x: Float, y: Float, w: Float, h: Float, onClick: () -> Unit) {
        overlayClicks.add(ClickRegion(x, y, w, h, onClick))
    }

    /** Starting offset (px from the anchored edge) for the next panel at [anchor], then advance it. */
    internal fun nextPanelOffset(anchor: Anchor, margin: Float, span: Float, gap: Float): Float {
        val cur = anchorCursor[anchor] ?: margin
        anchorCursor[anchor] = cur + span + gap
        return cur
    }

    /** Horizontal base (px from the anchored edge to the current column's near side) for [anchor]. */
    internal fun columnInset(anchor: Anchor, margin: Float): Float = anchorInset[anchor] ?: margin

    /** Record a panel's far extent (inset + width) so the next column can start past it. */
    internal fun growColumn(anchor: Anchor, extent: Float) {
        anchorColumnExtent[anchor] = maxOf(anchorColumnExtent[anchor] ?: 0f, extent)
    }

    /** Start a fresh column [margin] past the current one (to the left for right anchors), resetting the
     *  vertical stack so the new column begins at the top edge. */
    internal fun startNewColumn(anchor: Anchor, margin: Float) {
        anchorInset[anchor] = (anchorColumnExtent[anchor] ?: margin) + margin
        anchorCursor[anchor] = margin
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

    companion object {
        private const val INITIAL_DELAY = 0.35f   // hold this long before auto-repeat begins
        private const val REPEAT_INTERVAL = 0.05f // then fire this often
    }
}

/** Frame-scoped builder: add panels. */
class UiBuilder internal constructor(private val ui: Ui) {
    /** An auto-sized panel anchored to a screen [anchor] corner. Panels at the same anchor **stack**
     *  (each below the previous, [margin] apart). */
    fun panel(
        anchor: Anchor,
        margin: Float = 12f,
        padding: Float = 8f,
        background: Long = 0x000000C0,
        rowHeight: Float = 18f,
        newColumn: Boolean = false,
        block: PanelBuilder.() -> Unit,
    ) {
        val pb = PanelBuilder(rowHeight).apply(block)
        if (pb.items.isEmpty()) return
        val textH = rowHeight * 0.68f
        val contentW = pb.items.maxOf { it.measureWidth(textH) }
        val contentH = pb.items.sumOf { it.height.toDouble() }.toFloat()
        val w = padding * 2 + contentW
        val h = padding * 2 + contentH
        if (newColumn) ui.startNewColumn(anchor, margin)             // a fresh column beside the previous one
        val inset = ui.columnInset(anchor, margin)                   // horizontal base for this column
        val offset = ui.nextPanelOffset(anchor, margin, h, margin)   // vertical stack distance from the anchored edge
        val x = when (anchor) {
            Anchor.TopLeft, Anchor.BottomLeft -> inset
            Anchor.TopRight, Anchor.BottomRight -> ui.resWidth - inset - w
        }
        val y = when (anchor) {
            Anchor.TopLeft, Anchor.TopRight -> offset
            Anchor.BottomLeft, Anchor.BottomRight -> ui.resHeight - offset - h
        }
        ui.growColumn(anchor, inset + w)
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

    /** A label + a click-to-expand dropdown field showing [value]; when [open], its [options] render in
     *  the overlay layer and a pick calls [onPick]. [onToggle] opens/closes the dropdown. */
    fun picker(label: String, value: String, options: List<String>, open: Boolean, onToggle: () -> Unit, onPick: (Int) -> Unit) =
        items.add(PickerItem(label, value, options, open, onToggle, onPick, rowHeight))

    /** A label + `[-] value [+]` where ± are hold-to-repeat steppers calling [onStep] with a signed,
     *  accelerating magnitude. */
    fun stepper(label: String, value: String, onStep: (Int) -> Unit) = items.add(StepperItem(label, value, onStep, rowHeight))

    /** A horizontal row of small buttons. */
    fun actionRow(buttons: List<Triple<String, Long, () -> Unit>>) = items.add(ActionRowItem(buttons, rowHeight))

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
    }

    private class PickerItem(
        val label: String, val value: String, val options: List<String>, val open: Boolean,
        val onToggle: () -> Unit, val onPick: (Int) -> Unit, override val height: Float,
    ) : Item {
        private fun fieldW(textH: Float): Float {
            var w = UiTextRenderer.measureWidthPx(value, textH)
            for (o in options) w = maxOf(w, UiTextRenderer.measureWidthPx(o, textH))
            return w + textH * 2.5f   // padding + the dropdown arrow
        }
        override fun measureWidth(textH: Float) = UiTextRenderer.measureWidthPx(label, textH) + textH + fieldW(textH)
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            ui.emitTextLeft(label, x, ty, textH, 0x9A9A9AFFL)
            val fw = fieldW(textH)
            val fx = x + contentW - fw
            ui.emitRect(fx, topY + 1f, fw, height - 2f, if (open) 0x2A4A6AFFL else 0x303848FFL)
            ui.emitTextLeft(value, fx + textH * 0.4f, ty, textH, 0xFFFFFFFFL)
            ui.emitTextLeft("v", fx + fw - textH * 0.9f, ty, textH, 0xAACCFFFFL)
            ui.emitClick(fx, topY + 1f, fw, height - 2f, onToggle)
            if (open) {
                // Drop the option list into the overlay layer (on top of every panel), below the field.
                var oy = topY + height
                for ((i, opt) in options.withIndex()) {
                    ui.emitOverlayRect(fx, oy, fw, height, 0x1A2233FFL)
                    ui.emitOverlayTextLeft(opt, fx + textH * 0.4f, oy + (height - textH) * 0.5f, textH, if (opt == value) 0xFFE070FFL else 0xCFE0FFFFL)
                    ui.emitOverlayClick(fx, oy, fw, height) { onPick(i) }
                    oy += height
                }
            }
        }
    }

    private class StepperItem(val label: String, val value: String, val onStep: (Int) -> Unit, override val height: Float) : Item {
        private fun btnW(textH: Float) = textH * 1.6f
        private fun valW(textH: Float) = maxOf(UiTextRenderer.measureWidthPx(value, textH), textH * 3f)
        override fun measureWidth(textH: Float) =
            UiTextRenderer.measureWidthPx(label, textH) + textH + btnW(textH) * 2f + valW(textH) + textH
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            ui.emitTextLeft(label, x, ty, textH, 0x9A9A9AFFL)
            val bw = btnW(textH); val vw = valW(textH)
            val groupW = bw * 2f + vw
            val gx = x + contentW - groupW
            // [-]
            ui.emitRect(gx, topY + 1f, bw, height - 2f, 0x444C5CFFL)
            ui.emitTextCentered("-", gx + bw * 0.5f, ty, textH, 0xFFFFFFFFL)
            ui.emitStepper(gx, topY + 1f, bw, height - 2f, -1, onStep)
            // value
            ui.emitTextCentered(value, gx + bw + vw * 0.5f, ty, textH, 0xFFFFFFFFL)
            // [+]
            val px = gx + bw + vw
            ui.emitRect(px, topY + 1f, bw, height - 2f, 0x444C5CFFL)
            ui.emitTextCentered("+", px + bw * 0.5f, ty, textH, 0xFFFFFFFFL)
            ui.emitStepper(px, topY + 1f, bw, height - 2f, +1, onStep)
        }
    }

    private class ActionRowItem(val buttons: List<Triple<String, Long, () -> Unit>>, override val height: Float) : Item {
        private fun bw(label: String, textH: Float) = UiTextRenderer.measureWidthPx(label, textH) + textH * 1.6f
        override fun measureWidth(textH: Float) = buttons.sumOf { bw(it.first, textH).toDouble() }.toFloat() + (buttons.size - 1) * textH * 0.5f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            var bx = x
            for ((label, color, onClick) in buttons) {
                val w = bw(label, textH)
                ui.emitRect(bx, topY + 1f, w, height - 2f, color)
                ui.emitTextCentered(label, bx + w * 0.5f, topY + (height - textH) * 0.5f, textH, contrast(color))
                ui.emitClick(bx, topY + 1f, w, height - 2f, onClick)
                bx += w + textH * 0.5f
            }
        }
    }

    private class GapItem(override val height: Float) : Item {
        override fun measureWidth(textH: Float) = 0f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) = Unit
    }
}

private fun contrast(rgba: Long): Long {
    val r = ((rgba ushr 24) and 0xFF).toFloat() / 255f
    val g = ((rgba ushr 16) and 0xFF).toFloat() / 255f
    val b = ((rgba ushr 8) and 0xFF).toFloat() / 255f
    return if (0.299f * r + 0.587f * g + 0.114f * b < 0.5f) 0xFFFFFFFFL else 0x000000FFL
}
