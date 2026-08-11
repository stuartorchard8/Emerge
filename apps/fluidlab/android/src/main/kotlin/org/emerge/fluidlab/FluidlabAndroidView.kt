package org.emerge.fluidlab

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import org.emerge.demo.fluidlab.FluidlabController
import org.emerge.demo.fluidlab.FluidlabHud
import org.emerge.demo.fluidlab.FluidlabRenderer
import org.emerge.render.torus.ui.Ui
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Android host. Same three jobs as the desktop host — GL context, real time, platform input — and
 * the same zero game rules.
 *
 * Two things are specific to this platform and easy to get wrong:
 *
 *  1. **Everything GL happens on the GL thread.** The renderer, the UI and the sim tick are all
 *     created and touched inside the [Renderer] callbacks. Touch events arrive on the *main* thread,
 *     so they are marshalled across with [queueEvent] rather than acted on where they land.
 *  2. **Density.** A phone has ~3 physical pixels per dp. Without [Ui.setDensity] every panel is a
 *     third of its intended size and unusably small; with it, the same layout code that draws the
 *     desktop HUD is legible in the hand.
 */
internal class FluidlabAndroidView(context: Context) : GLSurfaceView(context) {

    private val controller = FluidlabController()
    private val density = context.resources.displayMetrics.density

    // Created on the GL thread (they need a current context); only touched there afterwards.
    private var renderer: FluidlabRenderer? = null
    private var hud: FluidlabHud? = null
    private var ui: Ui? = null
    private var lastTimeNanos = 0L

    // Touch state, GL-thread only.
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
        renderer = FluidlabRenderer(controller.cfg)
        hud = FluidlabHud().also {
            it.onTogglePause = { controller.paused = !controller.paused }
            it.onClear = { controller.clear() }
            it.onSpawnBurst = { repeat(50) { controller.spawnAt(0f, 0f) } }
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
        val state = controller.tick(delta)
        renderer.draw(state)
        hud.build(ui, controller, if (delta > 0f) 1f / delta else 0f)
        ui.draw()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Copy what we need off the event: it is recycled the moment this method returns, so reading
        // it later on the GL thread would read whatever the next gesture put there.
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
                        val w = renderer.screenToWorld(x, y)
                        controller.spawnAt(w[0], w[1])
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
        /** In pixels — a phone's pixels are small, so this is deliberately larger than desktop's. */
        const val DRAG_THRESHOLD = 12f
    }
}
