package org.emerge.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.OutofspaceHud
import org.emerge.demo.outofspace.OutofspaceRenderer
import org.emerge.demo.outofspace.Tool
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.render.torus.GPU
import org.emerge.render.torus.ui.Ui
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.pow

/**
 * Web host (WebGL2). The third dialect of the same loop.
 *
 * The one structural difference from the other hosts: [GPU.init] must be handed the WebGL context
 * before anything touches the GPU. On JVM and Android the GL entry points are global and `GPU` finds
 * them itself; in a browser they hang off the context object, so it has to be injected.
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
    val renderer = OutofspaceRenderer()
    val hud = OutofspaceHud()
    val ui = Ui()
    renderer.centreOn(controller.state)

    hud.onTogglePause = { controller.paused = !controller.paused }
    hud.onReset = { controller.reset(); renderer.centreOn(controller.state) }

    fun applySize() {
        val dpr = window.devicePixelRatio
        canvas.width = (window.innerWidth * dpr).toInt()
        canvas.height = (window.innerHeight * dpr).toInt()
        renderer.setResolution(canvas.width.toFloat(), canvas.height.toFloat())
        ui.setResolution(canvas.width.toFloat(), canvas.height.toFloat())
    }
    applySize()
    window.addEventListener("resize", { applySize() })

    var leftDown = false
    var middleDown = false
    var uiConsumed = false
    var lastX = 0f
    var lastY = 0f
    var hovered = TileIndex.NONE
    var lastPainted = TileIndex.NONE

    /** CSS pixels → framebuffer pixels. The canvas is backed at device resolution, so these differ. */
    fun toPx(e: MouseEvent): Pair<Float, Float> {
        val rect = canvas.getBoundingClientRect()
        return ((e.clientX - rect.left) * canvas.width / rect.width).toFloat() to
            ((e.clientY - rect.top) * canvas.height / rect.height).toFloat()
    }

    canvas.addEventListener("contextmenu", { it.preventDefault() })

    canvas.addEventListener("mousedown", { ev ->
        val e = ev as MouseEvent
        val (x, y) = toPx(e)
        lastX = x; lastY = y
        when (e.button.toInt()) {
            0 -> {
                leftDown = true
                uiConsumed = ui.hitTestDown(x, y)
                if (!uiConsumed) {
                    val tile = renderer.tileIndexAt(x, y, controller.state)
                    if (tile != TileIndex.NONE) { controller.apply(tile); lastPainted = tile }
                }
            }
            1 -> middleDown = true
            2 -> {
                val tile = renderer.tileIndexAt(x, y, controller.state)
                if (tile != TileIndex.NONE) controller.removeAt(tile)
            }
        }
    })

    window.addEventListener("mousemove", { ev ->
        val e = ev as MouseEvent
        val (x, y) = toPx(e)
        ui.hover(x, y)
        hovered = renderer.tileIndexAt(x, y, controller.state)
        val dx = x - lastX
        val dy = y - lastY
        when {
            middleDown -> renderer.panByPixels(dx, dy)
            leftDown && uiConsumed -> ui.dragTo(x, y)
            leftDown && controller.tool == Tool.Build -> if (hovered != TileIndex.NONE && hovered != lastPainted) {
                controller.place(hovered)
                lastPainted = hovered
            }
        }
        lastX = x; lastY = y
    })

    window.addEventListener("mouseup", { ev ->
        val e = ev as MouseEvent
        val (x, y) = toPx(e)
        when (e.button.toInt()) {
            0 -> {
                if (uiConsumed) { ui.hitTestUp(x, y); ui.releaseHold() }
                leftDown = false
                lastPainted = TileIndex.NONE
                uiConsumed = false
            }
            1 -> middleDown = false
        }
    })

    canvas.addEventListener("wheel", { ev ->
        val e = ev as WheelEvent
        e.preventDefault()
        val (x, y) = toPx(e)
        renderer.zoomAtScreen(x, y, 1.12.pow((-e.deltaY / 100.0).coerceIn(-24.0, 24.0)).toFloat())
    })

    var last = 0.0
    fun frame(ts: Double) {
        val delta = if (last == 0.0) 0f else ((ts - last) / 1000.0).toFloat().coerceIn(0f, 0.25f)
        last = ts
        ui.advanceClock(delta)
        renderer.draw(controller.tick(delta), controller.inspectTile, controller.inspectLayer, hovered, controller.overlay, controller.simTime, controller.mode.camera)
        hud.build(ui, controller, if (delta > 0f) 1f / delta else 0f, hovered)
        ui.draw()
        window.requestAnimationFrame(::frame)
    }
    window.requestAnimationFrame(::frame)
}
