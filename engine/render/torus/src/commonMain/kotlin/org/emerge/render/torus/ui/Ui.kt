package org.emerge.render.torus.ui

import org.emerge.render.torus.GPU
import kotlin.math.abs

/** Screen position a panel is anchored to. [Center] centres a panel on both axes (for title screens / modal
 *  menus); multiple [Center] panels stack downward from the centre of the first. */
enum class Anchor { TopLeft, TopRight, BottomLeft, BottomRight, Center, BottomCenter }

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
 * Hold-to-repeat needs the host to feed pointer state each frame: [hitTestDown] on press, [updateHold] while
 * held, [releaseHold] on release.
 */
class Ui {
    private val rectRenderer = UiRectRenderer()
    private val textRenderer = UiTextRenderer()
    private var resW = 1f
    private var resH = 1f
    private var densityScale = 1f

    // Per-frame accumulated draw commands (pixel coords) + interactive regions. A separate *overlay* layer
    // is drawn (and hit-tested) on top of the base layer — used for dropdown popups.
    private class RectCmd(val x: Float, val y: Float, val w: Float, val h: Float, val color: Long, val clip: Int = -1)
    private class TextCmd(val text: String, val x: Float, val y: Float, val h: Float, val color: Long, val centered: Boolean, val centerX: Float, val clip: Int = -1)
    private class ClickRegion(
        val x: Float, val y: Float, val w: Float, val h: Float, val onClick: () -> Unit,
        /** ±1 for a hold-to-repeat stepper button (0 = plain click). */
        val holdSign: Int = 0,
        /** Called with the (accelerating) signed step on each repeat tick; null for plain buttons. */
        val onStep: ((Int) -> Unit)? = null,
        /** Human-meaningful label for a button region (null for background/picker catchers). Lets a
         *  headless driver enumerate + tap widgets by name — see [elements] / [tapLabel]. */
        val label: String? = null,
        /** Index into [clipRects], or -1. A region scrolled out of its viewport must not be clickable,
         *  so hit-testing intersects the clip too. */
        val clip: Int = -1,
    )

    /** A scroll viewport laid out this frame (see [UiBuilder.scrollArea]). */
    private class ScrollRegion(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val contentH: Float)

    /** A labelled interactive region, for headless enumeration ([elements]). */
    class UiElement(val label: String, val x: Float, val y: Float, val w: Float, val h: Float)

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

    // ── Scrolling ──────────────────────────────────────────────────────────────────────────────────────
    /** Clip rectangles (px, `x,y,w,h`) this frame; commands reference them by index. */
    private val clipRects = ArrayList<FloatArray>()
    private var currentClip = -1
    /** Scroll distance per area id — the one piece of scroll state that must outlive the frame. */
    private val scrollOffsets = HashMap<String, Float>()
    private val scrollRegions = ArrayList<ScrollRegion>()
    private var activeScroll: String? = null
    private var scrollLastY = 0f
    /** Set once a press turns into a scroll drag — suppresses the click on release, like a desktop drag. */
    private var scrolled = false

    val resWidth: Float get() = resW
    val resHeight: Float get() = resH

