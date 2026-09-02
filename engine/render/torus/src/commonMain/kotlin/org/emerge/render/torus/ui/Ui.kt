package org.emerge.render.torus.ui

import org.emerge.render.torus.GPU
import org.emerge.render.torus.Mat4
import kotlin.math.abs

/** Screen position a panel is anchored to. [Center] centres a panel on both axes (for title screens / modal
 *  menus); multiple [Center] panels stack downward from the centre of the first. */
enum class Anchor { TopLeft, TopRight, BottomLeft, BottomRight, Center, BottomCenter }

/** One button in a horizontal [PanelBuilder.controlRow]. [enabled] = false renders it dimmed and inert (no
 *  click registered) so a control can be visibly unavailable (e.g. FAST at the max speed). */
class ActionButton(val label: String, val color: Long, val enabled: Boolean = true, val onClick: () -> Unit)

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
    // Built on first draw, not on construction: both compile shaders, so eagerly creating them would mean a
    // Ui could only exist inside a GL context — and the layout/input half of this class (which is all logic)
    // could never be tested without one. Every use below is on a draw path, already inside the frame.
    private val rectRendererLazy = lazy { UiRectRenderer() }
    private val textRendererLazy = lazy { UiTextRenderer() }
    private val imageRendererLazy = lazy { UiImageRenderer() }
    private val rectRenderer by rectRendererLazy
    private val textRenderer by textRendererLazy
    private val imageRenderer by imageRendererLazy
    private var resW = 1f
    private var resH = 1f
    private var densityScale = 1f

    // Per-frame accumulated draw commands (pixel coords) + interactive regions. A separate *overlay* layer
    // is drawn (and hit-tested) on top of the base layer — used for dropdown popups.
    private sealed interface DrawCmd { val clip: Int }
    private class RectCmd(val x: Float, val y: Float, val w: Float, val h: Float, val color: Long, override val clip: Int = -1) : DrawCmd
    private class TextCmd(val text: String, val x: Float, val y: Float, val h: Float, val color: Long, val centered: Boolean, val centerX: Float, override val clip: Int = -1) : DrawCmd
    private class ImageCmd(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val textureId: Int,
        val uvMinX: Float, val uvMinY: Float, val uvMaxX: Float, val uvMaxY: Float,
        val uvCos: Float = 1f, val uvSin: Float = 0f, val round: Boolean = false,
        override val clip: Int = -1,
    ) : DrawCmd
    private class ClickRegion(
        val x: Float, val y: Float, val w: Float, val h: Float, val onClick: () -> Unit,
        /** ±1 for a hold-to-repeat stepper button (0 = plain click). */
        val holdSign: Int = 0,
        /** Called with the (accelerating) signed step on each repeat tick; null for plain buttons. */
        val onStep: ((Int) -> Unit)? = null,
        /** Human-meaningful label for a button region (null for background/picker catchers). Lets a
         *  headless driver enumerate + tap widgets by name — see [elements] / [tapLabel]. */
        val label: String? = null,
        /** A **stable identity** for this widget, independent of the words it displays — see [elementByKey].
         *  Null for anything a caller has not bothered to name. */
        val key: String? = null,
        /** Index into [clipRects], or -1. A region scrolled out of its viewport must not be clickable,
         *  so hit-testing intersects the clip too. */
        val clip: Int = -1,
    )

    /** A scroll viewport laid out this frame (see [UiBuilder.scrollArea]). */
    private class ScrollRegion(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val contentH: Float)

    /** An axis-aligned rect, used for a [UiElement]'s clip viewport. */
    class UiRect(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * A labelled interactive region, for headless enumeration ([elements]).
     *
     * [clip] is the scroll viewport the region was laid out inside, if any, and [visible] whether the region
     * is (partly) within it. A region scrolled out of its viewport is still *enumerated* — it exists, it is
     * merely off-screen — but it is not clickable and must not be drawn on: a caller that decorates a widget
     * (the campaign spotlight) has to be able to tell "scrolled away" from "absent", because those want
     * opposite responses.
     */
    class UiElement(
        val label: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val clip: UiRect? = null,
        /** The widget's stable identity, if it has one — see [elementByKey]. */
        val key: String? = null,
    ) {
        val visible: Boolean
            get() = clip == null ||
                (x + w > clip.x && x < clip.x + clip.w && y + h > clip.y && y < clip.y + clip.h)
    }

    /**
     * Seconds of wall time the host has fed in through [advanceClock] — an animation clock for immediate-mode
     * drawing, which has nowhere of its own to keep a phase.
     *
     * It is **not** derived from the frame count: a caller wanting a one-second pulse means one second, not
     * sixty frames, and the draw rate varies with the sim. Monotonic, and never reset, so a caller animates by
     * remembering the value it started at.
     */
    var clockSeconds: Float = 0f
        private set

    /** Advance the animation clock by one frame's wall time. Hosts already compute this for hold-to-repeat. */
    fun advanceClock(dtSeconds: Float) {
        if (dtSeconds > 0f) clockSeconds += dtSeconds
    }

    /** The rect of the panel emitted most recently this frame, or null before any. A panel is auto-sized and
     *  anchor-placed, so a caller that wants to draw *relative to its own panel* (the campaign coach, pointing
     *  a connector at a widget elsewhere on screen) has no other way to know where it landed. */
    var lastPanelRect: UiElement? = null
        private set

    internal fun notePanelRect(x: Float, y: Float, w: Float, h: Float) {
        lastPanelRect = UiElement("panel", x, y, w, h)
    }

    // Base layer, as a single **insertion-ordered** stream (rects and text interleaved), so a later opaque
    // rect occludes earlier text — a modal's text vanishes behind a sheet drawn over it. (The old
    // rects-then-text split couldn't do that; it's why the dropdown "overlay" layer exists.)
    private val cmds = ArrayList<DrawCmd>()
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

    // ── Drag handles ─────────────────────────────────────────────────────────────────────────────────────
    // A drag handle resizes something (today: the L2 bottom sheet's height). A press on it takes priority
    // over scroll; a short movement is still a tap (onTap), a longer one becomes a drag (onDrag per move,
    // onRelease to snap). The active handle persists across frames for the duration of the gesture.
    private class DragHandleRegion(
        val id: String, val x: Float, val y: Float, val w: Float, val h: Float,
        val onTap: () -> Unit, val onDrag: (Float) -> Unit, val onRelease: () -> Unit,
    )
    private val dragHandles = ArrayList<DragHandleRegion>()
    private var activeHandle: DragHandleRegion? = null
    private var handleStartY = 0f
    private var handleLastY = 0f
    private var handleDragged = false

    // ── Drag-and-drop (free 2-D) ───────────────────────────────────────────────────────────────────────────
    // Pick up a card (a DragSource covering its rect) and drop it on a DropTarget. Distinct from a drag handle
    // (which is a 1-D resize): here the pointer travels freely and the RELEASE point picks the target. A press
    // on a source arms it but doesn't cancel a co-located click — a short press is still a tap (so the card's
    // own token controls stay clickable); movement past the slop commits to the drag. The active source
    // persists across frames for the gesture's life; targets are re-registered each frame and only consulted
    // on drop. Used by the desktop genome card to re-group a gene by dragging it onto a group (§8a).
    private class DragSource(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val onDrop: (String?) -> Unit)
    private class DropTargetRegion(val id: String, val x: Float, val y: Float, val w: Float, val h: Float)
    private val dragSources = ArrayList<DragSource>()
    private val dropTargets = ArrayList<DropTargetRegion>()
    private var activeDrag: DragSource? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragCurX = 0f
    private var dragCurY = 0f
    private var dragCommitted = false
    // Long-press pickup ([longPressDrag]): the source under a press that hasn't been held long enough to
    // become a drag yet. It is NOT [activeDrag] — the press is still an ordinary scroll/tap until the hold
    // completes, which is the whole point. [updateHold] promotes it; movement past the slop cancels it.
    private var pendingDrag: DragSource? = null
    private var pendingSeconds = 0f

    // ── Hover ────────────────────────────────────────────────────────────────────────────────────────────
    // The last cursor position (px), persisted across frames (like hold/scroll state) so a widget rebuilt
    // each frame can ask whether the pointer is over it — the basis for desktop hover-reveal affordances
    // (`apps/cyto/UI_REDESIGN.md` §8a). Off-screen (< 0) means "no hover": touch hosts never call [hover],
    // so their frames report nothing hovered and pay nothing. See [isHovered].
    private var cursorX = -1f
    private var cursorY = -1f

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

    /**
     * **Long-press to pick up a draggable card** (off by default = the desktop/mouse behaviour, where a press
     * on a drag source takes priority over scrolling immediately).
     *
     * Turn it on wherever the drag surface *is* the scroll surface — a phone's cell sheet is a scrolling list
     * made of draggable gene cards, so an immediate pickup would eat every scroll gesture. With this on, a
     * press arms scroll as usual and only becomes a drag after [INITIAL_DELAY] with the finger held still;
     * moving first is a scroll, releasing early is a tap.
     *
     * Set from the **layout** (narrow), not the input device, so forcing the narrow UI on desktop (Cyto's F2)
     * reproduces the phone gesture exactly — otherwise it can't be tested without a phone in hand.
     */
    var longPressDrag = false

    /** Feed the current cursor position (px), for desktop **hover** affordances (`apps/cyto/UI_REDESIGN.md`
     *  §8a). Persists across frames, so the host calls it on every pointer move — *including* moves with no
     *  button down, unlike [hitTestDown]. Touch hosts never call it (there is no hover on touch). */
    fun hover(px: Float, py: Float) { cursorX = px; cursorY = py }

    /** Clear the hover position (pointer left the window) so nothing reports as hovered. */
    fun clearHover() { cursorX = -1f; cursorY = -1f }

    /** Whether the persisted cursor ([hover]) is inside [x],[y],[w],[h] **and** the currently-active clip
     *  (a row scrolled out of its viewport is not hovered, mirroring [pointInBounds]). Widgets call this at
     *  emit time — when their row rect is known — to reveal hover-only affordances. Always false on a host
     *  that never fed a cursor. */
    fun isHovered(x: Float, y: Float, w: Float, h: Float): Boolean {
        if (cursorX < 0f) return false
        if (cursorX < x || cursorX > x + w || cursorY < y || cursorY > y + h) return false
        if (currentClip >= 0) {
            val c = clipRects[currentClip]
            if (cursorX < c[0] || cursorX > c[0] + c[2] || cursorY < c[1] || cursorY > c[1] + c[3]) return false
        }
        return true
    }

    /** Whether [x],[y],[w],[h] is (partly) inside the currently-active clip. An inline dropdown uses this to
     *  suppress its overlay option list when the anchor row has scrolled out of its viewport (the overlay
     *  layer is otherwise unclipped) — the "close on scroll" behaviour, done by not drawing rather than by
     *  mutating caller state. True when there is no active clip. */
    fun isWithinClip(x: Float, y: Float, w: Float, h: Float): Boolean {
        if (currentClip < 0) return true
        val c = clipRects[currentClip]
        return x + w > c[0] && x < c[0] + c[2] && y + h > c[1] && y < c[1] + c[3]
    }

    /** Rebuilds this frame's widget tree (clears the previous frame's geometry; hold state persists). */
    fun frame(block: UiBuilder.() -> Unit) {
        cmds.clear(); clicks.clear(); modalFrom = 0; lastPanelRect = null; readoutRegions.clear()
        overlayRects.clear(); overlayTexts.clear(); overlayClicks.clear()
        anchorCursor.clear(); anchorInset.clear(); anchorColumnExtent.clear()
        clipRects.clear(); scrollRegions.clear(); currentClip = -1
        dragHandles.clear()
        dragSources.clear(); dropTargets.clear()
        UiBuilder(this).block()
    }

    /** Draws this frame's widgets — base layer then overlay (dropdowns) on top. Call last (on top). */
    fun draw() {
        drawStream(cmds)
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

    /** Draw the base layer in strict insertion order, so later commands paint over earlier ones. Consecutive
     *  rects sharing a clip are still coalesced into one instanced call; a text command (or a clip change)
     *  flushes the pending rect batch first, preserving draw order. */
    private fun drawStream(cs: List<DrawCmd>) {
        if (cs.isEmpty()) return
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        var curClip = Int.MIN_VALUE
        var i = 0
        while (i < cs.size) {
            val c = cs[i]
            if (c is RectCmd) {
                // Coalesce the run of same-clip rects starting here.
                val clip = c.clip
                var j = i
                while (j < cs.size && cs[j].let { it is RectCmd && it.clip == clip }) j++
                if (clip != curClip) { applyClip(clip); curClip = clip }
                val n = j - i
                val matrices = FloatArray(n * Mat4.FLOATS)
                val colors = FloatArray(n * 4)
                for (k in 0 until n) {
                    val r = cs[i + k] as RectCmd
                    val m = Mat4.translation(
                        (r.x + r.w * 0.5f) / resW * 2f - 1f,
                        1f - (r.y + r.h * 0.5f) / resH * 2f,
                    ).times(Mat4.scale(
                        r.w / resW,
                        r.h / resH,
                    ))
                    m.copyInto(matrices, k * Mat4.FLOATS)
                    packColor(r.color, colors, k * 4)
                }
                rectRenderer.drawInstanced(n, matrices, colors)
                i = j
            } else if (c is TextCmd) {
                if (c.clip != curClip) { applyClip(c.clip); curClip = c.clip }
                val (cr, cg, cb) = rgb(c.color)
                if (c.centered) textRenderer.drawCentered(c.text, c.centerX, c.y + c.h * 0.5f, c.h, cr, cg, cb, resW, resH)
                else textRenderer.drawLeft(c.text, c.x, c.y, c.h, cr, cg, cb, resW, resH)
                i++
            } else {
                val img = c as ImageCmd
                if (img.clip != curClip) { applyClip(img.clip); curClip = img.clip }
                val centerX = (img.x + img.w * 0.5f) / resW * 2f - 1f
                val centerY = 1f - (img.y + img.h * 0.5f) / resH * 2f
                val halfW = img.w / resW
                val halfH = img.h / resH
                imageRenderer.draw(
                    centerX, centerY, halfW, halfH,
                    img.uvMinX, img.uvMinY, img.uvMaxX, img.uvMaxY,
                    img.textureId,
                    uvCos = img.uvCos, uvSin = img.uvSin, round = img.round,
                )
                i++
            }
        }
        GPU.disableScissorTest()
        GPU.disableBlend()
    }

    /** Overlay layer (dropdown popups): non-overlapping, so the simpler rects-then-text split is fine. */
    private fun drawLayer(rs: List<RectCmd>, ts: List<TextCmd>) {
        if (rs.isEmpty() && ts.isEmpty()) return
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        var i = 0
        while (i < rs.size) {
            val clip = rs[i].clip
            var j = i
            while (j < rs.size && rs[j].clip == clip) j++
            applyClip(clip)
            val n = j - i
            val matrices = FloatArray(n * Mat4.FLOATS)
            val centers = FloatArray(n * 2)
            val halfSizes = FloatArray(n * 2)
            val colors = FloatArray(n * 4)
            for (k in 0 until n) {
                val r = rs[i + k]
                val m = Mat4.translation(
                    (r.x + r.w * 0.5f) / resW * 2f - 1f,
                    1f - (r.y + r.h * 0.5f) / resH * 2f,
                ).times(Mat4.scale(
                    r.w / resW,
                    r.h / resH,
                ))
                m.copyInto(matrices, k * Mat4.FLOATS)
                centers[k * 2] = (r.x + r.w * 0.5f) / resW * 2f - 1f
                centers[k * 2 + 1] = 1f - (r.y + r.h * 0.5f) / resH * 2f
                halfSizes[k * 2] = r.w / resW
                halfSizes[k * 2 + 1] = r.h / resH
                packColor(r.color, colors, k * 4)
            }
            rectRenderer.drawInstanced(n, matrices, colors)
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
        // A drag handle wins over scroll: a press on it arms a resize, not a content scroll.
        activeHandle = null
        handleDragged = false
        for (i in dragHandles.indices.reversed()) {
            val hnd = dragHandles[i]
            if (px >= hnd.x && px <= hnd.x + hnd.w && py >= hnd.y && py <= hnd.y + hnd.h) {
                activeHandle = hnd; handleStartY = py; handleLastY = py; break
            }
        }
        // A press on a drag source arms a potential card drag; like a handle it wins over scroll (so a genome
        // list doesn't scroll out from under a gene you're dragging). It does NOT consume — the same press may
        // still be a tap on a token inside the card; [dragTo] decides which it became once the pointer moves.
        activeDrag = null
        dragCommitted = false
        pendingDrag = null
        pendingSeconds = 0f
        if (activeHandle == null) {
            for (i in dragSources.indices.reversed()) {
                val s = dragSources[i]
                if (px >= s.x && px <= s.x + s.w && py >= s.y && py <= s.y + s.h) {
                    // Under [longPressDrag] the source is only *pending*: it must be held still to be picked
                    // up ([updateHold]), and until then this press falls through to the scroll arm below and
                    // to the click scan, exactly as if there were no drag source here at all.
                    if (longPressDrag) { pendingDrag = s } else { activeDrag = s }
                    dragStartX = px; dragStartY = py; dragCurX = px; dragCurY = py; break
                }
            }
        }
        if (activeHandle == null && activeDrag == null) {
            // Reverse order so the TOPMOST scroll region under the point wins — same convention as the click
            // scan below. Regions are appended in render order, so a modal sheet's scroll area (registered
            // last) must take the drag over a full-screen scroll region behind it (e.g. the L3 gene modal
            // under the L4 operand sheet); forward order armed the background region and the sheet, clipped
            // past the screen edge, could not be scrolled.
            for (i in scrollRegions.indices.reversed()) {
                val r = scrollRegions[i]
                if (px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h) {
                    activeScroll = r.id
                    scrollLastY = py
                    break
                }
            }
        }
        for (i in overlayClicks.indices.reversed()) if (regionHitTestDown(overlayClicks[i], px, py)) return true
        for (i in clicks.indices.reversed()) if (regionHitTestDown(clicks[i], px, py)) return true
        return activeScroll != null || activeHandle != null || activeDrag != null
    }

    /** Feed pointer movement while held. If the press began in a scroll area, this scrolls it and (past
     *  [SCROLL_SLOP]) cancels the pending click, exactly as a drag does elsewhere. Returns true if it scrolled. */
    fun dragTo(px: Float, py: Float): Boolean {
        // A pending long-press pickup that MOVES is a scroll, not a drag: drop it and let the scroll arm below
        // take the gesture. The slop is generous (a fingertip resting on glass jitters far more than the 2px
        // [SCROLL_SLOP] a mouse does) and is measured from the press point, not per-event.
        pendingDrag?.let {
            if (abs(px - dragStartX) > LONG_PRESS_SLOP * densityScale || abs(py - dragStartY) > LONG_PRESS_SLOP * densityScale) {
                pendingDrag = null
                pendingSeconds = 0f
            }
        }
        val src = activeDrag
        if (src != null) {
            dragCurX = px; dragCurY = py
            if (!dragCommitted && abs(px - dragStartX) < SCROLL_SLOP && abs(py - dragStartY) < SCROLL_SLOP) return false
            dragCommitted = true
            heldRegion = null   // committed to a drag, so a co-located token tap no longer fires on release
            return true
        }
        val hnd = activeHandle
        if (hnd != null) {
            val dy = py - handleLastY
            handleLastY = py
            if (!handleDragged && abs(py - handleStartY) < SCROLL_SLOP) return false
            handleDragged = true
            heldRegion = null   // committed to a drag, so no tap fires on release
            hnd.onDrag(dy)
            return true
        }
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

    /** How far [id] is scrolled (px from the top of its content). */
    fun scrollOffsetOf(id: String): Float = scrollOffsets[id] ?: 0f

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
        // A card drag: a committed drag drops on the target under the release point (or null = no target) and
        // swallows the click; an un-committed press was really a tap, so fall through to the normal click scan
        // (the card's token controls fire). See [dragTo].
        // A long-press pickup that never completed: the press was an ordinary tap after all, so forget it and
        // let the click scan below run.
        pendingDrag = null
        pendingSeconds = 0f
        val src = activeDrag
        if (src != null) {
            activeDrag = null
            if (dragCommitted) {
                dragCommitted = false
                val target = dropTargets.lastOrNull { px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h }
                src.onDrop(target?.id)
                return true
            }
        }
        // A drag handle: a committed drag snaps (onRelease) and swallows the click; a mere tap on it falls
        // through to onTap via the normal click scan below (the handle also registers a click region).
        val hnd = activeHandle
        if (hnd != null) {
            activeHandle = null
            if (handleDragged) { handleDragged = false; hnd.onRelease(); return true }
        }
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
        // Long-press pickup: the finger has been still on a drag source for long enough, so take the gesture
        // away from scroll/tap and start the drag under the finger. Run before the stepper logic below —
        // a drag source need not sit on a click region at all, and if it does, the region may be a plain
        // button with no `onStep` (which returns early).
        pendingDrag?.let { p ->
            pendingSeconds += dtSeconds
            if (pendingSeconds >= INITIAL_DELAY) {
                pendingDrag = null
                pendingSeconds = 0f
                activeDrag = p
                dragCommitted = true      // the hold IS the commitment: the ghost appears without waiting for a move
                dragStartX = px; dragStartY = py; dragCurX = px; dragCurY = py
                activeScroll = null       // the press is no longer a scroll...
                scrolled = false
                heldRegion = null         // ...nor a tap on the card underneath
                return                    // picking a card up never also scrolls the list out from under it
            }
        }
        // Edge autoscroll: while dragging near the top/bottom of a scrolling list, keep scrolling it, so a
        // drop target that starts off-screen can be reached at all. Uses the previous frame's regions (hosts
        // tick this before rebuilding the tree), which is a frame stale and imperceptible.
        if (dragCommitted) autoScrollDrag(px, py, dtSeconds)
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
    fun releaseHold() { heldRegion = null; heldSeconds = 0f; repeatTimer = 0f; pendingDrag = null; pendingSeconds = 0f }

    /** Scroll the list under a dragged card when the pointer nears its top/bottom edge, at a rate that ramps
     *  from 0 at [AUTOSCROLL_EDGE] to full at the edge itself (so it eases in instead of lurching). Only the
     *  region under the pointer scrolls — dragging over a different list leaves this one where it was. */
    private fun autoScrollDrag(px: Float, py: Float, dtSeconds: Float) {
        val edge = AUTOSCROLL_EDGE * densityScale
        val region = scrollRegions.lastOrNull { px >= it.x && px <= it.x + it.w && py >= it.y - edge && py <= it.y + it.h + edge } ?: return
        // Positive = reveal content further down the list (the offset is a distance scrolled from the top).
        val frac = when {
            py < region.y + edge -> -(1f - ((py - (region.y - edge)) / (edge * 2f))).coerceIn(0f, 1f)
            py > region.y + region.h - edge -> ((py - (region.y + region.h - edge)) / (edge * 2f)).coerceIn(0f, 1f)
            else -> return
        }
        val max = maxOf(0f, region.contentH - region.h)
        scrollOffsets[region.id] = ((scrollOffsets[region.id] ?: 0f) + frac * AUTOSCROLL_PX_PER_SEC * densityScale * dtSeconds).coerceIn(0f, max)
    }

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

    /** Release GPU resources. Nothing to release if this Ui never drew — and asking for them at teardown
     *  would *create* them, which is exactly what a never-drawn Ui has no context for. */
    fun cleanup() {
        if (rectRendererLazy.isInitialized()) rectRenderer.deleteProgram()
        if (textRendererLazy.isInitialized()) textRenderer.cleanup()
        if (imageRendererLazy.isInitialized()) imageRenderer.deleteProgram()
    }

    // ── internal emit API (called by the builders) ─ all return Unit (so Item.emit overrides stay Unit) ─
    internal fun emitRect(x: Float, y: Float, w: Float, h: Float, color: Long) { cmds.add(RectCmd(x, y, w, h, color, currentClip)) }
    internal fun emitImage(
        x: Float, y: Float, w: Float, h: Float,
        textureId: Int,
        uvMinX: Float, uvMinY: Float, uvMaxX: Float, uvMaxY: Float,
        uvCos: Float = 1f, uvSin: Float = 0f, round: Boolean = false,
    ) {
        cmds.add(ImageCmd(x, y, w, h, textureId, uvMinX, uvMinY, uvMaxX, uvMaxY, uvCos, uvSin, round, currentClip))
    }
    internal fun emitTextLeft(text: String, x: Float, topY: Float, h: Float, color: Long) {
        cmds.add(TextCmd(text, x, topY, h, color, centered = false, centerX = 0f, clip = currentClip))
    }
    internal fun emitTextCentered(text: String, centerX: Float, topY: Float, h: Float, color: Long) {
        cmds.add(TextCmd(text, 0f, topY, h, color, centered = true, centerX = centerX, clip = currentClip))
    }
    internal fun emitClick(x: Float, y: Float, w: Float, h: Float, label: String? = null, key: String? = null, onClick: () -> Unit) {
        clicks.add(ClickRegion(x, y, w, h, onClick, label = label, key = key, clip = currentClip))
    }

    /** Register a resize drag handle plus a co-located click region (so a tap on it fires [onTap] via the
     *  normal click path, while a drag routes through [onDrag]/[onRelease]). */
    internal fun emitDragHandle(
        x: Float, y: Float, w: Float, h: Float, id: String,
        onTap: () -> Unit, onDrag: (Float) -> Unit, onRelease: () -> Unit,
    ) {
        dragHandles.add(DragHandleRegion(id, x, y, w, h, onTap, onDrag, onRelease))
        clicks.add(ClickRegion(x, y, w, h, onTap, label = id, clip = currentClip))
    }

    /** Register a free-2-D **drag source** over [x],[y],[w],[h]: a press here can be picked up and dropped on
     *  a [emitDropTarget]; [onDrop] gets the target id under the release point (or null). Does not consume a
     *  co-located click — a short press is still a tap. */
    internal fun emitDragSource(id: String, x: Float, y: Float, w: Float, h: Float, onDrop: (String?) -> Unit) {
        dragSources.add(DragSource(id, x, y, w, h, onDrop))
    }

    /** Register a **drop target** [id] over [x],[y],[w],[h]. Only consulted at the moment of a drop. */
    internal fun emitDropTarget(id: String, x: Float, y: Float, w: Float, h: Float) {
        dropTargets.add(DropTargetRegion(id, x, y, w, h))
    }

    /** True while a drag source has been picked up and moved past the slop (a live drag is in progress). */
    val isDragging: Boolean get() = activeDrag != null && dragCommitted
    /** The id of the source being dragged (null when no committed drag). */
    val draggingId: String? get() = if (dragCommitted) activeDrag?.id else null
    /** Current pointer position (px) during a drag — for drawing the ghost / highlighting the hovered target. */
    val dragX: Float get() = dragCurX
    val dragY: Float get() = dragCurY
    /** This frame's registered drop targets as (id, rect) elements — for headless drivers to locate targets
     *  that carry no clickable label (e.g. the thin reorder slots). */
    fun dropTargetElements(): List<UiElement> = dropTargets.map { UiElement(it.id, it.x, it.y, it.w, it.h) }

    /** The drop-target id currently under the drag pointer, or null. Lets the caller highlight it. */
    fun hoveredDropTarget(): String? =
        if (!dragCommitted) null
        else dropTargets.lastOrNull { dragCurX >= it.x && dragCurX <= it.x + it.w && dragCurY >= it.y && dragCurY <= it.y + it.h }?.id

    /** Draw a floating ghost chip at the current drag pointer, on the overlay layer (drawn on top of
     *  everything, unclipped so it can follow the cursor anywhere). No-op when no drag is live. */
    internal fun drawDragGhost(label: String, color: Long) {
        if (!isDragging) return
        val textH = 14f * densityScale
        val h = textH + 8f * densityScale
        val pad = 8f * densityScale
        val w = UiTextRenderer.measureWidthPx(label, textH) + pad * 2f
        // Mouse: trail the cursor to its right. Finger: sit ABOVE the contact point and centred — a chip beside
        // the pointer is under the hand that put it there, which is the one place it can't be read.
        val x = if (longPressDrag) dragCurX - w * 0.5f else dragCurX + 12f * densityScale
        val y = if (longPressDrag) dragCurY - h - 24f * densityScale else dragCurY - h * 0.5f
        emitOverlayRect(x, y, w, h, color)
        emitOverlayTextLeft(label, x + pad, y + (h - textH) * 0.5f, textH, 0xFFFFFFFFL)
    }

    /** Push a plain clip rect (no scrolling) so following emissions are bounded to it; pair with [clearClip].
     *  Used by [UiBuilder.panel] to keep a panel's rows from spilling past its (screen-clamped) width. */
    internal fun pushClip(x: Float, y: Float, w: Float, h: Float) {
        clipRects.add(floatArrayOf(x, y, w, h))
        currentClip = clipRects.size - 1
    }
    internal fun clearClip() { currentClip = -1 }

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
        for (c in overlayClicks) if (c.label != null) out.add(c.asElement())
        for (i in modalFrom until clicks.size) if (clicks[i].label != null) out.add(clicks[i].asElement())
        return out
    }

    /**
     * The frame's **non-interactive** labelled regions: the panel's readout rows (a `LIGHT  4200` line, a
     * gene's condition clause). They are not clickable and never will be, but they are exactly what a coach
     * directing attention through a dense readout needs to point at, and [elements] — a tap driver's view of
     * the frame — must keep listing only what can actually be tapped.
     */
    fun readouts(): List<UiElement> = readoutRegions

    private val readoutRegions = ArrayList<UiElement>()

    /** Record a readout row's rect under [label] (its own text), optionally under a stable [key] as well.
     *  Called by the panel items as they emit. */
    internal fun noteReadout(label: String, x: Float, y: Float, w: Float, h: Float, key: String? = null) {
        if (label.isNotBlank() || key != null)
            readoutRegions.add(UiElement(label, x, y, w, h, clipRectOf(currentClip), key))
    }

    private fun ClickRegion.asElement(fallbackLabel: String = "") =
        UiElement(label ?: fallbackLabel, x, y, w, h, clipRectOf(clip), key)

    private fun clipRectOf(clip: Int): UiRect? =
        if (clip < 0) null else clipRects[clip].let { UiRect(it[0], it[1], it[2], it[3]) }

    /**
     * **Where the widget [tapLabel] would hit is** — the [occurrence]-th match for [label], as a rect, or null
     * if nothing matches this frame.
     *
     * Deliberately the same lookup [tapLabel] uses rather than a parallel one, so "point at it" and "tap it"
     * can never disagree: a campaign spotlight and the harness script that drives the same step resolve the
     * identical widget, and a label that stops matching fails both at once instead of quietly pointing at
     * the wrong thing.
     *
     * Only regions laid out **so far this frame** are visible to it, so call it after the panels are built.
     * A match scrolled out of its viewport still resolves, with [UiElement.visible] false — see there.
     *
     * Also resolves the frame's [readouts], so "point at the LIGHT reading" works without turning a readout
     * into a button — but **only on an exact name**. A readout row is any text a panel prints, including a
     * paragraph of coach prose, and substring matching there rings whichever sentence happens to contain the
     * word. Order is exact widget, exact readout, substring widget: naming a readout exactly beats a gene
     * card that merely contains the word (`LIGHT` against a `USE LIGHT` card), while a widget a script would
     * tap by that exact name is never displaced.
     */
    fun element(label: String, occurrence: Int = 1): UiElement? {
        val (exactClick, partialClick) = labelMatchesSplit(label)
        val ordered = exactClick.map { it.asElement(label) } + exactReadouts(label) +
            partialClick.map { it.asElement(label) }
        return ordered.getOrNull(occurrence - 1)
    }

    /** Readout rows whose whole text is [label]. */
    private fun exactReadouts(label: String): List<UiElement> =
        readoutRegions.filter { it.label.equals(label, ignoreCase = true) }

    /**
     * **Where the widget with this [key] is** — the identity lookup, for callers that must not depend on the
     * words a widget displays.
     *
     * A label is what a widget *says*, and it changes: renamed, retitled, or simply rewritten as the state
     * behind it changes (a gene's condition token reads `ALWAYS` until it reads `WHEN BIO < 3000`). A key is
     * what a widget *is*. The campaign coach points at slots that keep their meaning while their text moves
     * under them, so it resolves by key; a driver naming a button by the word on it still uses [tapLabel].
     *
     * Keys are expected to be unique within a frame — the first match wins, and there is no occurrence.
     *
     * Resolves keyed [readouts] too, and for the same reason it resolves keyed widgets: a number a coach has
     * to point at (a cytoplasm count in a chemistry table) has no fixed text at all — its label *is* the
     * value that keeps changing. Widgets win over readouts, so making a readout tappable later cannot move
     * the ring.
     */
    fun elementByKey(key: String): UiElement? =
        keyMatch(key)?.asElement() ?: readoutRegions.firstOrNull { it.key == key }

    /** Fire the widget with this [key]. The keyed twin of [tapLabel], so a script can drive the same slot the
     *  coach points at without either of them naming the word on it. */
    fun tapKey(key: String): Boolean {
        val hit = keyMatch(key) ?: return false
        hit.onClick()
        return true
    }

    private fun keyMatch(key: String): ClickRegion? =
        overlayClicks.firstOrNull { it.key == key }
            ?: clicks.subList(modalFrom, clicks.size).firstOrNull { it.key == key }

    /** Invoke the first button region whose label contains [label] (case-insensitive). Returns true if
     *  one fired. Overlay regions (open dropdowns) win, then the base layer. */
    fun tapLabel(label: String): Boolean = tapLabel(label, 1)

    /**
     * As [tapLabel], but fires the [occurrence]-th match (1-based) instead of the first.
     *
     * Duplicate labels are not an edge case in a genome editor — every gene card carries its own `ALWAYS`
     * and `USE LIGHT` token, so "the DIVIDE gene's energy source" is simply the *second* `USE LIGHT` on
     * screen and no label can distinguish it. Matches are ordered exactly as [tapLabel] prioritises them
     * (overlay before base, exact before substring), which is also the order [elements] lists them in, so a
     * driver can read the index straight off an `elements` dump.
     */
    fun tapLabel(label: String, occurrence: Int): Boolean {
        val hit = labelMatches(label).getOrNull(occurrence - 1) ?: return false
        hit.onClick()
        return true
    }

    /** Every region matching [label], in tap priority order. */
    private fun labelMatches(label: String): List<ClickRegion> =
        labelMatchesSplit(label).let { it.first + it.second }

    /** As [labelMatches], but with the exact and substring tiers kept apart, so [element] can slot readouts
     *  between them. */
    private fun labelMatchesSplit(label: String): Pair<List<ClickRegion>, List<ClickRegion>> {
        val q = label.lowercase()
        // Prefer an *exact* label match (over any element whose label merely contains the query) so a driver
        // can target e.g. a picker row "FEED" without a co-visible "+ FEED (1)" header stealing the tap; then
        // fall back to the first substring match. Overlay (sheet/dialog) clicks win over base-panel clicks.
        val reachable = clicks.subList(modalFrom, clicks.size)
        val exact = ArrayList<ClickRegion>()
        val partial = ArrayList<ClickRegion>()
        for (c in overlayClicks) when {
            c.label?.lowercase() == q -> exact.add(c)
            c.label?.lowercase()?.contains(q) == true -> partial.add(c)
        }
        for (c in reachable) when {
            c.label?.lowercase() == q -> exact.add(c)
            c.label?.lowercase()?.contains(q) == true -> partial.add(c)
        }
        return exact to partial
    }

    /**
     * Index into [clicks] of the topmost modal scrim, or 0. Regions emitted **before** it are covered by a
     * full-screen scrim and cannot be clicked by a human at all, so [elements] and [tapLabel] must not offer
     * them either.
     *
     * Coordinate hit-testing never needed this — it scans in reverse, so the scrim occludes what's under it
     * for free. The label path scans forward and had no notion of layering, so an open pick sheet still
     * exposed the gene card behind it: `tap-ui 0` fired the card's token instead of the sheet's value row
     * and silently reopened the sheet. Two identically-labelled widgets, one of them unreachable in the
     * real UI, and the driver picked the wrong one every time.
     */
    private var modalFrom = 0

    /** Called by [UiBuilder.sheet] right after its scrim: everything already emitted is now covered. */
    internal fun markModalBarrier() { modalFrom = (clicks.size - 1).coerceAtLeast(0) }
    /** A hold-to-repeat stepper button: fires `onStep(sign)` on press, then `onStep(sign·magnitude)` while held. */
    internal fun emitStepper(x: Float, y: Float, w: Float, h: Float, sign: Int, onStep: (Int) -> Unit) {
        clicks.add(ClickRegion(x, y, w, h, { onStep(sign) }, holdSign = sign, onStep = onStep))
    }

    internal fun emitOverlayRect(x: Float, y: Float, w: Float, h: Float, color: Long) { overlayRects.add(RectCmd(x, y, w, h, color)) }
    internal fun emitOverlayTextLeft(text: String, x: Float, topY: Float, h: Float, color: Long) {
        overlayTexts.add(TextCmd(text, x, topY, h, color, centered = false, centerX = 0f))
    }
    /** An interactive region on the overlay layer (an open dropdown's rows), drawn above every panel.
     *
     *  Pass the row's own text as [label] wherever there is one: [elements] and [tapLabel] both key off it,
     *  so a label-less region is invisible to a headless driver **and untappable by name** — an open menu
     *  that a human can see and click but an agent cannot reach at all. (That was the case for every
     *  dropdown row until 2026-07-23.) Null stays allowed for genuine background/scrim catchers. */
    internal fun emitOverlayClick(x: Float, y: Float, w: Float, h: Float, label: String? = null, onClick: () -> Unit) {
        overlayClicks.add(ClickRegion(x, y, w, h, onClick, label = label))
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
        private const val INITIAL_DELAY = 0.35f   // hold this long before auto-repeat begins (and before a
                                                  // [longPressDrag] pickup — sharing the constant keeps the
                                                  // pickup and the "a long hold is not a click" cutoff at
                                                  // hitTestUp exactly aligned, so no hold is both)
        private const val SCROLL_SLOP = 2f        // px of movement before a press becomes a scroll
        private const val LONG_PRESS_SLOP = 12f   // dp a finger may wander and still be "held still"
        private const val AUTOSCROLL_EDGE = 56f   // dp from a list edge where a drag starts scrolling it
        private const val AUTOSCROLL_PX_PER_SEC = 700f   // dp/s at the very edge (ramped from 0)
    }
}

/** Frame-scoped builder: add panels. */
/** A trailing hover-reveal action on a [PanelBuilder.hoverRow] — a small glyph button (e.g. `+`, `X`) that
 *  appears only while the row is hovered. [label] is for headless targeting ([Ui.tapLabel]). */
class HoverAction(val glyph: String, val color: Long, val label: String, val onClick: () -> Unit)

/**
 * The drawing surface behind [UiBuilder.canvas]: rectangles, centred text and click boxes at absolute pixel
 * coordinates. Everything is px; scale your own dp sizes by [density].
 */
class CanvasBuilder internal constructor(private val ui: Ui) {
    val screenW: Float get() = ui.resWidth
    val screenH: Float get() = ui.resHeight
    val density: Float get() = ui.scale

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Long) = ui.emitRect(x, y, w, h, color)

    /** A textured quad — one draw call regardless of the source texture's resolution, with GPU bilinear
     *  filtering doing the smoothing (set on the texture itself, e.g.
     *  [org.emerge.render.torus.GPU.configureTexture2DClampLinear]). The uv rect need not be [0,1] —
     *  panning/zooming is just moving [uvMinX]..[uvMaxY].
     *
     *  [uvCos]/[uvSin] turn the sampled rect about its own middle, which is how a map rotates without
     *  its UVs being rebuilt every frame; [round] clips the quad to its inscribed ellipse, leaving what
     *  is behind the corners untouched rather than darkened. Both are identity by default, so a caller
     *  that wants a plain square quad writes what it always did. */
    fun image(
        x: Float, y: Float, w: Float, h: Float,
        textureId: Int,
        uvMinX: Float = 0f, uvMinY: Float = 0f, uvMaxX: Float = 1f, uvMaxY: Float = 1f,
        uvCos: Float = 1f, uvSin: Float = 0f, round: Boolean = false,
    ) = ui.emitImage(x, y, w, h, textureId, uvMinX, uvMinY, uvMaxX, uvMaxY, uvCos, uvSin, round)

    /** Text centred horizontally on [centerX], its top at [topY], [height] px tall. */
    fun label(text: String, centerX: Float, topY: Float, height: Float, color: Long) =
        ui.emitTextCentered(text, centerX, topY, height, color)

    /** A clickable region with no appearance of its own — pair it with [rect]/[label]. [label] names it for
     *  the agent harness's `tap-ui` (see `Ui.tapLabel`), so give it the text the player reads. */
    fun clickable(x: Float, y: Float, w: Float, h: Float, label: String? = null, onClick: () -> Unit) =
        ui.emitClick(x, y, w, h, label, onClick = onClick)

    /** A filled box with centred text, optionally clickable — the common case, in one call. */
    fun box(
        x: Float, y: Float, w: Float, h: Float, color: Long,
        text: String? = null, textColor: Long = 0xFFFFFFFFL, textHeight: Float = h * 0.4f,
        onClick: (() -> Unit)? = null,
    ) {
        rect(x, y, w, h, color)
        if (text != null) label(text, x + w / 2f, y + (h - textHeight) / 2f, textHeight, textColor)
        if (onClick != null) clickable(x, y, w, h, text, onClick)
    }
}

/** A token in an inline sentence row ([PanelBuilder.tokenLines], `apps/cyto/UI_REDESIGN.md` §8a): either
 *  static prose or an interactive control that reads as a word. Spacing lives in the [Text] tokens (their
 *  leading/trailing spaces), so a control sits flush against the words around it. */
sealed class UiTok {
    /** Static prose. Include the surrounding spaces here (e.g. `"WHEN "`, `" GRADIENT"`). */
    class Text(val text: String, val color: Long = 0x9A9A9AFFL) : UiTok()
    /** A boxed word that fires [onClick] on click (the caller cycles a binary/small choice) — comparator,
     *  orient, sever, keep. Reads as an editable word; no dropdown. */
    class Toggle(val value: String, val color: Long, val key: String? = null, val onClick: () -> Unit) : UiTok()
    /** A boxed word + `v` that opens an inline dropdown (overlay, edge-aware) of [options] — action, operand,
     *  source, morphogen, efficiency, group. [open]/[onToggle] drive the list; [onPick] selects. */
    class Menu(
        val value: String, val color: Long, val options: List<String>, val open: Boolean,
        val key: String? = null,
        val onToggle: () -> Unit, val onPick: (Int) -> Unit,
    ) : UiTok()
}

class UiBuilder internal constructor(private val ui: Ui) {
    /** Framebuffer size (px) and the dp→px scale, so callers can compute wide-layout container bounds
     *  (a docked column, a centred popover) from the screen. */
    val screenW: Float get() = ui.resWidth
    val screenH: Float get() = ui.resHeight
    val density: Float get() = ui.scale

    /** Whether the cursor is over [x],[y],[w],[h] this frame (see [Ui.isHovered]) — for call-site
     *  hover-reveal. Always false on touch hosts. */
    fun isHovered(x: Float, y: Float, w: Float, h: Float): Boolean = ui.isHovered(x, y, w, h)

    /** Where the widget `tap-ui <label>` would hit is (see [Ui.element]) — for pointing at one. Sees only
     *  what has been laid out so far this frame. */
    fun element(label: String, occurrence: Int = 1): Ui.UiElement? = ui.element(label, occurrence)

    /** See [Ui.elementByKey] — the identity lookup, independent of displayed text. */
    fun elementByKey(key: String): Ui.UiElement? = ui.elementByKey(key)

    /** The most recently emitted panel's rect (see [Ui.lastPanelRect]). */
    val lastPanelRect: Ui.UiElement? get() = ui.lastPanelRect

    /** The host's animation clock — see [Ui.clockSeconds]. */
    val clockSeconds: Float get() = ui.clockSeconds

    /** Whether a card drag (drag-and-drop) is live this frame (see [Ui.isDragging]). */
    val isDragging: Boolean get() = ui.isDragging

    /** Require a long press to pick up a draggable card (see [Ui.longPressDrag]). Set from the layout at
     *  build time, so one call site governs every host rather than each deciding for itself. */
    var longPressDrag: Boolean
        get() = ui.longPressDrag
        set(v) { ui.longPressDrag = v }
    /** The id of the drag source being dragged, or null when no committed drag. */
    val draggingId: String? get() = ui.draggingId
    /** The drop-target id currently under the drag pointer, or null — for highlighting. */
    fun hoveredDropTarget(): String? = ui.hoveredDropTarget()
    /** Draw a floating ghost chip [label] at the drag pointer (overlay layer). Call while [isDragging]. */
    fun dragGhost(label: String, color: Long = 0x2E4A6EFFL) = ui.drawDragGhost(label, color)

    /**
     * An auto-sized panel anchored to a screen [anchor] corner. Panels at the same anchor **stack**
     * (each below the previous, [margin] apart).
     *
     * **All sizes here are dp**, scaled to pixels by [Ui.scale] (1.0 on desktop, so dp == px there).
     * [textSize] defaults to a fixed ratio of [rowHeight] — the historical coupling — but is an
     * independent knob: touch layouts need a tall row (≥48dp) with *normal* text in it, which the ratio
     * can't express (a 48dp row would imply 33dp text, wider than a phone screen).
     *
     * [offsetX] nudges the panel off its anchor (dp, positive = right). For a panel that wants to centre on
     * something other than the whole screen: a **centre anchor centres on the screen**, which is the wrong
     * centre when a docked column owns part of it, and the anchor alone cannot express "centred in what's
     * left". The caller knows what it is avoiding; this lets it say so without reimplementing placement.
     */
    fun panel(
        anchor: Anchor,
        margin: Float = 12f,
        padding: Float = 8f,
        background: Long = 0x000000C0,
        rowHeight: Float = 18f,
        textSize: Float = rowHeight * TEXT_TO_ROW_RATIO,
        newColumn: Boolean = false,
        fillWidth: Boolean = false,
        /** Floor on the auto-sized width, in dp — for a panel that has to line up with something
         *  beside it (a scroll area under it, a sibling column) rather than shrink to its own text.
         *  Ignored under [fillWidth], and never widens past the screen. */
        minWidth: Float = 0f,
        offsetX: Float = 0f,
        block: PanelBuilder.() -> Unit,
    ): Float {
        val s = ui.scale
        val marginPx = margin * s
        val paddingPx = padding * s
        val textH = textSize * s
        val pb = PanelBuilder(rowHeight * s, s).apply(block)
        if (pb.items.isEmpty()) return 0f
        // Panels never grow past the screen (minus a margin each side): auto-sized to content, or [fillWidth]
        // to span the whole width. Content wider than that is clipped in [emitPanel].
        val maxW = (ui.resWidth - marginPx * 2f).coerceAtLeast(paddingPx * 2f)
        val contentH = pb.items.sumOf { it.height.toDouble() }.toFloat()
        val w = if (fillWidth) maxW else {
            minOf(maxOf(paddingPx * 2 + pb.items.maxOf { it.measureWidth(textH) }, minWidth * s), maxW)
        }
        val contentW = w - paddingPx * 2
        val h = paddingPx * 2 + contentH
        if (anchor == Anchor.Center) {
            val x = (ui.resWidth - w) * 0.5f + offsetX * s
            val stack = ui.nextPanelOffset(anchor, 0f, h, marginPx)  // 0 for the first, then h+margin for extras
            val y = (ui.resHeight - h) * 0.5f + stack
            emitPanel(x, y, w, h, paddingPx, contentW, textH, background, pb)
            return h
        }
        if (anchor == Anchor.BottomCenter) {
            // Centred horizontally, anchored a [margin] gap above the bottom edge; extra panels stack upward.
            val x = (ui.resWidth - w) * 0.5f + offsetX * s
            val stack = ui.nextPanelOffset(anchor, marginPx, h, marginPx)
            val y = ui.resHeight - h - stack
            emitPanel(x, y, w, h, paddingPx, contentW, textH, background, pb)
            return h
        }
        if (newColumn) ui.startNewColumn(anchor, marginPx)             // a fresh column beside the previous one
        val inset = ui.columnInset(anchor, marginPx)                   // horizontal base for this column
        val offset = ui.nextPanelOffset(anchor, marginPx, h, marginPx) // vertical stack distance from the anchored edge
        val x = (when (anchor) {
            Anchor.TopRight, Anchor.BottomRight -> ui.resWidth - inset - w
            else -> inset
        }) + offsetX * s
        val y = when (anchor) {
            Anchor.BottomLeft, Anchor.BottomRight -> ui.resHeight - offset - h
            else -> offset
        }
        ui.growColumn(anchor, inset + w)
        emitPanel(x, y, w, h, paddingPx, contentW, textH, background, pb)
        return h
    }

    /** Emit a panel's background + click-catcher + rows at an already-resolved (x, y). */
    private fun emitPanel(
        x: Float, y: Float, w: Float, h: Float, padding: Float,
        contentW: Float, textH: Float, background: Long, pb: PanelBuilder,
    ) {
        ui.notePanelRect(x, y, w, h)
        ui.emitRect(x, y, w, h, background)
        // The panel background absorbs taps so a press on the panel (not just its buttons) doesn't fall
        // through to the world behind it. Registered BEFORE the items, so each button — added after — still
        // wins in hitTest's reverse-order scan; this no-op only catches presses on the empty panel area.
        ui.emitClick(x, y, w, h) {}
        // Clip the rows to the panel so content wider than the (screen-clamped) width can't spill off-screen.
        ui.pushClip(x, y, w, h)
        var rowY = y + padding
        for (item in pb.items) {
            item.emit(ui, x + padding, rowY, contentW, textH)
            rowY += item.height
        }
        ui.clearClip()
    }

    /** A full-screen fill — a backdrop for a title screen / modal menu. Emit it **first** (it draws behind
     *  later panels) — it also swallows any click that misses a widget so the scene behind doesn't react. */
    fun background(color: Long) {
        ui.emitRect(0f, 0f, ui.resWidth, ui.resHeight, color)
        ui.emitClick(0f, 0f, ui.resWidth, ui.resHeight) {}
    }

    /**
     * A **free-placement** drawing scope, in raw pixels — the escape hatch from the stacked-row layout every
     * other widget here is built on.
     *
     * For content whose shape is the point rather than a consequence: a graph, a map, a chart. Those cannot be
     * expressed as a column of rows at all, and faking one out of nested panels puts layout arithmetic in the
     * caller anyway, only obscured. So the scope is deliberately thin — rectangles, text and click boxes at
     * coordinates you compute — and everything above it (padding, stacking, hit-slop) stays the row layout's
     * job.
     *
     * Emitted in call order into the same command stream as the panels around it, so draw it BEFORE the panel
     * that should sit on top. Coordinates are px, not dp: a caller placing things itself needs the real screen,
     * and [density] is there to scale its own sizes with.
     */
    fun canvas(block: CanvasBuilder.() -> Unit) = CanvasBuilder(ui).block()

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
     * A **modal** — the host for L3 (`apps/cyto/UI_REDESIGN.md` §3): a fixed title bar (back chevron + title +
     * optional `...` overflow), a fixed bottom bar of [actions], and a **scrolling body** clipped between
     * them. The body is a [scrollArea], so content taller than the box scrolls under the bars.
     *
     * Fills its **bounds** — full-screen by default (narrow layout), or an explicit ([boundsX],[boundsY],
     * [boundsW],[boundsH]) box (wide layout: a docked column beside the cell panel, with the world still
     * visible around it). Draw order makes the bars occlude scrolled content. [id] keys the scroll offset;
     * all dp sizes × [Ui.scale]. [statusBar] reserves a phone's notch inset (full-screen only).
     */
    fun modal(
        id: String,
        title: String,
        onBack: () -> Unit,
        actions: List<Triple<String, Long, () -> Unit>> = emptyList(),
        onOverflow: (() -> Unit)? = null,
        boundsX: Float = Float.NaN, boundsY: Float = Float.NaN, boundsW: Float = Float.NaN, boundsH: Float = Float.NaN,
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
        val x0 = if (boundsX.isNaN()) 0f else boundsX
        val y0 = if (boundsY.isNaN()) 0f else boundsY
        val bw = if (boundsW.isNaN()) ui.resWidth else boundsW
        val bh = if (boundsH.isNaN()) ui.resHeight else boundsH

        // Backdrop: fills the bounds + swallows any tap that misses a widget.
        ui.emitRect(x0, y0, bw, bh, background)
        ui.emitClick(x0, y0, bw, bh) {}

        // Body viewport, between the bars — emitted before the chrome so the bars draw over it.
        val viewTop = y0 + statusPx + titlePx
        val viewH = bh - statusPx - titlePx - bottomPx
        scrollArea(id, x0, viewTop, bw, viewH, padding = margin, rowHeight = rowHeight, textSize = textSize, block = body)

        // Title bar (drawn on top of the body).
        val titleY = y0 + statusPx
        ui.emitRect(x0, titleY, bw, titlePx, barColor)
        val titleMid = titleY + (titlePx - textH) * 0.5f
        ui.emitTextLeft("<", x0 + marginPx, titleMid, textH, 0xAACCFFFFL)
        ui.emitClick(x0, titleY, titlePx, titlePx, label = "back", onClick = onBack)
        ui.emitTextLeft(title, x0 + marginPx + textH * 1.6f, titleMid, textH, 0xFFFFFFFFL)
        if (onOverflow != null) {
            ui.emitTextLeft("...", x0 + bw - marginPx - textH * 1.5f, titleMid, textH, 0xAACCFFFFL)
            ui.emitClick(x0 + bw - titlePx, titleY, titlePx, titlePx, label = "overflow", onClick = onOverflow)
        }

        // Bottom action bar.
        if (actions.isNotEmpty()) {
            val by = y0 + bh - bottomPx
            ui.emitRect(x0, by, bw, bottomPx, barColor)
            val n = actions.size
            val btnH = (bottomPx - marginPx).coerceAtLeast(textH * 1.5f)
            val btnY = by + (bottomPx - btnH) * 0.5f
            val btnW = (bw - marginPx * (n + 1)) / n
            for ((i, a) in actions.withIndex()) {
                val bx = x0 + marginPx + i * (btnW + marginPx)
                ui.emitRect(bx, btnY, btnW, btnH, a.second)
                ui.emitTextCentered(a.first, bx + btnW * 0.5f, btnY + (btnH - textH) * 0.5f, textH, contrast(a.second))
                ui.emitClick(bx, btnY, btnW, btnH, label = a.first, onClick = a.third)
            }
        }
    }

    /**
     * A **bottom sheet** — the narrow-layout host for L4 field pickers (`apps/cyto/UI_REDESIGN.md` §3–4),
     * stacked over an open [modal]. A dimming scrim covers the screen (tap it, or the title-bar `X`, to
     * [onDismiss]); the sheet itself is a bottom-anchored panel of height [heightFraction] × screen, with a
     * title bar and a **scrolling body** (so a long option list — every energy source, every action — fits).
     *
     * Emit it **after** the modal in the same frame: its scrim + panel then draw on top, and its click
     * regions win the reverse-order hit-test. [id] keys the body scroll offset.
     */
    fun sheet(
        id: String,
        title: String,
        onDismiss: () -> Unit,
        heightFraction: Float = 0.6f,
        boxX: Float = Float.NaN, boxY: Float = Float.NaN, boxW: Float = Float.NaN, boxH: Float = Float.NaN,
        titleBar: Float = 56f,
        margin: Float = 16f,
        scrim: Long = 0x000000AAL,
        background: Long = 0x121722FFL,
        barColor: Long = 0x1B2230FFL,
        padding: Float = 8f,
        rowHeight: Float = 18f,
        textSize: Float = rowHeight * TEXT_TO_ROW_RATIO,
        body: PanelBuilder.() -> Unit,
    ) {
        val s = ui.scale
        val fullW = ui.resWidth
        val fullH = ui.resHeight
        val marginPx = margin * s
        val titlePx = titleBar * s
        val textH = textSize * s
        // Box: bottom-anchored full width by default (narrow); or an explicit box (wide: a centred popover).
        val bw = if (boxW.isNaN()) fullW else boxW
        val bh = if (boxH.isNaN()) fullH * heightFraction.coerceIn(0.2f, 1f) else boxH
        val bx = if (boxX.isNaN()) 0f else boxX
        val by = if (boxY.isNaN()) fullH - bh else boxY

        // Scrim over the whole screen: dims + dismisses on tap.
        ui.emitRect(0f, 0f, fullW, fullH, scrim)
        ui.emitClick(0f, 0f, fullW, fullH, label = "scrim", onClick = onDismiss)
        ui.markModalBarrier()   // the card/panel behind a scrim is unreachable — see [Ui.markModalBarrier]
        // Sheet surface + a tap-swallow so a press on the sheet body doesn't reach the scrim behind it.
        ui.emitRect(bx, by, bw, bh, background)
        ui.emitClick(bx, by, bw, bh) {}
        // Title bar with a close affordance.
        ui.emitRect(bx, by, bw, titlePx, barColor)
        val ty = by + (titlePx - textH) * 0.5f
        ui.emitTextLeft(title, bx + marginPx, ty, textH, 0xFFFFFFFFL)
        ui.emitTextLeft("X", bx + bw - marginPx - textH, ty, textH, 0xAACCFFFFL)
        ui.emitClick(bx + bw - titlePx, by, titlePx, titlePx, label = "close", onClick = onDismiss)
        // Scrolling body.
        scrollArea(id, bx, by + titlePx, bw, bh - titlePx, padding = margin, rowHeight = rowHeight, textSize = textSize, block = body)
    }

    /**
     * A **docked bottom sheet** — a persistent, scrollable panel filling the bottom [heightFraction] of the
     * screen, full width, with **no scrim** so the world above stays live (the narrow-layout host for the L2
     * cell view, `apps/cyto/UI_REDESIGN.md` §3). Unlike [sheet] it doesn't dim or dismiss on an outside tap;
     * a tap above it falls through to the world (deselecting the cell dismisses it naturally). [id] keys the
     * scroll offset; sizes are dp (× [Ui.scale]).
     */
    fun dockBottom(
        id: String,
        heightFraction: Float = 0.58f,
        background: Long = 0x121722F2L,
        padding: Float = 12f,
        rowHeight: Float = 18f,
        textSize: Float = rowHeight * TEXT_TO_ROW_RATIO,
        block: PanelBuilder.() -> Unit,
    ) {
        val fullW = ui.resWidth
        val fullH = ui.resHeight
        val h = fullH * heightFraction.coerceIn(0.15f, 1f)
        scrollArea(id, 0f, fullH - h, fullW, h, padding = padding, rowHeight = rowHeight, textSize = textSize, background = background, block = block)
    }

    /** A **right-docked panel** — the wide-layout host for the L2 cell view: a fixed-[width] (dp) scrollable
     *  column pinned to the right edge, full height minus [margin] (dp), over the live world. The
     *  wide-screen counterpart of [dockBottom]. Returns the panel's left x (px) so a caller can dock a second
     *  column (the L3 gene editor) beside it. */
    fun dockRight(
        id: String,
        width: Float = 300f,
        margin: Float = 12f,
        background: Long = 0x121722F2L,
        padding: Float = 10f,
        rowHeight: Float = 18f,
        textSize: Float = rowHeight * TEXT_TO_ROW_RATIO,
        block: PanelBuilder.() -> Unit,
    ): Float {
        val s = ui.scale
        val m = margin * s
        val w = width * s
        val x = ui.resWidth - w - m
        scrollArea(id, x, m, w, ui.resHeight - m * 2f, padding = padding, rowHeight = rowHeight, textSize = textSize, background = background, block = block)
        return x
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
        val pb = PanelBuilder(rowHeight * s, s).apply(block)
        drawScroll(id, pb, x, y, w, h, padding * s, textSize * s, background)
    }

    /**
     * A [scrollArea] that **hangs from its bottom edge and is only as tall as it needs to be**, up to
     * [maxH]. Returns the top edge it settled on, so a caller can stack something directly above it.
     *
     * A panel auto-sizes to its content and cannot scroll; a scroll area scrolls and must be told a
     * rectangle. This is the case in between, and it is the one a reference panel wants: an article
     * two lines long should be two lines tall sitting on whatever is below it, and one twenty lines
     * long should fill the space it has and scroll inside it. Given a fixed rectangle the short
     * article is two lines of text in a tall empty box, which reads as a panel that has failed to
     * load rather than as a short answer.
     *
     * ⚠️ **Upward is the whole point.** Anchoring the top and shrinking the bottom is [scrollArea]
     * with a smaller `h`, which any caller can already do; what cannot be done from outside is
     * *growing away from a fixed edge*, because that needs the content measured before the rectangle
     * is known and the measurement is this module's business.
     */
    fun scrollAreaAbove(
        id: String,
        x: Float, bottom: Float, w: Float, maxH: Float,
        padding: Float = 8f,
        rowHeight: Float = 18f,
        textSize: Float = rowHeight * TEXT_TO_ROW_RATIO,
        background: Long = 0x00000000,
        block: PanelBuilder.() -> Unit,
    ): Float {
        val s = ui.scale
        val paddingPx = padding * s
        val pb = PanelBuilder(rowHeight * s, s).apply(block)
        val contentH = pb.items.sumOf { it.height.toDouble() }.toFloat() + paddingPx * 2
        val h = minOf(contentH, maxOf(maxH, 0f))
        val y = bottom - h
        drawScroll(id, pb, x, y, w, h, paddingPx, textSize * s, background)
        return y
    }

    /** The half of [scrollArea] that draws, once the rectangle is settled. */
    private fun drawScroll(
        id: String,
        pb: PanelBuilder,
        x: Float, y: Float, w: Float, h: Float,
        paddingPx: Float,
        textH: Float,
        background: Long,
    ) {
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

    /**
     * A read-only line of text.
     *
     * [key] gives the row a stable identity ([Ui.elementByKey]) for a caller that must not depend on the
     * words in it — a data row's text is the data, so it changes every tick. [spans] name **parts** of the
     * line the same way: a column in a fixed-width table is a character range, and pointing at the number
     * rather than the whole row is the difference between "look here" and "look somewhere on this line".
     * Character offsets only make sense against the monospace renderer, which is what panels use.
     */
    fun text(text: String, color: Long = 0xC8C8C8FFL, key: String? = null, spans: List<TextSpan> = emptyList()) =
        items.add(TextItem(text, color, rowHeight, key, spans))

    /** A named character range `[from, to)` within a [text]'s text. */
    class TextSpan(val key: String, val from: Int, val to: Int)
    fun keyValue(key: String, value: String, keyColor: Long = 0x9A9A9AFFL, valueColor: Long = 0xFFFFFFFFL) =
        items.add(KeyValueItem(key, value, keyColor, valueColor, rowHeight))
    /**
     * A button. [widthEm] pins its width to that many multiples of the text height instead of letting it
     * size to its label — what a *deliberately narrow* control in a [text] needs, so that the items
     * sharing the row with it don't lose room every time its text grows a character.
     */
    fun button(
        label: String, color: Long, dropTargetId: String? = null, enabled: Boolean = true,
        widthEm: Float = 0f, weight: Float = 0f, onClick: () -> Unit,
    ) = items.add(ButtonItem(label, color, rowHeight, dropTargetId, enabled, widthEm, onClick, weight))

    /** Place a prepared [ActionButton] — the same button, described as data rather than as arguments. */
    fun button(b: ActionButton) = items.add(ButtonItem(b.label, b.color, rowHeight, null, b.enabled, 0f, b.onClick))

    /** A button whose label is coloured **per segment**: each pair is (text, colour-or-null); a null
     *  colour uses the auto-contrast against the button [color]. The segments render as one centred label
     *  (e.g. to highlight just the blocking parts of a gene in orange). */
    fun button(spans: List<Pair<String, Long?>>, color: Long, onClick: () -> Unit) = items.add(SpanButtonItem(spans, color, rowHeight, onClick))

    /** A **left-aligned gene card** (`apps/cyto/UI_REDESIGN.md` §3, L2): one row per entry in [lines], each a
     *  list of (text, colour-or-null) segments (null → auto-contrast against [color]). Typically the gene's
     *  condition clauses one-per-line plus an action line. Unlike [button] it never centres and never widens
     *  the panel — long lines clip at the panel's scissor rather than pushing it past the screen. */
    /** A read-only multi-line card (the narrow layout's gene). Give it [dragId]/[onDrop] to make the whole
     *  card a drag source, as [tokenRow] is on desktop — with [Ui.longPressDrag] on, picking it up takes a
     *  hold, so a plain tap still opens it and a swipe still scrolls the list. */
    fun geneCard(
        lines: List<List<Pair<String, Long?>>>, color: Long,
        dragId: String? = null, onDrop: ((String?) -> Unit)? = null,
        onClick: () -> Unit,
    ) = items.add(GeneCardItem(lines, color, rowHeight * lines.size.coerceAtLeast(1), dragId, onDrop, onClick))

    /** A left-aligned label row that reveals trailing [actions] (glyph buttons) **only while the cursor is
     *  over it** — desktop hover-reveal (`apps/cyto/UI_REDESIGN.md` §8a). On touch (no hover) the actions
     *  never show, so the row reads as plain text. */
    fun hoverRow(text: String, textColor: Long = 0xE0E6F0FFL, actions: List<HoverAction>) =
        items.add(HoverRowItem(text, textColor, rowHeight, actions))

    /** An **inline sentence** of [lines], each a run of [UiTok]s (static prose + interactive Toggle/Menu
     *  tokens), wrapping within [wrapWidth] dp so a long clause never clips (`apps/cyto/UI_REDESIGN.md`
     *  §8a — the desktop interactive gene card). [wrapWidth] is the caller's known content width (e.g. a
     *  fixed-width dock); [textSize] is dp. Continuation rows indent by [indent] dp. */
    /** [alwaysShowActions] draws [lineActions]/[cardActions] unconditionally instead of on hover — required on
     *  touch, where there is no hover and a hover-gated affordance simply does not exist. Shown actions also
     *  reserve their width from the wrap, so tokens never run underneath them. */
    fun tokenLines(
        lines: List<List<UiTok>>, wrapWidth: Float, textSize: Float, indent: Float = 10f,
        background: Long = 0x00000000L,
        lineActions: List<List<HoverAction>> = emptyList(), cardActions: List<HoverAction> = emptyList(),
        dragId: String? = null, onDrop: ((String?) -> Unit)? = null,
        alwaysShowActions: Boolean = false,
    ) = items.add(TokenRowItem(lines, wrapWidth * scale, textSize * scale, rowHeight, indent * scale, background, lineActions, cardActions, dragId, onDrop, alwaysShowActions))

    /** Vertical space, in dp. */
    fun gap(height: Float = 6f) = items.add(GapItem(height * scale))

    /** A thin **insertion drop slot** [id] (drag-and-drop reorder): a small-height drop target that draws a
     *  faint line, brightening when the drag pointer is over it — the "drop here to place it between these
     *  two" affordance. [height] is dp. */
    fun dropSlot(id: String, height: Float = 10f) = items.add(DropSlotItem(id, height * scale))

    /** A label + a click-to-expand dropdown field showing [value]; when [open], its [options] render in
     *  the overlay layer and a pick calls [onPick]. [onToggle] opens/closes the dropdown. */
    fun picker(label: String, value: String, options: List<String>, open: Boolean, onToggle: () -> Unit, onPick: (Int) -> Unit) =
        items.add(PickerItem(label, value, options, open, onToggle, onPick, rowHeight))

    /** A **chip-styled inline dropdown** for the desktop inline editor (`apps/cyto/UI_REDESIGN.md` §8a): like
     *  [chip] but the value list drops in place (overlay layer), flipping up near the screen bottom and
     *  hidden when scrolled out of view. An empty [label] makes the field full-width. */
    fun dropdown(label: String, value: String, options: List<String>, open: Boolean, onToggle: () -> Unit, onPick: (Int) -> Unit) =
        items.add(DropdownItem(label, value, options, open, onToggle, onPick, rowHeight))

    /** A label + `[-] value [+]` where ± are hold-to-repeat steppers calling [onStep] with a signed,
     *  accelerating magnitude. */
    fun stepper(label: String, value: String, onStep: (Int) -> Unit) = items.add(StepperItem(label, value, onStep, rowHeight))

    /** A horizontal row of small buttons. */
    fun actionRow(buttons: List<Triple<String, Long, () -> Unit>>) =
        controlRow(buttons.map { ActionButton(it.first, it.second, onClick = it.third) })

    /** A horizontal row of [ActionButton]s — like [actionRow] but each button may be individually disabled
     *  (dimmed + non-interactive). Sugar for `row { buttons.forEach { button(it) } }`. */
    fun controlRow(buttons: List<ActionButton>) = row { buttons.forEach { button(it) } }

    /**
     * A horizontal row of arbitrary items — the toolkit's one and only horizontal container. Anything
     * that can go in a panel can go in a row, including another [row]; [spacer] absorbs the slack.
     *
     * ⚠️ An **empty** row adds nothing at all rather than a zero-height item, because a caller that
     * computes its children (`actionRow(buttons)`) legitimately produces none, and a row of nothing
     * has no height to report.
     */
    fun row(gapPx: Float? = null, block: PanelBuilder.() -> Unit) {
        val nested = PanelBuilder(rowHeight, scale)
        nested.block()
        if (nested.items.isEmpty()) return
        items.add(RowItem(nested.items, nested.items.maxOf { it.height }, gapPx?.let { it * scale }))
    }

    /**
     * Blank, flexible width: measures [minEm] characters — **zero** by default, so it never widens the
     * panel — then absorbs whatever slack the panel turned out to have. Two of them centre what's
     * between; one pushes the rest right.
     *
     * [minEm] is the *guaranteed* separation, in multiples of the text height: a label and the control
     * it names must not touch when the panel happens to size itself exactly to that row, and only the
     * gap itself knows how much room that needs.
     */
    fun spacer(minEm: Float = 0f) = items.add(SpacerItem(minEm))

    private class SpacerItem(val minEm: Float) : Item {
        override val height = 0f          // never inflates the row's height
        override val weight = 1f          // a spacer is simply the emptiest weighted item there is
        override fun measureWidth(textH: Float) = minEm * textH
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) = Unit
    }

    /**
     * A **chip** — a tappable current value, the workhorse of a progressive-disclosure screen: it *shows*
     * the value and *opens* the editor for it (`apps/cyto/UI_REDESIGN.md` §3). An empty [label] makes it
     * full-width (`[ DIVIDE (MITOSIS) v ]`); otherwise it's `LABEL        [ value v ]`.
     */
    fun chip(label: String, value: String, color: Long = 0x2A3550FFL, weight: Float = 0f, onTap: () -> Unit) =
        items.add(ChipItem(label, value, color, rowHeight, weight, onTap))

    /**
     * A **segmented control** — 2–3 exclusive options, chosen inline with no drill-down (`> / <`,
     * `along / across`, `yes / no`). Segments size to the widest option: a fixed width silently clips
     * longer labels ("DAUGHTER"), which the design mock caught.
     */
    fun segmented(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
        // 2px rather than the row's usual half-character: segments touching is what makes a set of
        // them read as one control instead of as loose buttons.
        row(gapPx = 2f) {
            if (label.isNotEmpty()) text(label, 0x9A9A9AFFL)
            spacer(minEm = 1f)
            for ((i, opt) in options.withIndex()) segment(opt, options, i == selected) { onSelect(i) }
        }

    /** One segment of a [segmented] set. It carries the whole [set] because its width is a property of
     *  the *set*, not of its own text — every segment is as wide as the longest option. */
    private fun segment(opt: String, set: List<String>, on: Boolean, onSelect: () -> Unit) =
        items.add(SegmentItem(opt, set, on, rowHeight, onSelect))

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
        row {
            // Weight 1 apiece: the operands stay equal and the comparator stays put, however long the
            // chosen species' name happens to be.
            chip("", lhs, 0x2A3550FFL, weight = 1f, onTap = onLhs)
            button(cmp, 0x35507AFFL, widthEm = 3f, onClick = onCmp)
            chip("", rhs, 0x2A3550FFL, weight = 1f, onTap = onRhs)
        }

    /** A **grab handle** — a centred pill that resizes its container by dragging: [onDrag] gets the vertical
     *  pixel delta per move, [onRelease] fires once the gesture ends (snap to a detent there), and a mere tap
     *  fires [onTap]. Used for the L2 sheet's peek↔full drag. [id] identifies the gesture + labels the tap. */
    fun dragHandle(id: String, onTap: () -> Unit, onDrag: (Float) -> Unit, onRelease: () -> Unit) =
        items.add(DragHandleItem(id, rowHeight * 0.7f, onTap, onDrag, onRelease))

    /** A **drag card** — a fixed-[heightPx] surface that is entirely a [dragHandle]: a grab pill on top plus a
     *  few centred info [lines], and the whole rect drags ([onDrag]/[onRelease]) or taps ([onTap]). Sized to
     *  fill its container so nothing scrolls — the collapsed L1 peek, where the tiny content shouldn't scroll
     *  and a drag anywhere (not just the pill) should expand the sheet. [heightPx] is already in pixels. */
    fun dragCard(id: String, heightPx: Float, lines: List<Pair<String, Long>>, onTap: () -> Unit, onDrag: (Float) -> Unit, onRelease: () -> Unit) =
        items.add(DragCardItem(id, heightPx, lines, onTap, onDrag, onRelease))

    internal interface Item {
        val height: Float

        /**
         * How much of a [text]'s **leftover** width this item claims, `0` meaning "just my natural width".
         *
         * ⚠️ A weight *replaces* the natural width rather than adding to it: two weight-1 chips come out
         * the same size whatever their text, which is the whole point of [clauseRow] — an operand that
         * resized as you picked a longer species would slide the comparator out from under your finger.
         * The natural width still counts toward [measureWidth], so a panel is never sized smaller than
         * the words it has to show.
         */
        val weight: Float get() = 0f

        fun measureWidth(textH: Float): Float
        fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float)
    }

    private class TextItem(
        val text: String,
        val color: Long,
        override val height: Float,
        val key: String? = null,
        val spans: List<TextSpan> = emptyList(),
    ) : Item {
        override fun measureWidth(textH: Float) = UiTextRenderer.measureWidthPx(text, textH)
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            ui.emitTextLeft(text, x, topY + (height - textH) * 0.5f, textH, color)
            ui.noteReadout(text, x, topY, contentW, height, key)
            for (s in spans) {
                val from = s.from.coerceIn(0, text.length)
                val to = s.to.coerceIn(from, text.length)
                val left = UiTextRenderer.measureWidthPx(text.substring(0, from), textH)
                val w = UiTextRenderer.measureWidthPx(text.substring(from, to), textH)
                // Labelled with the span's own text so `elements`/`readouts` stay readable, but found by key.
                ui.noteReadout(text.substring(from, to).trim(), x + left, topY, w, height, s.key)
            }
        }
    }

    private class KeyValueItem(val key: String, val value: String, val keyColor: Long, val valueColor: Long, override val height: Float) : Item {
        override fun measureWidth(textH: Float): Float =
            UiTextRenderer.measureWidthPx(key, textH) + textH * 2f + UiTextRenderer.measureWidthPx(value, textH)
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            ui.emitTextLeft(key, x, ty, textH, keyColor)
            ui.emitTextLeft(value, x + contentW - UiTextRenderer.measureWidthPx(value, textH), ty, textH, valueColor)
            // Named by its key, not "LIGHT  4200": the value changes every tick, the key is what a coach means.
            ui.noteReadout(key, x, topY, contentW, height)
        }
    }

    private class ButtonItem(
        val label: String, val color: Long, override val height: Float, val dropTargetId: String?,
        val enabled: Boolean, val widthEm: Float, val onClick: () -> Unit, override val weight: Float = 0f
    ) : Item {
        override fun measureWidth(textH: Float) =
            if (widthEm > 0f) widthEm * textH else UiTextRenderer.measureWidthPx(label, textH) + textH * 2f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val inset = 1f
            // A drop target (drag-and-drop): register the button's rect and, when the drag pointer is over it,
            // lighten it so the player sees where the drop will land.
            val highlit = dropTargetId != null && ui.hoveredDropTarget() == dropTargetId
            if (dropTargetId != null) ui.emitDropTarget(dropTargetId, x, topY + inset, contentW, height - inset * 2f)
            ui.emitRect(x, topY + inset, contentW, height - inset * 2f, if (enabled) color else DISABLED_BG)
            if (highlit) ui.emitRect(x, topY + inset, contentW, height - inset * 2f, 0xFFFFFF44L)   // drop-hover tint
            ui.emitTextCentered(
                label, x + contentW * 0.5f, topY + (height - textH) * 0.5f, textH,
                if (enabled) contrast(color) else DISABLED_FG,
            )
            if (enabled) ui.emitClick(x, topY + inset, contentW, height - inset * 2f, label = label, onClick = onClick)
        }
        companion object { const val DISABLED_BG = 0x1E2430FFL; const val DISABLED_FG = 0x5A6272FFL }
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

    private class GeneCardItem(
        val lines: List<List<Pair<String, Long?>>>,
        val color: Long, override val height: Float,
        val dragId: String? = null, val onDrop: ((String?) -> Unit)? = null,
        val onClick: () -> Unit,
    ) : Item {
        private fun lineW(line: List<Pair<String, Long?>>, textH: Float) =
            line.fold(0f) { acc, s -> acc + UiTextRenderer.measureWidthPx(s.first, textH) }
        override fun measureWidth(textH: Float) = (lines.maxOfOrNull { lineW(it, textH) } ?: 0f) + textH * 1.5f
        private fun emitLine(ui: Ui, line: List<Pair<String, Long?>>, x: Float, ty: Float, textH: Float) {
            var sx = x
            for ((text, c) in line) {
                ui.emitTextLeft(text, sx, ty, textH, c ?: contrast(color))
                sx += UiTextRenderer.measureWidthPx(text, textH)
            }
        }
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val inset = 1f
            if (dragId != null && onDrop != null) ui.emitDragSource(dragId, x, topY, contentW, height, onDrop)
            // The card being carried dims in place, so the list still shows where it came from (and where it
            // will fall back to) while the ghost follows the finger.
            val bg = if (dragId != null && ui.draggingId == dragId) dim(color, 0.45f) else color
            ui.emitRect(x, topY + inset, contentW, height - inset * 2f, bg)
            val n = lines.size.coerceAtLeast(1)
            val lineH = (height - inset * 2f) / n
            val pad = textH * 0.6f
            lines.forEachIndexed { i, line ->
                emitLine(ui, line, x + pad, topY + inset + lineH * i + (lineH - textH) * 0.5f, textH)
            }
            val lbl = lines.flatten().joinToString("") { it.first }
            ui.emitClick(x, topY + inset, contentW, height - inset * 2f, label = lbl, onClick = onClick)
        }
    }

    /** A left-aligned label row that reveals trailing action buttons **only while hovered** (desktop
     *  hover-reveal, `apps/cyto/UI_REDESIGN.md` §8a — the per-clause `+`/`×`). Each action is (glyph, colour,
     *  label, onClick); the buttons lay out right-to-left at the row's right edge. On a touch host (no
     *  hover) the buttons never appear, so this row is desktop-only by construction. */
    private class HoverRowItem(
        val text: String, val textColor: Long, override val height: Float,
        val actions: List<HoverAction>,
    ) : Item {
        override fun measureWidth(textH: Float) =
            UiTextRenderer.measureWidthPx(text, textH) + textH * (1f + actions.size * 1.6f)
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            ui.emitTextLeft(text, x, ty, textH, textColor)
            if (!ui.isHovered(x, topY, contentW, height)) return
            val btn = height - 2f          // square, inset 1px top/bottom
            var bx = x + contentW - btn    // pack right-to-left
            for (a in actions) {
                ui.emitRect(bx, topY + 1f, btn, btn, a.color)
                ui.emitTextCentered(a.glyph, bx + btn * 0.5f, topY + 1f + (btn - textH) * 0.5f, textH, contrast(a.color))
                ui.emitClick(bx, topY + 1f, btn, btn, label = a.label, onClick = a.onClick)
                bx -= btn + textH * 0.4f
            }
        }
    }

    /** Renders [lines] of [UiTok]s as an inline sentence, **wrapping** within [wrapPx] (continuation rows
     *  indented by [indentPx]) so a long clause never clips (`apps/cyto/UI_REDESIGN.md` §8a). The wrap is
     *  computed at construction against the caller's known content width, so [height] is fixed before
     *  layout — the immediate-mode height-before-width constraint. Interactive tokens ([UiTok.Toggle]/
     *  [UiTok.Menu]) draw as boxed words with their own click regions; an open [UiTok.Menu] drops an
     *  edge-aware option list into the overlay layer. */
    private class TokenRowItem(
        val lines: List<List<UiTok>>, val wrapPx: Float, val textH: Float, val rowH: Float, val indentPx: Float,
        val background: Long, val lineActions: List<List<HoverAction>>, val cardActions: List<HoverAction>,
        val dragId: String? = null, val onDrop: ((String?) -> Unit)? = null,
        val alwaysShowActions: Boolean = false,
    ) : Item {
        private class Placed(val tok: UiTok, val dx: Float, val w: Float)
        private class VLine(val rows: List<List<Placed>>, val actions: List<HoverAction>)
        private val gap = textH * 0.15f
        /** Width a row of glyph buttons occupies, for both placement and the wrap reserve. */
        private fun actionsW(n: Int) = if (n == 0) 0f else n * (rowH - 2f + gap)
        /** The card-level buttons sit in the top-right corner, i.e. on the first line — which therefore has to
         *  yield that much more room than the others, or the two sets of buttons land on each other. */
        private val cardReserve = if (alwaysShowActions) actionsW(cardActions.size) else 0f
        private fun tokW(t: UiTok): Float = when (t) {
            is UiTok.Text -> UiTextRenderer.measureWidthPx(t.text, textH)
            is UiTok.Toggle -> UiTextRenderer.measureWidthPx(t.value, textH) + textH * 0.8f
            is UiTok.Menu -> UiTextRenderer.measureWidthPx(t.value, textH) + textH * 1.4f
        }
        private val vlines: List<VLine> = lines.mapIndexed { li, line ->
            val acts = lineActions.getOrElse(li) { emptyList() }
            // Permanently-visible buttons sit at the right edge, so they are not space the sentence can use.
            val limit = wrapPx - if (alwaysShowActions) actionsW(acts.size) + (if (li == 0) cardReserve else 0f) else 0f
            val rows = ArrayList<List<Placed>>()
            var cur = ArrayList<Placed>()
            var cx = 0f
            for (tok in line) {
                val w = tokW(tok)
                if (cur.isNotEmpty() && cx + w > limit) { rows.add(cur); cur = ArrayList(); cx = indentPx }
                cur.add(Placed(tok, cx, w)); cx += w + gap
            }
            if (cur.isNotEmpty()) rows.add(cur)
            VLine(rows, acts)
        }
        override val height = vlines.sumOf { it.rows.size } * rowH
        override fun measureWidth(textH: Float) = wrapPx

        private fun drawTok(ui: Ui, p: Placed, px: Float, ry: Float, ty: Float) { when (val t = p.tok) {
            is UiTok.Text -> ui.emitTextLeft(t.text, px, ty, textH, t.color)
            is UiTok.Toggle -> {
                ui.emitRect(px, ry + 1f, p.w, rowH - 2f, t.color)
                ui.emitTextCentered(t.value, px + p.w * 0.5f, ty, textH, contrast(t.color))
                ui.emitClick(px, ry + 1f, p.w, rowH - 2f, label = t.value, key = t.key, onClick = t.onClick)
            }
            is UiTok.Menu -> {
                ui.emitRect(px, ry + 1f, p.w, rowH - 2f, t.color)
                ui.emitTextLeft(t.value, px + textH * 0.35f, ty, textH, contrast(t.color))
                ui.emitTextLeft("v", px + p.w - textH * 0.8f, ty, textH, 0xAACCFFFFL)
                ui.emitClick(px, ry + 1f, p.w, rowH - 2f, label = t.value, key = t.key, onClick = t.onToggle)
                if (t.open && ui.isWithinClip(px, ry, p.w, rowH)) {
                    val ow = maxOf(p.w, (t.options.maxOfOrNull { UiTextRenderer.measureWidthPx(it, textH) } ?: 0f) + textH * 0.8f)
                    val listH = t.options.size * rowH
                    val below = ui.resHeight - (ry + rowH)
                    val flipUp = listH > below && ry > below
                    var oy = if (flipUp) ry - listH else ry + rowH
                    for ((i, opt) in t.options.withIndex()) {
                        ui.emitOverlayRect(px, oy, ow, rowH, 0x1A2233FFL)
                        ui.emitOverlayTextLeft(opt, px + textH * 0.35f, oy + (rowH - textH) * 0.5f, textH, if (opt == t.value) 0xFFE070FFL else 0xCFE0FFFFL)
                        ui.emitOverlayClick(px, oy, ow, rowH, label = opt) { t.onPick(i) }
                        oy += rowH
                    }
                }
            }
        } }

        /** Draw hover-reveal glyph buttons left-to-right starting at [startX], on the row at [ry]. */
        private fun drawActions(ui: Ui, actions: List<HoverAction>, startX: Float, ry: Float) {
            val btn = rowH - 2f
            var bx = startX
            for (a in actions) {
                ui.emitRect(bx, ry + 1f, btn, btn, a.color)
                ui.emitTextCentered(a.glyph, bx + btn * 0.5f, ry + 1f + (btn - textH) * 0.5f, textH, contrast(a.color))
                ui.emitClick(bx, ry + 1f, btn, btn, label = a.label, onClick = a.onClick)
                bx += btn + gap
            }
        }

        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textHIgnored: Float) {
            if (background != 0x00000000L) ui.emitRect(x, topY + 1f, contentW, height - 2f, background)
            // Whole-card drag source (drag-and-drop re-group): registered under the card rect. It doesn't
            // consume token clicks — a press that doesn't move is still a tap on whatever token it hit.
            if (dragId != null && onDrop != null) ui.emitDragSource(dragId, x, topY, contentW, height, onDrop)
            val beingDragged = dragId != null && ui.draggingId == dragId
            val cardHovered = cardActions.isNotEmpty() && (alwaysShowActions || ui.isHovered(x, topY, contentW, height))
            var ry = topY
            for ((li, vl) in vlines.withIndex()) {
                val lineTop = ry
                var lastEndX = x
                for (row in vl.rows) {
                    val ty = ry + (rowH - textH) * 0.5f
                    for (p in row) drawTok(ui, p, x + p.dx, ry, ty)
                    row.lastOrNull()?.let { lastEndX = x + it.dx + it.w }
                    ry += rowH
                }
                // Per-line affordances (the clause +/×). On a mouse they reveal on hover, right after the
                // line's last token; when always shown they pin to the right edge, in the width the wrap
                // reserved for them, so they hold one column instead of jittering with the sentence.
                if (vl.actions.isNotEmpty()) {
                    if (alwaysShowActions)
                        drawActions(ui, vl.actions, x + contentW - actionsW(vl.actions.size) - (if (li == 0) cardReserve else 0f), ry - rowH)
                    else if (ui.isHovered(x, lineTop, contentW, ry - lineTop)) drawActions(ui, vl.actions, lastEndX + gap * 2f, ry - rowH)
                }
            }
            // The card-level ⋮ sits at the top-right corner while any part of the card is hovered.
            if (cardHovered && !beingDragged) {
                val btn = rowH - 2f
                drawActions(ui, cardActions, x + contentW - cardActions.size * (btn + gap), topY)
            }
            // Dim the source card while it's being dragged (the ghost is the thing that moves).
            if (beingDragged) ui.emitRect(x, topY + 1f, contentW, height - 2f, 0x0E1420AAL)
        }
    }

    private class DragHandleItem(
        val id: String, override val height: Float,
        val onTap: () -> Unit, val onDrag: (Float) -> Unit, val onRelease: () -> Unit,
    ) : Item {
        override fun measureWidth(textH: Float) = textH * 6f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val pillW = maxOf(contentW * 0.16f, textH * 3f)
            val pillH = maxOf(3f, height * 0.28f)
            ui.emitRect(x + (contentW - pillW) * 0.5f, topY + (height - pillH) * 0.5f, pillW, pillH, 0x66748AFFL)
            ui.emitDragHandle(x, topY, contentW, height, id, onTap, onDrag, onRelease)
        }
    }

    private class DragCardItem(
        val id: String, override val height: Float, val lines: List<Pair<String, Long>>,
        val onTap: () -> Unit, val onDrag: (Float) -> Unit, val onRelease: () -> Unit,
    ) : Item {
        override fun measureWidth(textH: Float) =
            (lines.maxOfOrNull { UiTextRenderer.measureWidthPx(it.first, textH) } ?: (textH * 6f)) + textH
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val pillW = maxOf(contentW * 0.16f, textH * 3f)
            val pillH = maxOf(3f, textH * 0.3f)
            ui.emitRect(x + (contentW - pillW) * 0.5f, topY + textH * 0.5f, pillW, pillH, 0x66748AFFL)
            // Info lines, stacked below the pill.
            val top = topY + textH * 1.6f
            val gap = textH * 1.5f
            for ((i, ln) in lines.withIndex()) ui.emitTextLeft(ln.first, x, top + i * gap, textH, ln.second)
            ui.emitDragHandle(x, topY, contentW, height, id, onTap, onDrag, onRelease)
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
                    ui.emitOverlayClick(fx, oy, fw, height, label = opt) { onPick(i) }
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

    private class RowItem(val items: List<Item>, override val height: Float, val gapPx: Float?) : Item {
        // A gap goes *between two adjacent visible items*. A spacer already separates what it sits
        // between, so it neither claims a gap nor earns one on either side — `[A, spacer, B]` is spaced
        // by the spacer alone, which is the only thing that knows how wide that separation must be.
        private val gaps = (1 until items.size).count { items[it] !is SpacerItem && items[it - 1] !is SpacerItem }
        private val totalWeight = items.fold(0f) { a, i -> a + i.weight }

        private fun gap(textH: Float) = gapPx ?: textH * 0.5f

        /**
         * Natural width — what the panel sizes itself to.
         *
         * ⚠️ The weighted items are **not** summed. A weight-`w` item ends up with `leftover · w / W`, so
         * for *every* weighted item to still fit its own text the leftover must be at least
         * `max(natural · W / w)` — for two equal operands, twice the longer one, not the two added
         * together. Summing them under-reserves, and the shortfall comes out of the widest child: the
         * long operand's dropdown arrow ends up sitting on top of its own text.
         */
        override fun measureWidth(textH: Float): Float {
            var fixed = gaps * gap(textH)
            var weighted = 0f
            for (i in items) {
                if (i.weight == 0f) fixed += i.measureWidth(textH)
                else weighted = maxOf(weighted, i.measureWidth(textH) * totalWeight / i.weight)
            }
            return fixed + weighted
        }

        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            // Never negative: a row clipped by the screen has no leftover to hand out.
            val fixedW = items.fold(gaps * gap(textH)) { a, i -> if (i.weight == 0f) a + i.measureWidth(textH) else a }
            val leftover = (contentW - fixedW).coerceAtLeast(0f)
            var bx = x
            var placed = false
            for (b in items) {
                // ⚠️ Exactly the share — never `maxOf` this against the natural width. A weighted item
                // that could claim its own text back is not weighted at all: the long operand would keep
                // its length, the short one would take what remained, and the two would come out uneven,
                // which is the single thing [clauseRow] exists to prevent. A minimum belongs in
                // [measureWidth] (where it sizes the panel), not here.
                val w = if (b.weight == 0f) b.measureWidth(textH) else leftover * b.weight / totalWeight
                if (b is SpacerItem) { bx += w; placed = false; continue }   // the spacer *is* the gap
                if (placed) bx += gap(textH)      // gap *before* each subsequent item, so none trails
                b.emit(ui, bx, topY, w, textH)
                bx += w
                placed = true
            }
        }
    }

    private class GapItem(override val height: Float) : Item {
        override fun measureWidth(textH: Float) = 0f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) = Unit
    }

    private class DropSlotItem(val id: String, override val height: Float) : Item {
        override fun measureWidth(textH: Float) = 0f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            ui.emitDropTarget(id, x, topY, contentW, height)
            val hot = ui.hoveredDropTarget() == id
            val lineH = if (hot) 3f else 2f
            val cy = topY + (height - lineH) * 0.5f
            ui.emitRect(x, cy, contentW, lineH, if (hot) 0x66AAFFFFL else 0x33415577L)
        }
    }

    /** See [chip]. */
    private class ChipItem(
        val label: String, val value: String, val color: Long, override val height: Float,
        override val weight: Float, val onTap: () -> Unit,
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

    /** A **chip-styled inline dropdown** (`apps/cyto/UI_REDESIGN.md` §8a): a field showing [value] that, when
     *  [open], drops its [options] into the overlay layer anchored to the field. **Edge-aware** — the list
     *  flips *up* when it wouldn't fit below — and **scroll-aware** — it isn't drawn when the field has
     *  scrolled out of its clip viewport (the desktop analogue of the L4 sheet, reusing the same overlay
     *  plumbing as [picker]). */
    private class DropdownItem(
        val label: String, val value: String, val options: List<String>, val open: Boolean,
        val onToggle: () -> Unit, val onPick: (Int) -> Unit, override val height: Float,
    ) : Item {
        private fun fieldW(textH: Float): Float {
            var w = UiTextRenderer.measureWidthPx(value, textH)
            for (o in options) w = maxOf(w, UiTextRenderer.measureWidthPx(o, textH))
            return w + textH * 3f
        }
        override fun measureWidth(textH: Float) =
            (if (label.isEmpty()) 0f else UiTextRenderer.measureWidthPx(label, textH) + textH * 2f) + fieldW(textH)

        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            val ty = topY + (height - textH) * 0.5f
            val fw = if (label.isEmpty()) contentW else fieldW(textH)
            val fx = x + contentW - fw
            if (label.isNotEmpty()) ui.emitTextLeft(label, x, ty, textH, 0x9A9A9AFFL)
            ui.emitRect(fx, topY + 1f, fw, height - 2f, if (open) 0x2A4A6AFFL else 0x2A3550FFL)
            ui.emitTextCentered(value, fx + fw * 0.5f, ty, textH, 0xFFFFFFFFL)
            ui.emitTextLeft("V", fx + fw - textH * 0.9f, ty, textH, 0xAACCFFFFL)
            ui.emitClick(fx, topY + 1f, fw, height - 2f, label = if (label.isEmpty()) value else "$label $value", onClick = onToggle)
            if (!open) return
            // Suppress the list if the field scrolled out of its viewport (overlay layer is unclipped).
            if (!ui.isWithinClip(fx, topY, fw, height)) return
            val listH = options.size * height
            // Flip up when the list wouldn't fit below the field and there's more room above.
            val spaceBelow = ui.resHeight - (topY + height)
            val flipUp = listH > spaceBelow && topY > spaceBelow
            var oy = if (flipUp) topY - listH else topY + height
            for ((i, opt) in options.withIndex()) {
                ui.emitOverlayRect(fx, oy, fw, height, 0x1A2233FFL)
                ui.emitOverlayTextLeft(opt, fx + textH * 0.4f, oy + (height - textH) * 0.5f, textH, if (opt == value) 0xFFE070FFL else 0xCFE0FFFFL)
                ui.emitOverlayClick(fx, oy, fw, height, label = opt) { onPick(i) }
                oy += height
            }
        }
    }

    /** See [segmented]. */
    private class SegmentItem(
        val opt: String, val set: List<String>, val on: Boolean, override val height: Float,
        val onSelect: () -> Unit,
    ) : Item {
        override fun measureWidth(textH: Float) =
            (set.maxOfOrNull { UiTextRenderer.measureWidthPx(it, textH) } ?: 0f) + textH * 1.2f
        override fun emit(ui: Ui, x: Float, topY: Float, contentW: Float, textH: Float) {
            ui.emitRect(x, topY + 1f, contentW, height - 2f, if (on) 0x3A6EA5FFL else 0x252C3AFFL)
            ui.emitTextCentered(opt, x + contentW * 0.5f, topY + (height - textH) * 0.5f, textH, if (on) 0xFFFFFFFFL else 0x9A9A9AFFL)
            ui.emitClick(x, topY + 1f, contentW, height - 2f, label = opt, onClick = onSelect)
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

    private companion object {
        /** A list row's description text, relative to its title. */
        const val DESC_RATIO = 0.78f
    }
}

/** Scale a colour's RGB toward black by [f], keeping its alpha — "this row is inert right now". */
private fun dim(rgba: Long, f: Float): Long {
    fun ch(shift: Int) = (((rgba ushr shift) and 0xFF).toFloat() * f).toInt().coerceIn(0, 255).toLong()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or (rgba and 0xFF)
}

private fun contrast(rgba: Long): Long {
    val r = ((rgba ushr 24) and 0xFF).toFloat() / 255f
    val g = ((rgba ushr 16) and 0xFF).toFloat() / 255f
    val b = ((rgba ushr 8) and 0xFF).toFloat() / 255f
    return if (0.299f * r + 0.587f * g + 0.114f * b < 0.5f) 0xFFFFFFFFL else 0x000000FFL
}
