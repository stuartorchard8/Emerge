package org.emerge.cyto

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.demo.cyto.ui.CytoHud
import org.emerge.demo.cyto.ui.GeneEditor
import org.emerge.render.torus.ui.Ui
import org.emerge.sim.core.EntityId
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Android host for the native Cyto demo. Drives the same progressive-disclosure UI as the
 * desktop host (apps/cyto/UI_REDESIGN.md §8) — the [Ui] toolkit, the [CytoHud] bottom bar +
 * sheets, and the [GeneEditor] cell/gene panels — always in the narrow (phone) layout. The
 * sim tick + render + UI all run on the GL thread (in [onDrawFrame]); touch events are
 * marshalled onto it via [queueEvent].
 *
 * Touch model: one finger routes through the UI first ([Ui.hitTestDown]/[Ui.dragTo]/
 * [Ui.hitTestUp], no wheel); a miss grabs the cell under it (drag moves it) or pans empty
 * space (which releases any camera focus). A tap on a cell both selects it (info sheet) and
 * gives it camera focus, so the selected cell eases up into the free area above the sheet.
 * Two fingers pinch-zoom.
 *
 * Deferred to the next slice (they live in the desktop module and need moving into core):
 * the front-end menu, named saves, the genome-brush library, and the campaign. MENU is a
 * no-op until then, and speed/pause are driven inline (no threaded sim driver).
 */
internal class CytoAndroidView(context: Context) : GLSurfaceView(context) {
    private val controller = CytoController()
    private val displayDensity = context.resources.displayMetrics.density

    // Created on the GL thread (need a current context); only touched there afterwards.
    private var renderer: CytoRenderer? = null
    private var controls: CytoControls? = null
    private var hud: CytoHud? = null
    private var ui: Ui? = null
    private var geneEditor: GeneEditor? = null
    private var lastTimeNanos = 0L

    // Inline sim speed/pause (no threaded driver on this host yet).
    private var paused = false
    private var speedIdx = DEFAULT_SPEED_IDX