    /** **dp → px.** Every size a caller passes ([UiBuilder.panel]'s `margin`/`padding`/`rowHeight`/
     *  `textSize`, [PanelBuilder.gap]) is a **density-independent** unit, multiplied by this to reach
     *  framebuffer pixels. Desktop leaves it at 1.0 (dp == px, so nothing changes); a phone host sets it
     *  from the display density (~2.625 on a 420dpi screen), without which the whole kit lays out at ~6dp
     *  rows against Android's 48dp minimum target. See `apps/cyto/MOBILE_READINESS.md` §2. */
    val scale: Float get() = densityScale

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = widthPx.coerceAtLeast(1f)
        resH = heightPx.coerceAtLeast(1f)
    }

    /** Set the dp → px factor (see [scale]). Hosts feed this from the display's density. */
    fun setDensity(scale: Float) {
        densityScale = scale.coerceAtLeast(0.01f)
    }

    /** Rebuilds this frame's widget tree (clears the previous frame's geometry; hold state persists). */
    fun frame(block: UiBuilder.() -> Unit) {
        rects.clear(); texts.clear(); clicks.clear()
        overlayRects.clear(); overlayTexts.clear(); overlayClicks.clear()
        anchorCursor.clear(); anchorInset.clear(); anchorColumnExtent.clear()
        clipRects.clear(); scrollRegions.clear(); currentClip = -1
        UiBuilder(this).block()
    }

    /** Draws this frame's widgets — base layer then overlay (dropdowns) on top. Call last (on top). */
    fun draw() {
        drawLayer(rects, texts)
        drawLayer(overlayRects, overlayTexts)
    }

    /** Apply (or clear) the scissor for clip index [clip]. GL's scissor origin is **bottom-left**, so the
     *  y flips against our top-left pixel space. */
    private fun applyClip(clip: Int) {
        if (clip < 0) { GPU.disableScissorTest(); return }
        val c = clipRects[clip]
        GPU.enableScissorTest()
        GPU.setScissor(c[0].toInt(), (resH - (c[1] + c[3])).toInt(), c[2].toInt(), c[3].toInt())
    }

    private fun drawLayer(rs: List<RectCmd>, ts: List<TextCmd>) {
        if (rs.isEmpty() && ts.isEmpty()) return
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        // Rects are emitted in tree order, so same-clip commands are contiguous: draw them in runs, one
        // instanced call per run, re-scissoring between runs.
        var i = 0
        while (i < rs.size) {
            val clip = rs[i].clip
            var j = i
            while (j < rs.size && rs[j].clip == clip) j++
            applyClip(clip)
            val n = j - i
            val centers = FloatArray(n * 2)
            val halfSizes = FloatArray(n * 2)
            val colors = FloatArray(n * 4)
            for (k in 0 until n) {
                val r = rs[i + k]
                centers[k * 2] = (r.x + r.w * 0.5f) / resW * 2f - 1f
                centers[k * 2 + 1] = 1f - (r.y + r.h * 0.5f) / resH * 2f
                halfSizes[k * 2] = r.w / resW
                halfSizes[k * 2 + 1] = r.h / resH
                packColor(r.color, colors, k * 4)
            }
            rectRenderer.drawInstanced(n, centers, halfSizes, colors)
            i = j
        }
        var lastClip = Int.MIN_VALUE
        for (t in ts) {
            if (t.clip != lastClip) { applyClip(t.clip); lastClip = t.clip }
            val (cr, cg, cb) = rgb(t.color)
            if (t.centered) {
                textRenderer.drawCentered(t.text, t.centerX, t.y + t.h * 0.5f, t.h, cr, cg, cb, resW, resH)
            } else {
                textRenderer.drawLeft(t.text, t.x, t.y, t.h, cr, cg, cb, resW, resH)
            }
        }
        GPU.disableScissorTest()
        GPU.disableBlend()
    }

    private fun pointInBounds(c: ClickRegion, px: Float, py: Float): Boolean {
        if (px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h) return false
        // A region scrolled outside its viewport is not clickable, even though its rect still "contains"
        // the point — the pixels aren't on screen.
        if (c.clip >= 0) {
            val r = clipRects[c.clip]
            if (px < r[0] || px > r[0] + r[2] || py < r[1] || py > r[1] + r[3]) return false
        }
        return true
    }

    /** Routes a pointer-down: marks the topmost interactive region (overlay first) containing the point.
     *  Starting a hold on a stepper button is handled here too. */
    fun hitTestDown(px: Float, py: Float): Boolean {
        // A press inside a scroll viewport also arms a scroll drag. This runs *before* the widget scan and
        // doesn't consume: the same press may still be a button tap — [dragTo] decides which it became.
        activeScroll = null
        scrolled = false
        for (r in scrollRegions) {
            if (px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h) {
                activeScroll = r.id
                scrollLastY = py
                break
            }
        }
        for (i in overlayClicks.indices.reversed()) if (regionHitTestDown(overlayClicks[i], px, py)) return true
        for (i in clicks.indices.reversed()) if (regionHitTestDown(clicks[i], px, py)) return true
        return activeScroll != null
    }

    /** Feed pointer movement while held. If the press began in a scroll area, this scrolls it and (past
     *  [SCROLL_SLOP]) cancels the pending click, exactly as a drag does elsewhere. Returns true if it scrolled. */
    fun dragTo(px: Float, py: Float): Boolean {
        val id = activeScroll ?: return false
        val dy = py - scrollLastY
        scrollLastY = py
        if (!scrolled && abs(dy) < SCROLL_SLOP) return false
        scrolled = true
        val region = scrollRegions.firstOrNull { it.id == id } ?: return false
        val max = maxOf(0f, region.contentH - region.h)
        scrollOffsets[id] = ((scrollOffsets[id] ?: 0f) - dy).coerceIn(0f, max)
        heldRegion = null   // a scroll is not a hold-to-repeat
        return true
    }

    /** Scroll [id] by [dy] px (mouse wheel). Clamped to the content. */
    fun scrollBy(id: String, dy: Float) {
        val region = scrollRegions.firstOrNull { it.id == id } ?: return
        val max = maxOf(0f, region.contentH - region.h)
        scrollOffsets[id] = ((scrollOffsets[id] ?: 0f) + dy).coerceIn(0f, max)
    }

    /** The scroll area under a point, or null — lets a host route wheel events. */
    fun scrollAreaAt(px: Float, py: Float): String? =
        scrollRegions.firstOrNull { px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h }?.id

    private fun regionHitTestDown(c: ClickRegion, px: Float, py: Float): Boolean {
        if (!pointInBounds(c, px, py)) return false
        heldRegion = c
        heldSeconds = 0f
        repeatTimer = 0f
        return true
    }

    /** Routes a pointer-up: invokes the topmost interactive region (overlay first) containing the point. */
    fun hitTestUp(px: Float, py: Float): Boolean {
        // A press that turned into a scroll is not a click.
        if (scrolled) { activeScroll = null; scrolled = false; return true }
        activeScroll = null
        // Valid clicks must end within [INITIAL_DELAY] of the initial mouse down
        if (heldSeconds >= INITIAL_DELAY) return false
        for (i in overlayClicks.indices.reversed()) if (regionHitTestUp(overlayClicks[i], px, py)) return true
        for (i in clicks.indices.reversed()) if (regionHitTestUp(clicks[i], px, py)) return true
        return false
    }

    private fun regionHitTestUp(c: ClickRegion, px: Float, py: Float): Boolean {
        // Valid clicks must end on the same region they started in.
        if (!pointInBounds(c, px, py)) return false
        // TODO proper comparison
        if (c.label != heldRegion?.label) return false
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
        val delta = magnitude(heldSeconds)
        if (repeatTimer >= repeatInterval(delta)) {
            repeatTimer = 0f
            step(r.holdSign * delta)
        }
    }

    /** End any in-progress hold (call on pointer release). */
    fun releaseHold() { heldRegion = null; heldSeconds = 0f; repeatTimer = 0f }

    /** Accelerating step magnitude by how long the button's been held (for ×1000-scale values). */
    private fun magnitude(t: Float): Int = when {
        t < 1f -> 1
        t < 5f -> 10
        t < 10f -> 100
        else -> 1000
    }

    /** Decelerating step interval by how large the increment is. */
    private fun repeatInterval(n: Int): Float = when {
        n < 10 -> 0.05f
        n < 100 -> 0.25f
        else -> 0.50f
    }

    fun cleanup() {
        rectRenderer.deleteProgram()
        textRenderer.cleanup()
    }

    // ── internal emit API (called by the builders) ─ all return Unit (so Item.emit overrides stay Unit) ─
    internal fun emitRect(x: Float, y: Float, w: Float, h: Float, color: Long) { rects.add(RectCmd(x, y, w, h, color, currentClip)) }
    internal fun emitTextLeft(text: String, x: Float, topY: Float, h: Float, color: Long) {
        texts.add(TextCmd(text, x, topY, h, color, centered = false, centerX = 0f, clip = currentClip))
    }
    internal fun emitTextCentered(text: String, centerX: Float, topY: Float, h: Float, color: Long) {
        texts.add(TextCmd(text, 0f, topY, h, color, centered = true, centerX = centerX, clip = currentClip))
    }
    internal fun emitClick(x: Float, y: Float, w: Float, h: Float, label: String? = null, onClick: () -> Unit) {
        clicks.add(ClickRegion(x, y, w, h, onClick, label = label, clip = currentClip))
    }

    /** Open a clip + scroll viewport; returns the scroll offset to lay content out against. */
    internal fun beginScroll(id: String, x: Float, y: Float, w: Float, h: Float): Float {
        clipRects.add(floatArrayOf(x, y, w, h))
        currentClip = clipRects.size - 1
        return scrollOffsets[id] ?: 0f
    }

    /** Close a scroll viewport, recording its content height so scrolling can clamp to it. */
    internal fun endScroll(id: String, x: Float, y: Float, w: Float, h: Float, contentH: Float) {
        scrollRegions.add(ScrollRegion(id, x, y, w, h, contentH))
        // Content can shrink between frames (a collapsed group); re-clamp so the view can't stay past the end.
        val max = maxOf(0f, contentH - h)
        scrollOffsets[id]?.let { if (it > max) scrollOffsets[id] = max }
        currentClip = -1
    }

    /** The current frame's labelled interactive regions (buttons), for headless drivers. Rebuild the
     *  frame with [frame] first. */
    fun elements(): List<UiElement> {
        val out = ArrayList<UiElement>()
        for (c in overlayClicks) if (c.label != null) out.add(UiElement(c.label, c.x, c.y, c.w, c.h))
        for (c in clicks) if (c.label != null) out.add(UiElement(c.label, c.x, c.y, c.w, c.h))
        return out
    }

    /** Invoke the first button region whose label contains [label] (case-insensitive). Returns true if
     *  one fired. Overlay regions (open dropdowns) win, then the base layer. */
    fun tapLabel(label: String): Boolean {
        val q = label.lowercase()
        for (c in overlayClicks) if (c.label?.lowercase()?.contains(q) == true) { c.onClick(); return true }
        for (c in clicks) if (c.label?.lowercase()?.contains(q) == true) { c.onClick(); return true }
        return false
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
        private const val SCROLL_SLOP = 2f        // px of movement before a press becomes a scroll
    }
}

