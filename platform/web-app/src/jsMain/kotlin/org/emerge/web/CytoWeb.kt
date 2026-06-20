package org.emerge.web

import kotlinx.browser.window
import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.sim.core.EntityId
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.abs
import kotlin.math.pow

/**
 * Web host for the native Cyto demo (WebGL2). Mirrors the desktop host: pointer-down hits
 * the on-screen [CytoControls] first, else grabs a cell / pans; drag moves; click spawns or
 * acts per the current mode/type; wheel zooms toward the cursor. Assumes `GPU.init(gl)` was
 * already called by [main]. Single-player, so the rAF loop drives sim + render + UI.
 */
fun startCyto(canvas: HTMLCanvasElement) {
    val controller = CytoController()
    val renderer = CytoRenderer()
    val controls = CytoControls()

    fun applySize() {
        val dpr = window.devicePixelRatio
        canvas.width = (window.innerWidth * dpr).toInt()
        canvas.height = (window.innerHeight * dpr).toInt()
        renderer.setResolution(canvas.width.toFloat(), canvas.height.toFloat())
        controls.setResolution(canvas.width.toFloat(), canvas.height.toFloat())
    }
    applySize()
    window.addEventListener("resize", { applySize() })

    var grabId: EntityId? = null
    var uiConsumed = false
    var dragged = false
    var down = false
    var lastX = 0f
    var lastY = 0f

    fun toPx(e: MouseEvent): Pair<Float, Float> {
        val rect = canvas.getBoundingClientRect()
        val x = ((e.clientX - rect.left) * canvas.width / rect.width).toFloat()
        val y = ((e.clientY - rect.top) * canvas.height / rect.height).toFloat()
        return x to y
    }

    canvas.addEventListener("mousedown", { ev ->
        val e = ev as MouseEvent
        if (e.button.toInt() != 0) return@addEventListener
        down = true
        dragged = false
        val (x, y) = toPx(e)
        lastX = x; lastY = y
        if (controls.hitTest(x, y)) {
            uiConsumed = true
            grabId = null
            return@addEventListener
        }
        uiConsumed = false
        val world = renderer.screenToWorld(x, y)
        val hit = controller.cellAt(world[0], world[1])
        grabId = hit
        if (hit != null && controls.touchMode == TouchMode.Detach) controller.detach(hit)
    })

    window.addEventListener("mousemove", { ev ->
        if (!down || uiConsumed) return@addEventListener
        val e = ev as MouseEvent
        val (x, y) = toPx(e)
        val dx = x - lastX
        val dy = y - lastY
        if (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD) dragged = true
        val held = grabId
        if (held != null) {
            val world = renderer.screenToWorld(x, y)
            controller.grab(held, world[0], world[1], sticky = controls.touchMode == TouchMode.Sticky)
        } else {
            renderer.panByPixels(dx, dy)
        }
        lastX = x; lastY = y
    })

    window.addEventListener("mouseup", { ev ->
        val e = ev as MouseEvent
        if (e.button.toInt() != 0) return@addEventListener
        if (!uiConsumed && !dragged) {
            val (x, y) = toPx(e)
            val world = renderer.screenToWorld(x, y)
            controller.tap(world[0], world[1], controls.touchMode, controls.cellType)
        }
        controller.releaseGrab()
        grabId = null
        dragged = false
        uiConsumed = false
        down = false
    })

    canvas.addEventListener("wheel", { ev ->
        val e = ev as WheelEvent
        e.preventDefault()
        val (x, y) = toPx(e)
        val steps = (-e.deltaY / 100.0).coerceIn(-24.0, 24.0)
        renderer.zoomAtScreen(x, y, 1.1.pow(steps).toFloat())
    })

    var last = 0.0
    fun frame(ts: Double) {
        val delta = if (last == 0.0) 0f else ((ts - last) / 1000.0).toFloat().coerceIn(0f, 0.25f)
        last = ts
        val f = controller.tick(delta)
        renderer.colorMode = controls.colorMode   // Color button → renderer
        renderer.draw(f)
        for (r in controller.readouts(grabId, controls.showChemicals)) {
            val screen = renderer.worldToScreen(r.x, r.y)
            controls.drawLabel(r.text, screen[0], screen[1] - 28f, pixelHeight = 12f, color = 0x00FF22FF)
        }
        controls.draw()
        window.requestAnimationFrame(::frame)
    }
    window.requestAnimationFrame(::frame)
}

private const val DRAG_THRESHOLD = 4f