    // Touch state, mutated only inside queueEvent (GL thread).
    private var grabId: EntityId? = null
    private var uiConsumed = false
    private var dragged = false
    private var pointerDown = false
    private var lastX = 0f
    private var lastY = 0f
    private var pinchSpan = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                renderer = CytoRenderer()
                controls = CytoControls()
                hud = CytoHud()
                geneEditor = GeneEditor()
                ui = Ui().also { it.setDensity(displayDensity) }
                controls?.let { c ->
                    // The HUD Speed sheet drives these; there's no threaded driver, so pause/speed are inline.
                    c.showSimSpeed = true
                    c.onSlower = { speedIdx = (speedIdx - 1).coerceAtLeast(0) }
                    c.onFaster = { speedIdx = (speedIdx + 1).coerceAtMost(SPEEDS.lastIndex) }
                    c.onTogglePause = { paused = !paused }
                }
                lastTimeNanos = System.nanoTime()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                val w = width.toFloat()
                val h = height.toFloat()
                renderer?.setResolution(w, h)
                controls?.setResolution(w, h)
                ui?.setResolution(w, h)
            }

            override fun onDrawFrame(gl: GL10?) {
                drawFrame()
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    // ── Frame (GL thread) ───────────────────────────────────────────────────────

    private fun drawFrame() {
        val r = renderer ?: return
        val c = controls ?: return
        val hud = hud ?: return
        val ui = ui ?: return
        val geneEditor = geneEditor ?: return

        val now = System.nanoTime()
        val delta = ((now - lastTimeNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
        lastTimeNanos = now

        // Drive hold-to-repeat steppers (the gene-editor +/- buttons) while a finger is down.
        if (pointerDown) ui.updateHold(lastX, lastY, delta)

        val simDelta = if (paused) 0f else delta * SPEEDS[speedIdx]
        val frame = controller.tick(simDelta)

        r.showLightField = c.showLightField
        r.showMatterField = c.showMatterField
        r.colorMode = c.colorMode

        controller.pruneDeadSelection()
        r.focusedCellId = controller.lastHeldId?.value ?: -1   // highlight = the inspected cell (tap)
        // The camera follows a separate focus target (also set on tap), unless the cell is being grabbed.
        if (!controller.isGrabbed) {
            val pos = controller.cameraFocusPosition()
            r.follow(controller.cameraFocusId?.value ?: -1, pos?.first ?: -1f, pos?.second ?: -1f)
        }
        // Recentre the followed cell into the space the L2 sheet doesn't cover (always narrow on a phone).
        run {
            val cellShown = geneEditor.isEditing || controller.lastHeldId != null
            val (offX, offY) = geneEditor.freeAreaOffsetPx(narrow = true, cellShown, ui.resWidth, ui.resHeight, ui.scale)
            r.setFollowOffsetPx(offX, offY)
        }

        r.draw(frame)   // renderer fills its own background

        // Feed the HUD's status readout; the bar shows only at L0 (a cell sheet owns the bottom otherwise).
        c.simPaused = paused
        c.simStatus = if (paused) "PAUSED" else "${SPEEDS[speedIdx]}x"
        val showHud = !geneEditor.isEditing && controller.lastHeldId == null
        if (!showHud) hud.close()

        ui.frame {
            geneEditor.render(this, controller, narrow = true)
            if (showHud) hud.render(this, c, wide = false) { /* menu: next slice */ }
        }
        ui.draw()
    }

    // ── Touch (marshalled onto the GL thread) ────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            handlePinch(event)
            return true
        }
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> queueEvent { onDown(x, y) }
            MotionEvent.ACTION_MOVE -> queueEvent { onMove(x, y) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> queueEvent { onUp(x, y) }
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        val r = renderer ?: return
        val c = controls ?: return
        val ui = ui ?: return
        val geneEditor = geneEditor ?: return
        dragged = false
        pointerDown = true
        lastX = x
        lastY = y
        pinchSpan = 0f
        // UI first: a hit (info-panel/HUD buttons, scroll areas) consumes the press.
        if (ui.hitTestDown(x, y)) {
            uiConsumed = true
            grabId = null
            return
        }
        uiConsumed = false
        geneEditor.closeDropdown()   // a press outside the UI dismisses any open picker
        val world = r.screenToWorld(x, y)
        val hit = controller.cellAt(world[0], world[1])
        grabId = hit
        if (hit != null && c.touchMode == TouchMode.Detach) controller.detach(hit)
    }

    private fun onMove(x: Float, y: Float) {
        val r = renderer ?: return
        val c = controls ?: return
        val ui = ui ?: return
        // A press the UI claimed may be a drag inside a scroll area — route it to the toolkit.
        if (uiConsumed) {
            ui.dragTo(x, y)
            lastX = x
            lastY = y
            return
        }
        val dx = x - lastX
        val dy = y - lastY
        val held = grabId
        if (!dragged && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
            dragged = true
            // A drag that starts on empty space (no cell grabbed) fully deselects. Unlike desktop — which
            // keeps the info-panel selection because it has a separate right-click camera — a phone has one
            // pointer, so an empty-space drag is the natural "put the cell down" gesture.
            if (held == null) { controller.clearSelection(); controller.clearCameraFocus() }
        }
        if (held != null) {
            val world = r.screenToWorld(x, y)
            controller.grab(held, world[0], world[1], sticky = c.touchMode == TouchMode.Sticky)
        } else {
            r.panByPixels(dx, dy)
        }
        lastX = x
        lastY = y
    }

    private fun onUp(x: Float, y: Float) {
        val r = renderer
        val c = controls
        val ui = ui
        pointerDown = false
        if (uiConsumed) {
            ui?.hitTestUp(x, y)
            ui?.releaseHold()
            uiConsumed = false
            grabId = null
            dragged = false
            return
        }
        ui?.releaseHold()
        if (!dragged && r != null && c != null) {
            val hit = grabId
            if (hit != null) {
                controller.focus(hit)         // select → info sheet
                controller.cameraFocus(hit)   // and follow it up into the free area
            }
            val world = r.screenToWorld(x, y)
            controller.tap(world[0], world[1], c.touchMode, c.cellType)
        }
        controller.releaseGrab()
        grabId = null
        dragged = false
        uiConsumed = false
    }

    private fun handlePinch(event: MotionEvent) {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        val span = hypot(dx, dy)
        val midX = (event.getX(0) + event.getX(1)) * 0.5f
        val midY = (event.getY(0) + event.getY(1)) * 0.5f
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> queueEvent {
                pinchSpan = span; grabId = null; uiConsumed = true; pointerDown = false
            }
            MotionEvent.ACTION_MOVE -> queueEvent {
                if (pinchSpan > 0f && span > 0f) renderer?.zoomAtScreen(midX, midY, span / pinchSpan)
                pinchSpan = span
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                queueEvent { pinchSpan = 0f; uiConsumed = false }
        }
    }

    companion object {
        private const val DRAG_THRESHOLD_PX = 12f
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
        private const val DEFAULT_SPEED_IDX = 2   // 1x
    }
}