/** Frame-scoped builder: add panels. */
class UiBuilder internal constructor(private val ui: Ui) {
    /**
     * An auto-sized panel anchored to a screen [anchor] corner. Panels at the same anchor **stack**
     * (each below the previous, [margin] apart).
     *
     * **All sizes here are dp**, scaled to pixels by [Ui.scale] (1.0 on desktop, so dp == px there).
     * [textSize] defaults to a fixed ratio of [rowHeight] — the historical coupling — but is an
     * independent knob: touch layouts need a tall row (≥48dp) with *normal* text in it, which the ratio
     * can't express (a 48dp row would imply 33dp text, wider than a phone screen).
     */
    fun panel(
        anchor: Anchor,
        margin: Float = 12f,
        padding: Float = 8f,
        background: Long = 0x000000C0,
        rowHeight: Float = 18f,
        textSize: Float = rowHeight * TEXT_TO_ROW_RATIO,
        newColumn: Boolean = false,
        block: PanelBuilder.() -> Unit,
    ) {
        val s = ui.scale
        val marginPx = margin * s
        val paddingPx = padding * s
        val textH = textSize * s
        val pb = PanelBuilder(rowHeight * s, s).apply(block)
        if (pb.items.isEmpty()) return
        val contentW = pb.items.maxOf { it.measureWidth(textH) }
        val contentH = pb.items.sumOf { it.height.toDouble() }.toFloat()
        val w = paddingPx * 2 + contentW
        val h = paddingPx * 2 + contentH
        if (anchor == Anchor.Center) {
            val x = (ui.resWidth - w) * 0.5f
            val stack = ui.nextPanelOffset(anchor, 0f, h, marginPx)  // 0 for the first, then h+margin for extras
            val y = (ui.resHeight - h) * 0.5f + stack
            emitPanel(x, y, w, h, paddingPx, contentW, textH, background, pb)
            return
        }
        if (anchor == Anchor.BottomCenter) {
            // Centred horizontally, anchored a [margin] gap above the bottom edge; extra panels stack upward.
            val x = (ui.resWidth - w) * 0.5f
            val stack = ui.nextPanelOffset(anchor, marginPx, h, marginPx)
            val y = ui.resHeight - h - stack
            emitPanel(x, y, w, h, paddingPx, contentW, textH, background, pb)
            return
        }
        if (newColumn) ui.startNewColumn(anchor, marginPx)             // a fresh column beside the previous one
        val inset = ui.columnInset(anchor, marginPx)                   // horizontal base for this column
        val offset = ui.nextPanelOffset(anchor, marginPx, h, marginPx) // vertical stack distance from the anchored edge
        val x = when (anchor) {
            Anchor.TopRight, Anchor.BottomRight -> ui.resWidth - inset - w
            else -> inset
        }
        val y = when (anchor) {
            Anchor.BottomLeft, Anchor.BottomRight -> ui.resHeight - offset - h
            else -> offset
        }
        ui.growColumn(anchor, inset + w)
        emitPanel(x, y, w, h, paddingPx, contentW, textH, background, pb)
    }

