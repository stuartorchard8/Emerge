package org.emerge.androidapp

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.sim.core.EntityId
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Android host for the native Cyto demo. Single-player, so the sim tick + render + the
 * on-screen [CytoControls] all run on the GL thread (in [onDrawFrame]); touch events are
 * marshalled onto the GL thread via [queueEvent] so they mutate the controller/controls
 * safely. One finger: UI first, else grab a cell / pan / tap; two fingers: pinch-zoom.
 */
internal class CytoAndroidView(context: Context) : GLSurfaceView(context) {
    private val controller = CytoController()

    // Created on the GL thread (need a current context); only touched there afterwards.
    private var renderer: CytoRenderer? = null
    private var controls: CytoControls? = null
    private var lastTimeNanos = 0L

    // Touch state, mutated only inside queueEvent (GL thread).
    private var grabId: EntityId? = null
    private var uiConsumed = false
    private var dragged = false
    private var lastX = 0f
    private var lastY = 0f
    private var pinchSpan = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                renderer = CytoRenderer()
                controls = CytoControls()
                lastTimeNanos = System.nanoTime()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                val w = width.toFloat()
                val h = height.toFloat()
                renderer?.setResolution(w, h)
                controls?.setResolution(w, h)
            }

            override fun onDrawFrame(gl: GL10?) {
                val r = renderer ?: return
                val c = controls ?: return
                val now = System.nanoTime()
                val delta = ((now - lastTimeNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
                lastTimeNanos = now

                val frame = controller.tick(delta)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                r.draw(frame)
                for (readout in controller.readouts(grabId, c.showChemicals)) {
                    val screen = r.worldToScreen(readout.x, readout.y)
                    c.drawLabel(readout.text, screen[0], screen[1] - 28f, pixelHeight = 12f, color = 0x00FF22FF)
                }
                c.draw()
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

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

    // ── Touch logic (GL thread) ─────────────────────────────────────────────────

    private fun onDown(x: Float, y: Float) {
        val r = renderer ?: return
        val c = controls ?: return
        dragged = false
        lastX = x
        lastY = y
        pinchSpan = 0f
        if (c.hitTest(x, y)) {
            uiConsumed = true
            grabId = null
            return
        }
        uiConsumed = false
        val world = r.screenToWorld(x, y)
        val hit = controller.cellAt(world[0], world[1])
        grabId = hit
        if (hit != null && c.touchMode == TouchMode.Detach) controller.detach(hit)
    }

    private fun onMove(x: Float, y: Float) {
        if (uiConsumed) return
        val r = renderer ?: return
        val c = controls ?: return
        val dx = x - lastX
        val dy = y - lastY
        if (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX) dragged = true
        val held = grabId
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
        if (!uiConsumed && !dragged && r != null && c != null) {
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
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> queueEvent { pinchSpan = span; grabId = null; uiConsumed = true }
            MotionEvent.ACTION_MOVE -> queueEvent {
                if (pinchSpan > 0f && span > 0f) {
                    val factor = span / pinchSpan
                    renderer?.zoomByFactor(factor)
                }
                pinchSpan = span
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                queueEvent { pinchSpan = 0f; uiConsumed = false }
        }
    }

    companion object {
        private const val DRAG_THRESHOLD_PX = 12f
    }
}
