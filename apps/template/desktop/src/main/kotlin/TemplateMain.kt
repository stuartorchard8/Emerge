package org.emerge.desktop

import org.emerge.demo.template.TemplateController
import org.emerge.demo.template.TemplateHud
import org.emerge.demo.template.TemplateRenderer
import org.emerge.render.torus.ui.Ui
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Desktop host: a GLFW window, an input loop, and a call to the shared game every frame.
 *
 * A host owns exactly three things — a GL context, real time, and platform input — and knows no
 * game rules whatsoever. Every line of behaviour lives in `:apps:template:core`, which is why the
 * Android and web hosts beside this one are the same forty lines in a different dialect.
 *
 * The sim ticks inline on this thread. That is right up to the point where a tick costs more than a
 * frame; past it, move the sim to its own thread and have the draw thread read a snapshot. If you
 * do, read `reference_cyto_threading` first — the rule that a draw thread must never block on the
 * sim's lock is learned expensively.
 */
fun main() {
    if (!glfwInit()) error("GLFW init failed")
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)

    val window = glfwCreateWindow(1280, 800, "Emerge: Template", NULL, NULL)
    if (window == NULL) error("Failed to create GLFW window")
    glfwMakeContextCurrent(window)
    glfwSwapInterval(1)
    glfwShowWindow(window)
    org.lwjgl.opengl.GL.createCapabilities()

    // Everything GL must be constructed *after* the context is current.
    val controller = TemplateController()
    val renderer = TemplateRenderer(controller.cfg)
    val hud = TemplateHud()
    val ui = Ui()

    hud.onTogglePause = { controller.paused = !controller.paused }
    hud.onClear = { controller.clear() }
    hud.onSpawnBurst = { repeat(50) { controller.spawnAt(randomWorld(controller.cfg.worldSize), randomWorld(controller.cfg.worldSize)) } }

    var mouseDown = false
    var dragged = false
    var uiConsumed = false
    var lastX = 0f
    var lastY = 0f

    // Pointer routing, in priority order: UI first, then the world. Getting this order wrong means
    // clicks fall through panels into the world behind them.
    glfwSetMouseButtonCallback(window) { _, button, action, _ ->
        if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
        val (px, py) = cursorPixel(window)
        if (action == GLFW_PRESS) {
            mouseDown = true
            dragged = false
            lastX = px; lastY = py
            uiConsumed = ui.hitTestDown(px, py)
        } else {
            mouseDown = false
            if (uiConsumed) {
                ui.hitTestUp(px, py)
                ui.releaseHold()
            } else if (!dragged) {
                // A click that didn't drag and didn't hit the UI is a world action.
                val w = renderer.screenToWorld(px, py)
                controller.spawnAt(w[0], w[1])
            }
            uiConsumed = false
        }
    }

    glfwSetCursorPosCallback(window) { _, _, _ ->
        val (px, py) = cursorPixel(window)
        ui.hover(px, py)
        if (!mouseDown) return@glfwSetCursorPosCallback
        val dx = px - lastX
        val dy = py - lastY
        if (!dragged && (abs(px - lastX) > DRAG_THRESHOLD || abs(py - lastY) > DRAG_THRESHOLD)) dragged = true
        if (uiConsumed) ui.dragTo(px, py) else if (dragged) renderer.panByPixels(dx, dy)
        lastX = px; lastY = py
    }

    glfwSetScrollCallback(window) { _, _, yoffset ->
        val (px, py) = cursorPixel(window)
        renderer.zoomAtScreen(px, py, 1.1f.pow(yoffset.toFloat().coerceIn(-24f, 24f)))
    }

    glfwSetKeyCallback(window) { _, key, _, action, _ ->
        if (action != GLFW_PRESS) return@glfwSetKeyCallback
        when (key) {
            GLFW_KEY_SPACE -> controller.paused = !controller.paused
            GLFW_KEY_R -> controller.reset()
            GLFW_KEY_LEFT_BRACKET -> controller.speed = max(0.25f, controller.speed / 2f)
            GLFW_KEY_RIGHT_BRACKET -> controller.speed = (controller.speed * 2f).coerceAtMost(16f)
            GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(window, true)
        }
    }

    var lastTime = glfwGetTime()
    var lastFpsTime = lastTime
    var frames = 0
    var fps = 0f

    while (!glfwWindowShouldClose(window)) {
        glfwPollEvents()
        updateResolution(window, ui, renderer)

        val now = glfwGetTime()
        val delta = (now - lastTime).toFloat().coerceIn(0f, 0.25f)
        lastTime = now
        frames++
        if (now - lastFpsTime >= 1.0) {
            fps = frames.toFloat(); frames = 0; lastFpsTime = now
        }

        ui.advanceClock(delta)
        if (mouseDown && uiConsumed) {
            val (px, py) = cursorPixel(window)
            ui.updateHold(px, py, delta)
        }

        val state = controller.tick(delta)

        // Draw order: world, then UI on top of it.
        renderer.draw(state)
        hud.build(ui, controller, fps)
        ui.draw()

        glfwSwapBuffers(window)
    }

    renderer.cleanup()
    ui.cleanup()
    glfwDestroyWindow(window)
    glfwTerminate()
}

private const val DRAG_THRESHOLD = 4f

/**
 * Host-side randomness for a UI convenience (where to scatter a burst). Note that it is *not* used
 * for anything the sim depends on — a spawn position becomes an input value, which the reducer then
 * treats as data. Platform randomness inside the reducer would desync every peer.
 */
private fun randomWorld(worldSize: Float): Float = (kotlin.random.Random.nextFloat() - 0.5f) * worldSize

private fun updateResolution(window: Long, ui: Ui, renderer: TemplateRenderer) {
    MemoryStack.stackPush().use { st ->
        val w = st.mallocInt(1)
        val h = st.mallocInt(1)
        glfwGetFramebufferSize(window, w, h)
        val fw = max(1, w[0]).toFloat()
        val fh = max(1, h[0]).toFloat()
        renderer.setResolution(fw, fh)
        ui.setResolution(fw, fh)
    }
}

/** Cursor position in framebuffer pixels — the toolkit's coordinate space, HiDPI-correct. */
private fun cursorPixel(window: Long): Pair<Float, Float> {
    MemoryStack.stackPush().use { st ->
        val cx = st.mallocDouble(1)
        val cy = st.mallocDouble(1)
        glfwGetCursorPos(window, cx, cy)
        val winW = st.mallocInt(1); val winH = st.mallocInt(1)
        val fbW = st.mallocInt(1); val fbH = st.mallocInt(1)
        glfwGetWindowSize(window, winW, winH)
        glfwGetFramebufferSize(window, fbW, fbH)
        return Pair(
            cx[0].toFloat() * fbW[0] / max(1, winW[0]),
            cy[0].toFloat() * fbH[0] / max(1, winH[0]),
        )
    }
}