    /** Emit a panel's background + click-catcher + rows at an already-resolved (x, y). */
    private fun emitPanel(
        x: Float, y: Float, w: Float, h: Float, padding: Float,
        contentW: Float, textH: Float, background: Long, pb: PanelBuilder,
    ) {
        ui.emitRect(x, y, w, h, background)
        // The panel background absorbs taps so a press on the panel (not just its buttons) doesn't fall
        // through to the world behind it. Registered BEFORE the items, so each button — added after — still
        // wins in hitTest's reverse-order scan; this no-op only catches presses on the empty panel area.
        ui.emitClick(x, y, w, h) {}
        var rowY = y + padding
        for (item in pb.items) {
            item.emit(ui, x + padding, rowY, contentW, textH)
            rowY += item.height
        }
    }

    /** A full-screen fill — a backdrop for a title screen / modal menu. Emit it **first** (it draws behind
     *  later panels) — it also swallows any click that misses a widget so the scene behind doesn't react. */
    fun background(color: Long) {
        ui.emitRect(0f, 0f, ui.resWidth, ui.resHeight, color)
        ui.emitClick(0f, 0f, ui.resWidth, ui.resHeight) {}
    }

    /**
     * A **scrolling, clipped** vertical stack inside an explicit viewport (px). Content taller than the
     * viewport scrolls: drag inside it (see [Ui.dragTo]) or wheel over it ([Ui.scrollBy]). Rows clipped
     * out of view are neither drawn nor clickable.
     *
     * [id] keys the scroll offset across frames — it must be stable and unique per area, since the widget
     * tree itself is rebuilt every frame and carries no state. Unlike [panel], the viewport is **given**,
     * not auto-sized: a scroll area exists precisely because its content doesn't fit.
     *
     * Sizes are dp (scaled by [Ui.scale]); the viewport rect is raw px, since callers derive it from the
     * screen. Needed because genome length is unbounded — see `apps/cyto/UI_REDESIGN.md` §2.3.
     */
    /**
     * A **full-screen modal** — the narrow-layout host for L3 (`apps/cyto/UI_REDESIGN.md` §3): a fixed
     * title bar (back chevron + title + optional `...` overflow), a fixed bottom bar of [actions], and a
     * **scrolling body** clipped between them. The body is a [scrollArea], so a gene taller than the screen
     * scrolls under the bars rather than running off the bottom (the mock proved the worst-case gene
     * overflows by ~1 row — §5.1).
     *
     * Draw order makes the bars occlude scrolled content: the backdrop fills first (behind), the body next
     * (clipped to the viewport), the bars last (on top, at the edges). [id] keys the body's scroll offset.
     *
     * All sizes are dp (× [Ui.scale]); [statusBar] reserves the notch/status inset a phone host passes.
     */
    fun modal(
        id: String,
        title: String,
        onBack: () -> Unit,
        actions: List<Triple<String, Long, () -> Unit>> = emptyList(),
        onOverflow: (() -> Unit)? = null,
        statusBar: Float = 0f,
        titleBar: Float = 56f,
        bottomBar: Float = 72f,
        margin: Float = 16f,
        background: Long = 0x0B0E14FFL,
        barColor: Long = 0x1B2230FFL,
        padding: Float = 8f,
        rowHeight: Float = 18f,
        textSize: Float = rowHeight * TEXT_TO_ROW_RATIO,
        body: PanelBuilder.() -> Unit,
    ) {
        val s = ui.scale
        val statusPx = statusBar * s
        val titlePx = titleBar * s
        val bottomPx = if (actions.isEmpty()) 0f else bottomBar * s
        val marginPx = margin * s
        val textH = textSize * s
        val fullW = ui.resWidth
        val fullH = ui.resHeight

        // Backdrop: fills + swallows any tap that misses a widget, so the scene behind stays inert.
        ui.emitRect(0f, 0f, fullW, fullH, background)
        ui.emitClick(0f, 0f, fullW, fullH) {}

        // Body viewport, between the bars — emitted before the chrome so the bars draw over it.
        val viewTop = statusPx + titlePx
        val viewH = fullH - viewTop - bottomPx
        scrollArea(id, 0f, viewTop, fullW, viewH, padding = margin, rowHeight = rowHeight, textSize = textSize, block = body)

        // Title bar (drawn on top of the body).
        ui.emitRect(0f, statusPx, fullW, titlePx, barColor)
        val titleMid = statusPx + (titlePx - textH) * 0.5f
        ui.emitTextLeft("<", marginPx, titleMid, textH, 0xAACCFFFFL)
        ui.emitClick(0f, statusPx, titlePx, titlePx, label = "back", onClick = onBack)
        ui.emitTextLeft(title, marginPx + textH * 1.6f, titleMid, textH, 0xFFFFFFFFL)
        if (onOverflow != null) {
            ui.emitTextLeft("...", fullW - marginPx - textH * 1.5f, titleMid, textH, 0xAACCFFFFL)
            ui.emitClick(fullW - titlePx, statusPx, titlePx, titlePx, label = "overflow", onClick = onOverflow)
        }

        // Bottom action bar.
        if (actions.isNotEmpty()) {
            val by = fullH - bottomPx
            ui.emitRect(0f, by, fullW, bottomPx, barColor)
            val n = actions.size
            val btnH = (bottomPx - marginPx).coerceAtLeast(textH * 1.5f)
            val btnY = by + (bottomPx - btnH) * 0.5f
            val btnW = (fullW - marginPx * (n + 1)) / n
            for ((i, a) in actions.withIndex()) {
                val bx = marginPx + i * (btnW + marginPx)
                ui.emitRect(bx, btnY, btnW, btnH, a.second)
                ui.emitTextCentered(a.first, bx + btnW * 0.5f, btnY + (btnH - textH) * 0.5f, textH, contrast(a.second))
                ui.emitClick(bx, btnY, btnW, btnH, label = a.first, onClick = a.third)
            }
        }
    }

