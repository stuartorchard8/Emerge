package org.emerge.desktop

import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.OutofspaceHud
import org.emerge.demo.outofspace.OutofspaceRenderer
import org.emerge.demo.outofspace.Tool
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Save
import org.emerge.render.torus.ui.Ui
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.max
import kotlin.math.pow

/**
 * Desktop host: a GLFW window, an input loop, and a call to the shared game every frame.
 *
 * The host owns a GL context, real time and platform input, and knows no game rules whatsoever —
 * all of those live in `:apps:outofspace:core`, which is why the Android and web hosts beside it are
 * the same loop in a different dialect.
 *
 * Pointer routing is UI first, then the world. Left-drag paints a run of machines, which makes
 * laying a belt line feel like drawing rather than clicking; middle-drag pans.
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

    val window = glfwCreateWindow(1440, 900, "Out of Space", NULL, NULL)
    if (window == NULL) error("Failed to create GLFW window")
    glfwMakeContextCurrent(window)
    glfwSwapInterval(1)
    glfwShowWindow(window)
    org.lwjgl.opengl.GL.createCapabilities()

    // Everything GL must be constructed after the context is current.
    val controller = OutofspaceController()
    val renderer = OutofspaceRenderer()
    val hud = OutofspaceHud()
    val ui = Ui()
    renderer.centreOn(controller.state)

    hud.onTogglePause = { controller.paused = !controller.paused }
    hud.onReset = { controller.reset(); renderer.centreOn(controller.state) }
    hud.canSave = true
    hud.onSave = { hud.saveStatus = saveWorld(controller) }
    hud.onLoad = { hud.saveStatus = loadWorld(controller, renderer) }

    var leftDown = false
    var middleDown = false
    var uiConsumed = false
    var lastX = 0f
    var lastY = 0f
    var hovered = -1
    // So a drag paints each tile once rather than re-issuing the same edit every frame.
    var lastPainted = -1

    glfwSetMouseButtonCallback(window) { _, button, action, _ ->
        val (px, py) = cursorPixel(window)
        when (button) {
            GLFW_MOUSE_BUTTON_LEFT -> if (action == GLFW_PRESS) {
                leftDown = true
                lastX = px; lastY = py
                uiConsumed = ui.hitTestDown(px, py)
                if (!uiConsumed) {
                    val tile = renderer.tileIndexAt(px, py, controller.state)
                    if (tile >= 0) { controller.apply(tile); lastPainted = tile }
                }
            } else {
                leftDown = false
                lastPainted = -1
                controller.endDrag()
                if (uiConsumed) { ui.hitTestUp(px, py); ui.releaseHold() }
                uiConsumed = false
            }

            GLFW_MOUSE_BUTTON_RIGHT -> if (action == GLFW_PRESS) {
                val tile = renderer.tileIndexAt(px, py, controller.state)
                if (tile >= 0) controller.remove(tile)
            }

            GLFW_MOUSE_BUTTON_MIDDLE -> {
                middleDown = action == GLFW_PRESS
                lastX = px; lastY = py
            }
        }
    }

    glfwSetCursorPosCallback(window) { _, _, _ ->
        val (px, py) = cursorPixel(window)
        ui.hover(px, py)
        hovered = renderer.tileIndexAt(px, py, controller.state)
        val dx = px - lastX
        val dy = py - lastY
        when {
            middleDown -> renderer.panByPixels(dx, dy)
            leftDown && uiConsumed -> ui.dragTo(px, py)
            // Painting a run of machines is a Build-tool gesture; a wire drag would just thrash
            // the selection, so dragging does nothing while wiring.
            // Painting a run of machines is a Build-tool gesture. For conduit it is more than that:
            // the drag is what *connects* the tiles, since track no longer joins by touching, so the
            // gesture is handed to the controller whole rather than replayed as isolated placements.
            leftDown && controller.tool == Tool.Build -> if (hovered >= 0 && hovered != lastPainted) {
                if (controller.brush.conduit != null) controller.dragTo(hovered) else controller.place(hovered)
                lastPainted = hovered
            }
        }
        lastX = px; lastY = py
    }

    glfwSetScrollCallback(window) { _, _, yoffset ->
        val (px, py) = cursorPixel(window)
        renderer.zoomAtScreen(px, py, 1.12f.pow(yoffset.toFloat().coerceIn(-24f, 24f)))
    }

    glfwSetKeyCallback(window) { _, key, _, action, _ ->
        if (action != GLFW_PRESS) return@glfwSetKeyCallback
        when (key) {
            GLFW_KEY_SPACE -> controller.paused = !controller.paused
            GLFW_KEY_R -> controller.rotateBrush()
            GLFW_KEY_H -> controller.overlay = controller.overlay.next
            GLFW_KEY_W -> controller.tool = if (controller.tool == Tool.Build) Tool.Wire else Tool.Build
            GLFW_KEY_TAB -> controller.cycleBrush(1)
            GLFW_KEY_F5 -> { controller.reset(); renderer.centreOn(controller.state) }
            GLFW_KEY_F9 -> hud.saveStatus = saveWorld(controller)
            GLFW_KEY_F10 -> hud.saveStatus = loadWorld(controller, renderer)
            GLFW_KEY_LEFT_BRACKET -> controller.speed = max(0.25f, controller.speed / 2f)
            GLFW_KEY_RIGHT_BRACKET -> controller.speed = (controller.speed * 2f).coerceAtMost(16f)
            GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(window, true)
            in GLFW_KEY_1..GLFW_KEY_9 -> MachineKind.ALL.getOrNull(key - GLFW_KEY_1)?.let { controller.brush = it }
            GLFW_KEY_0 -> MachineKind.ALL.getOrNull(9)?.let { controller.brush = it }
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
        if (now - lastFpsTime >= 1.0) { fps = frames.toFloat(); frames = 0; lastFpsTime = now }

        ui.advanceClock(delta)
        if (leftDown && uiConsumed) {
            val (px, py) = cursorPixel(window)
            ui.updateHold(px, py, delta)
        }

        val state = controller.tick(delta)

        renderer.draw(state, hovered, controller.overlay, controller.tickAlpha)
        hud.build(ui, controller, fps, hovered)
        ui.draw()

        glfwSwapBuffers(window)
    }

    renderer.cleanup()
    ui.cleanup()
    glfwDestroyWindow(window)
    glfwTerminate()
}

/**
 * Where a save goes: one well-known file beside wherever the game was started.
 *
 * One slot rather than a file picker, deliberately. The job this does is *handing a world to
 * somebody* — a reproduction of something that misbehaved — and for that, a path you can predict and
 * paste is worth more than a dialog. Slots and naming can come when there is a reason to keep two.
 */
