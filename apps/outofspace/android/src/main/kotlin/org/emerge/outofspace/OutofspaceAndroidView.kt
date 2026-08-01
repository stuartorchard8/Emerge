package org.emerge.outofspace

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.OutofspaceHud
import org.emerge.demo.outofspace.OutofspaceRenderer
import org.emerge.render.torus.ui.Ui
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Android host. Same three jobs as the desktop host — GL context, real time, platform input — and the
 * same zero game rules.
 *
 * Two things are specific to the platform: everything GL happens on the GL thread (touch events
 * arrive on the main thread and are marshalled across with [queueEvent]), and [Ui.setDensity] must
 * be told the display density or every panel is a third of its intended size.
 *
 * Touch model: one finger pans, a tap places, two fingers pinch-zoom. Panning beats painting on a
 * phone — a finger is imprecise and an accidental line of belts is much more annoying than an
 * accidental pan.
 */
internal class OutofspaceAndroidView(context: Context) : GLSurfaceView(context) {

    private val controller = OutofspaceController()
    private val density = context.resources.displayMetrics.density

    private var renderer: OutofspaceRenderer? = null
    private var hud: OutofspaceHud? = null
    private var ui: Ui? = null
    private var lastTimeNanos = 0L

    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    private var uiConsumed = false
    private var pinchDist = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = setup()

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                renderer?.setResolution(width.toFloat(), height.toFloat())
                ui?.setResolution(width.toFloat(), height.toFloat())
            }

            override fun onDrawFrame(gl: GL10?) = drawFrame()
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun setup() {
        renderer = OutofspaceRenderer().also { it.centreOn(controller.state) }
        hud = OutofspaceHud().also {
            it.onTogglePause = { controller.paused = !controller.paused }
            it.onReset = { controller.reset(); renderer?.centreOn(controller.state) }
        }
        ui = Ui().also { it.setDensity(density) }
    }

    private fun drawFrame() {
        val renderer = renderer ?: return
        val ui = ui ?: return
        val hud = hud ?: return

        val now = System.nanoTime()
        val delta = if (lastTimeNanos == 0L) 0f else ((now - lastTimeNanos) / 1_000_000_000.0).toFloat()
        lastTimeNanos = now

        ui.advanceClock(delta)
        renderer.draw(controller.tick(delta), -1, controller.overlay, controller.tickAlpha)
        hud.build(ui, controller, if (delta > 0f) 1f / delta else 0f)
        ui.draw()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Copy what is needed off the event: it is recycled the moment this returns, so reading it
        // later on the GL thread would read whatever the next gesture put there.
        val action = event.actionMasked
        val x = event.x
        val y = event.y
        val pointers = event.pointerCount
        val secondX = if (pointers > 1) event.getX(1) else 0f
        val secondY = if (pointers > 1) event.getY(1) else 0f
        val midX = if (pointers > 1) (x + secondX) * 0.5f else x
        val midY = if (pointers > 1) (y + secondY) * 0.5f else y
        val spread = if (pointers > 1) hypot(x - secondX, y - secondY) else 0f

        queueEvent {
            val ui = ui ?: return@queueEvent
            val renderer = renderer ?: return@queueEvent
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = x; lastY = y; dragged = false
                    uiConsumed = ui.hitTestDown(x, y)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pinchDist = spread
                    uiConsumed = false   // a second finger is always a camera gesture
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pointers > 1) {
                        if (pinchDist > 0f && spread > 0f) renderer.zoomAtScreen(midX, midY, spread / pinchDist)
                        pinchDist = spread
                    } else {
                        val dx = x - lastX
                        val dy = y - lastY
                        if (!dragged && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) dragged = true
                        if (uiConsumed) ui.dragTo(x, y) else if (dragged) renderer.panByPixels(dx, dy)
                        lastX = x; lastY = y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (uiConsumed) {
                        ui.hitTestUp(x, y)
                        ui.releaseHold()
                    } else if (!dragged) {
                        val tile = renderer.tileIndexAt(x, y, controller.state)
                        if (tile >= 0) controller.apply(tile)
                    }
                    uiConsumed = false
                    pinchDist = 0f
                }
                MotionEvent.ACTION_POINTER_UP -> pinchDist = 0f
            }
        }
        return true
    }

    private companion object {
        /** In pixels — a phone's pixels are small, so deliberately larger than desktop's. */
        const val DRAG_THRESHOLD = 12f
    }
}