    fun scrollArea(
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        padding: Float = 8f,
        rowHeight: Float = 18f,
        textSize: Float = rowHeight * TEXT_TO_ROW_RATIO,
        background: Long = 0x00000000,
        block: PanelBuilder.() -> Unit,
    ) {
        val s = ui.scale
        val paddingPx = padding * s
        val textH = textSize * s
        val pb = PanelBuilder(rowHeight * s, s).apply(block)
        val contentH = pb.items.sumOf { it.height.toDouble() }.toFloat() + paddingPx * 2

        val offset = ui.beginScroll(id, x, y, w, h)
        if (background != 0x00000000L) ui.emitRect(x, y, w, h, background)
        // Catches presses on empty space so they arm a scroll drag instead of falling through to the world.
        // Emitted before the rows, so each row — added after — still wins hitTest's reverse-order scan.
        ui.emitClick(x, y, w, h) {}
        var rowY = y + paddingPx - offset
        val contentW = w - paddingPx * 2
        for (item in pb.items) {
            // Cull rows fully outside the viewport: cheap, and keeps a long genome from emitting hundreds
            // of off-screen draw commands every frame.
            if (rowY + item.height >= y && rowY <= y + h) item.emit(ui, x + paddingPx, rowY, contentW, textH)
            rowY += item.height
        }
        ui.endScroll(id, x, y, w, h, contentH)
    }
}

