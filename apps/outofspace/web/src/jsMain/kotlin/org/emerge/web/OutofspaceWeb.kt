package org.emerge.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.OutofspaceHud
import org.emerge.demo.outofspace.OutofspaceRenderer
import org.emerge.render.torus.GPU
import org.emerge.render.torus.ui.Ui
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.abs
import kotlin.math.pow

/**
 * Web host (WebGL2). The third dialect of the same forty lines.
 *
 * The one structural difference from the other two hosts: [GPU.init] must be handed the WebGL
 * context before anything touches the GPU. On JVM and Android the GL entry points are global and
 * `GPU` finds them itself; in a browser they hang off the context object, so it has to be injected.
 *
 * Serve it with:  ./gradlew :apps:outofspace:web:jsBrowserDevelopmentRun
 */
fun main() {
    val canvas = document.getElementById("canvas") as HTMLCanvasElement
    val gl = canvas.getContext("webgl2") ?: error("WebGL2 not supported")
    GPU.init(gl)
    start(canvas)
}

private fun start(canvas: HTMLCanvasElement) {
    val controller = OutofspaceController()
    val renderer = OutofspaceRenderer(controller.cfg)
    val hud = OutofspaceHud()
    val ui = Ui()

    hud.onTogglePause = { controller.paused = !controller.paused }
    hud.onClear = { controller.clear() }
    hud.onSpawnBurst = { repeat(50) { controller.spawnAt(0f, 0f) } }

    fun applySize() {
        val dpr = window.devicePixelRatio
        canvas.width = (window.innerWidth * dpr).toInt()
        canvas.height = (window.innerHeight * dpr).toInt()
        renderer.setResolution(canvas.width.toFloat(), canvas.height.toFloat())
        ui.setResolution(canvas.width.toFloat(), canvas.height.toFloat())
    }
    applySize()
    window.addEventListener("resize", { applySize() })

    var down = false
    var dragged = false
    var uiConsumed = false
    var lastX = 0f
    var lastY = 0f

    /** CSS pixels → framebuffer pixels. The canvas is backed at device resolution, so these differ. */
    fun toPx(e: MouseEvent): Pair<Float, Float> {
        val rect = canvas.getBoundingClientRect()
        return ((e.clientX - rect.left) * canvas.width / rect.width).toFloat() to
            ((e.clientY - rect.top) * canvas.height / rect.height).toFloat()
    }

    canvas.addEventListener("mousedown", { ev ->
        val e = ev as MouseEvent
        if (e.button.toInt() != 0) return@addEventListener
        val (x, y) = toPx(e)
        down = true; dragged = false; lastX = x; lastY = y
        uiConsumed = ui.hitTestDown(x, y)
    })

    window.addEventListener("mousemove", { ev ->
        val e = ev as MouseEvent
        val (x, y) = toPx(e)
        ui.hover(x, y)
        if (!down) return@addEventListener
        val dx = x - lastX
        val dy = y - lastY
        if (!dragged && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) dragged = true
        if (uiConsumed) ui.dragTo(x, y) else if (dragged) renderer.panByPixels(dx, dy)
        lastX = x; lastY = y
    })

    window.addEventListener("mouseup", { ev ->
        val e = ev as MouseEvent
        if (e.button.toInt() != 0) return@addEventListener
        val (x, y) = toPx(e)
        if (uiConsumed) {
            ui.hitTestUp(x, y)
            ui.releaseHold()
        } else if (!dragged) {
            val w = renderer.screenToWorld(x, y)
            controller.spawnAt(w[0], w[1])
        }
        down = false; dragged = false; uiConsumed = false
    })

    canvas.addEventListener("wheel", { ev ->
        val e = ev as WheelEvent
        e.preventDefault()
        val (x, y) = toPx(e)
        renderer.zoomAtScreen(x, y, 1.1.pow((-e.deltaY / 100.0).coerceIn(-24.0, 24.0)).toFloat())
    })

    var last = 0.0
    fun frame(ts: Double) {
        val delta = if (last == 0.0) 0f else ((ts - last) / 1000.0).toFloat().coerceIn(0f, 0.25f)
        last = ts
        ui.advanceClock(delta)
        renderer.draw(controller.tick(delta))
        hud.build(ui, controller, if (delta > 0f) 1f / delta else 0f)
        ui.draw()
        window.requestAnimationFrame(::frame)
    }
    window.requestAnimationFrame(::frame)
}

private const val DRAG_THRESHOLD = 4f