private val SAVE_FILE = java.io.File("outofspace.save")

/** Writes the world, and says where it went — the path is the point, since it is meant to be shared. */
private fun saveWorld(controller: OutofspaceController): String = try {
    SAVE_FILE.writeText(Save.write(controller.state))
    println("saved to ${SAVE_FILE.absolutePath}")
    "saved: ${SAVE_FILE.absolutePath}"
} catch (e: Exception) {
    println("save failed: ${e.message}")
    "save failed: ${e.message}"
}

/**
 * Reads the world back, or says why it could not.
 *
 * A bad save must never take the game down with it: the file is the thing most likely to have been
 * hand-edited, so a parse failure is an ordinary outcome and belongs on screen rather than in a
 * stack trace. The running world is left exactly as it was.
 */
private fun loadWorld(controller: OutofspaceController, renderer: OutofspaceRenderer): String = try {
    val state = Save.read(SAVE_FILE.readText())
    controller.reset(state)
    renderer.centreOn(state)
    println("loaded ${SAVE_FILE.absolutePath}")
    "loaded: ${SAVE_FILE.absolutePath}"
} catch (e: Exception) {
    println("load failed: ${e.message}")
    "load failed: ${e.message}"
}

private fun updateResolution(window: Long, ui: Ui, renderer: OutofspaceRenderer) {
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