/** The historical [UiBuilder.panel] text-height / row-height ratio, kept as the default so existing
 *  callers render identically. Touch layouts pass `textSize` explicitly instead. */
internal const val TEXT_TO_ROW_RATIO = 0.68f

/** Collects a panel's vertical stack of items, then [UiBuilder.panel] lays them out. [rowHeight] arrives
 *  already scaled to px; [scale] is carried for the items that take their own dp sizes ([gap]). */
class PanelBuilder internal constructor(private val rowHeight: Float, private val scale: Float = 1f) {
    internal val items = ArrayList<Item>()

    fun title(text: String, color: Long = 0xFFFFFFFFL) = items.add(TextItem(text, color, rowHeight))
    fun row(text: String, color: Long = 0xC8C8C8FFL) = items.add(TextItem(text, color, rowHeight))
    fun keyValue(key: String, value: String, keyColor: Long = 0x9A9A9AFFL, valueColor: Long = 0xFFFFFFFFL) =
        items.add(KeyValueItem(key, value, keyColor, valueColor, rowHeight))
    fun button(label: String, color: Long, onClick: () -> Unit) = items.add(ButtonItem(label, color, rowHeight, onClick))

    /** A button whose label is coloured **per segment**: each pair is (text, colour-or-null); a null
     *  colour uses the auto-contrast against the button [color]. The segments render as one centred label
     *  (e.g. to highlight just the blocking parts of a gene in orange). */
    fun button(spans: List<Pair<String, Long?>>, color: Long, onClick: () -> Unit) = items.add(SpanButtonItem(spans, color, rowHeight, onClick))
    /** Vertical space, in dp. */
    fun gap(height: Float = 6f) = items.add(GapItem(height * scale))

    /** A label + a click-to-expand dropdown field showing [value]; when [open], its [options] render in
     *  the overlay layer and a pick calls [onPick]. [onToggle] opens/closes the dropdown. */
    fun picker(label: String, value: String, options: List<String>, open: Boolean, onToggle: () -> Unit, onPick: (Int) -> Unit) =
        items.add(PickerItem(label, value, options, open, onToggle, onPick, rowHeight))

    /** A label + `[-] value [+]` where ± are hold-to-repeat steppers calling [onStep] with a signed,
     *  accelerating magnitude. */
    fun stepper(label: String, value: String, onStep: (Int) -> Unit) = items.add(StepperItem(label, value, onStep, rowHeight))

    /** A horizontal row of small buttons. */
    fun actionRow(buttons: List<Triple<String, Long, () -> Unit>>) = items.add(ActionRowItem(buttons, rowHeight))

    /**
     * A **chip** — a tappable current value, the workhorse of a progressive-disclosure screen: it *shows*
     * the value and *opens* the editor for it (`apps/cyto/UI_REDESIGN.md` §3). An empty [label] makes it
     * full-width (`[ DIVIDE (MITOSIS) v ]`); otherwise it's `LABEL        [ value v ]`.
     */
    fun chip(label: String, value: String, color: Long = 0x2A3550FFL, onTap: () -> Unit) =
        items.add(ChipItem(label, value, color, rowHeight, onTap))

    /**
     * A **segmented control** — 2–3 exclusive options, chosen inline with no drill-down (`> / <`,
     * `along / across`, `yes / no`). Segments size to the widest option: a fixed width silently clips
     * longer labels ("DAUGHTER"), which the design mock caught.
     */
    fun segmented(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
        items.add(SegmentedItem(label, options, selected, onSelect, rowHeight))

    /**
     * A **list row** — full-width, for a picker sheet: a title plus an optional one-line description, so
     * an option can explain itself (what `Lyse` *does*), which a dropdown never had room for. Taller when
     * described.
     */
    fun listRow(title: String, description: String = "", selected: Boolean = false, onClick: () -> Unit) =
        items.add(ListRowItem(title, description, selected, rowHeight, onClick))

    /**
     * One AND-clause as a **single row of three chips** — `[lhs] [cmp] [rhs]` — the core claim of the L3
     * redesign (`apps/cyto/UI_REDESIGN.md` §3): a clause that today costs ~5 stacked rows (LHS picker, L VAL
     * stepper, CMP, RHS, R VAL stepper) becomes one. The comparator sits in a narrow centre segment; the two
     * operands split the rest. Each chip taps into its own picker.
     */
    fun clauseRow(lhs: String, cmp: String, rhs: String, onLhs: () -> Unit, onCmp: () -> Unit, onRhs: () -> Unit) =
        items.add(ClauseRowItem(lhs, cmp, rhs, onLhs, onCmp, onRhs, rowHeight))

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
            ui.emitClick(x, topY + inset, contentW, height - inset * 2f, label = label, onClick = onClick)
        }
    }

    /** A button whose label is a sequence of independently-coloured segments (see [button]). */
    private class SpanButtonItem(val spans: List<Pair<String, Long?>>, val color: Long, override val height: Float, val onClick: () -> Unit) : Item {
        private fun width(textH: Float) = spans.fold(0f) { acc, s -> acc + UiTextRenderer.measureWidthPx(s.first, textH) }
        override fun measureWidth(textH: Float) = width(textH) + textH * 2f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val inset = 1f
            ui.emitRect(x, topY + inset, contentW, height - inset * 2f, color)
            var sx = x + (contentW - width(textH)) * 0.5f   // centre the whole label, lay segments left→right
            val ty = topY + (height - textH) * 0.5f
            for ((text, c) in spans) {
                ui.emitTextLeft(text, sx, ty, textH, c ?: contrast(color))
                sx += UiTextRenderer.measureWidthPx(text, textH)
            }
            ui.emitClick(x, topY + inset, contentW, height - inset * 2f, label = spans.joinToString("") { it.first }, onClick = onClick)
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
            ui.emitClick(fx, topY + 1f, fw, height - 2f, onClick = onToggle)
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
                ui.emitClick(bx, topY + 1f, w, height - 2f, label = label, onClick = onClick)
                bx += w + textH * 0.5f
            }
        }
    }

    private class GapItem(override val height: Float) : Item {
        override fun measureWidth(textH: Float) = 0f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) = Unit
    }

    /** See [chip]. */
    private class ChipItem(
        val label: String, val value: String, val color: Long, override val height: Float, val onTap: () -> Unit,
    ) : Item {
        private fun chipW(textH: Float) = UiTextRenderer.measureWidthPx(value, textH) + textH * 3f  // padding + arrow
        override fun measureWidth(textH: Float): Float =
            if (label.isEmpty()) chipW(textH)
            else UiTextRenderer.measureWidthPx(label, textH) + textH * 2f + chipW(textH)

        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            val cw = if (label.isEmpty()) contentW else chipW(textH)
            val cx = x + contentW - cw
            if (label.isNotEmpty()) ui.emitTextLeft(label, x, ty, textH, 0x9A9A9AFFL)
            ui.emitRect(cx, topY + 1f, cw, height - 2f, color)
            ui.emitTextCentered(value, cx + cw * 0.5f, ty, textH, 0xFFFFFFFFL)
            ui.emitTextLeft("V", cx + cw - textH * 0.9f, ty, textH, 0xAACCFFFFL)
            ui.emitClick(cx, topY + 1f, cw, height - 2f, label = if (label.isEmpty()) value else "$label $value", onClick = onTap)
        }
    }

    /** See [segmented]. */
    private class SegmentedItem(
        val label: String, val options: List<String>, val selected: Int,
        val onSelect: (Int) -> Unit, override val height: Float,
    ) : Item {
        private fun segW(textH: Float) =
            (options.maxOfOrNull { UiTextRenderer.measureWidthPx(it, textH) } ?: 0f) + textH * 1.2f
        override fun measureWidth(textH: Float) =
            (if (label.isEmpty()) 0f else UiTextRenderer.measureWidthPx(label, textH) + textH) + segW(textH) * options.size

        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            if (label.isNotEmpty()) ui.emitTextLeft(label, x, ty, textH, 0x9A9A9AFFL)
            val sw = segW(textH)
            var sx = x + contentW - sw * options.size
            for ((i, opt) in options.withIndex()) {
                val on = i == selected
                ui.emitRect(sx, topY + 1f, sw - 2f, height - 2f, if (on) 0x3A6EA5FFL else 0x252C3AFFL)
                ui.emitTextCentered(opt, sx + (sw - 2f) * 0.5f, ty, textH, if (on) 0xFFFFFFFFL else 0x9A9A9AFFL)
                ui.emitClick(sx, topY + 1f, sw - 2f, height - 2f, label = opt) { onSelect(i) }
                sx += sw
            }
        }
    }

    /** See [listRow]. */
    private class ListRowItem(
        val title: String, val description: String, val selected: Boolean,
        rowHeight: Float, val onClick: () -> Unit,
    ) : Item {
        override val height = if (description.isEmpty()) rowHeight else rowHeight * 1.6f
        override fun measureWidth(textH: Float) = maxOf(
            UiTextRenderer.measureWidthPx(title, textH),
            UiTextRenderer.measureWidthPx(description, textH * DESC_RATIO),
        ) + textH * 2f

        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            ui.emitRect(x, topY + 1f, contentW, height - 2f, if (selected) 0x35507AFFL else 0x252C3AFFL)
            if (description.isEmpty()) {
                ui.emitTextLeft(title, x + textH * 0.5f, topY + (height - textH) * 0.5f, textH, 0xFFFFFFFFL)
            } else {
                ui.emitTextLeft(title, x + textH * 0.5f, topY + height * 0.16f, textH, 0xFFFFFFFFL)
                ui.emitTextLeft(description, x + textH * 0.5f, topY + height * 0.55f, textH * DESC_RATIO, 0x9A9A9AFFL)
            }
            ui.emitClick(x, topY + 1f, contentW, height - 2f, label = title, onClick = onClick)
        }
    }

    /** See [clauseRow]. */
    private class ClauseRowItem(
        val lhs: String, val cmp: String, val rhs: String,
        val onLhs: () -> Unit, val onCmp: () -> Unit, val onRhs: () -> Unit, override val height: Float,
    ) : Item {
        override fun measureWidth(textH: Float): Float {
            val side = maxOf(UiTextRenderer.measureWidthPx(lhs, textH), UiTextRenderer.measureWidthPx(rhs, textH)) + textH * 3f
            return side * 2f + UiTextRenderer.measureWidthPx(cmp, textH) + textH * 2f
        }
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            val gap = textH * 0.5f
            val cmpW = textH * 3f
            val sideW = (contentW - cmpW - gap * 2f) * 0.5f
            fun opChip(cx: Float, value: String, onTap: () -> Unit) {
                ui.emitRect(cx, topY + 1f, sideW, height - 2f, 0x2A3550FFL)
                ui.emitTextCentered(value, cx + sideW * 0.5f, ty, textH, 0xFFFFFFFFL)
                ui.emitTextLeft("V", cx + sideW - textH * 0.9f, ty, textH, 0xAACCFFFFL)
                ui.emitClick(cx, topY + 1f, sideW, height - 2f, label = value, onClick = onTap)
            }
            opChip(x, lhs, onLhs)
            val mx = x + sideW + gap
            ui.emitRect(mx, topY + 1f, cmpW, height - 2f, 0x35507AFFL)
            ui.emitTextCentered(cmp, mx + cmpW * 0.5f, ty, textH, 0xFFFFFFFFL)
            ui.emitClick(mx, topY + 1f, cmpW, height - 2f, label = "cmp", onClick = onCmp)
            opChip(mx + cmpW + gap, rhs, onRhs)
        }
    }

    private companion object {
        /** A list row's description text, relative to its title. */
        const val DESC_RATIO = 0.78f
    }
}

private fun contrast(rgba: Long): Long {
    val r = ((rgba ushr 24) and 0xFF).toFloat() / 255f
    val g = ((rgba ushr 16) and 0xFF).toFloat() / 255f
    val b = ((rgba ushr 8) and 0xFF).toFloat() / 255f
    return if (0.299f * r + 0.587f * g + 0.114f * b < 0.5f) 0xFFFFFFFFL else 0x000000FFL
}
