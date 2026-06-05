package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.sim.core.EntityId
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Desktop host for the native Cyto demo. Drives a [CytoController] + [CytoRenderer] and
 * draws the on-screen [CytoControls] overlay (the faithful Cyto control UI). Pointer-down
 * goes to the UI first; if it misses, a press on a cell grabs it (Sticky/Detach hold-mode
 * effects applied), a press on empty space pans, scroll zooms, and a click spawns/acts per
 * the controls' current mode + cell type. F5/F9 save/load.
 */
object CytoSceneView {
    private val SAVE_PATH: Path = Path.of("cyto-save.bin")

    fun start() {
        Configuration.STACK_SIZE.set(512)
        Thread { runGl() }.start()
    }

    private fun runGl() {
        val controller = CytoController()

        // GL context must be current (initWindow) before any shader/texture is created.
        val window = initWindow(onSave = { saveSnapshot(controller) }, onLoad = { loadSnapshot(controller) })

        val renderer = CytoRenderer()
        val controls = CytoControls()
        autoLoadSnapshotAtStartup(controller)

        val mouse = MouseState()
        installMouseHandlers(window, controller, renderer, controls, mouse)

        var lastTime = glfwGetTime()
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateResolution(window, renderer, controls)

            val now = glfwGetTime()
            val delta = (now - lastTime).toFloat().coerceIn(0f, 0.25f)
            lastTime = now

            val frame = controller.tick(delta)

            renderer.draw(frame) // renderer fills its own background
            drawReadouts(controller, renderer, controls, mouse.grabId)
            controls.draw()

            glfwSwapBuffers(window)
        }

        renderer.cleanup()
        controls.cleanup()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun drawReadouts(
        controller: CytoController,
        renderer: CytoRenderer,
        controls: CytoControls,
        grabId: EntityId?,
    ) {
        val readouts = controller.readouts(grabId, controls.showChemicals)
        if (readouts.isEmpty()) return
        for (r in readouts) {
            val screen = renderer.worldToScreen(r.x, r.y)
            controls.drawLabel(r.text, screen[0], screen[1] - 28f, pixelHeight = 12f, color = 0x00FF22FF)
        }
    }

    private fun initWindow(onSave: () -> Unit, onLoad: () -> Unit): Long {
        if (!glfwInit()) error("GLFW init failed")
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

        val window = glfwCreateWindow(720, 720, "Cyto", NULL, NULL)
        if (window == NULL) error("Failed to create GLFW window")

        glfwSetKeyCallback(window) { win, key, _, action, _ ->
            if (action != GLFW_PRESS) return@glfwSetKeyCallback
            when (key) {
                GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win, true)
                GLFW_KEY_F5 -> onSave()
                GLFW_KEY_F9 -> onLoad()
            }
        }

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        org.lwjgl.opengl.GL.createCapabilities()
        return window
    }

    private fun installMouseHandlers(
        window: Long,
        controller: CytoController,
        renderer: CytoRenderer,
        controls: CytoControls,
        state: MouseState,
    ) {
        glfwSetMouseButtonCallback(window) { win, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            val px = cursorPixel(win)
            when (action) {
                GLFW_PRESS -> {
                    state.dragged = false
                    state.lastX = px.first
                    state.lastY = px.second
                    // UI first: a hit consumes the press.
                    if (controls.hitTest(px.first, px.second)) {
                        state.uiConsumed = true
                        state.grabId = null
                        return@glfwSetMouseButtonCallback
                    }
                    state.uiConsumed = false
                    val world = renderer.screenToWorld(px.first, px.second)
                    val hit = controller.cellAt(world[0], world[1])
                    state.grabId = hit
                    if (hit != null && controls.touchMode == TouchMode.Detach) controller.detach(hit)
                }
                GLFW_RELEASE -> {
                    if (!state.uiConsumed && !state.dragged) {
                        val world = renderer.screenToWorld(px.first, px.second)
                        controller.tap(world[0], world[1], controls.touchMode, controls.cellType)
                    }
                    controller.releaseGrab()
                    state.grabId = null
                    state.dragged = false
                    state.uiConsumed = false
                }
            }
        }

        glfwSetCursorPosCallback(window) { win, _, _ ->
            if (state.uiConsumed) return@glfwSetCursorPosCallback
            // Only react while the primary button is held (grabId set on a cell, else pan).
            if (!isPrimaryDown(win)) return@glfwSetCursorPosCallback
            val px = cursorPixel(win)
            val dx = px.first - state.lastX
            val dy = px.second - state.lastY
            if (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX) state.dragged = true

            val grabId = state.grabId
            if (grabId != null) {
                val world = renderer.screenToWorld(px.first, px.second)
                controller.grab(grabId, world[0], world[1], sticky = controls.touchMode == TouchMode.Sticky)
            } else {
                renderer.panByPixels(dx, dy)
            }
            state.lastX = px.first
            state.lastY = px.second
        }

        glfwSetScrollCallback(window) { win, _, yoffset ->
            if (yoffset == 0.0) return@glfwSetScrollCallback
            val steps = yoffset.coerceIn(-24.0, 24.0)
            val px = cursorPixel(win)
            renderer.zoomAtScreen(px.first, px.second, 1.1.pow(steps).toFloat())
        }
    }

    private fun isPrimaryDown(win: Long): Boolean =
        glfwGetMouseButton(win, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS

    private fun updateResolution(window: Long, renderer: CytoRenderer, controls: CytoControls) {
        MemoryStack.stackPush().use { st ->
            val sizeX = st.mallocInt(1)
            val sizeY = st.mallocInt(1)
            glfwGetFramebufferSize(window, sizeX, sizeY)
            val w = max(1f, sizeX[0].toFloat())
            val h = max(1f, sizeY[0].toFloat())
            renderer.setResolution(w, h)
            controls.setResolution(w, h)
        }
    }

    private fun cursorPixel(win: Long): Pair<Float, Float> {
        val cursorX = DoubleArray(1)
        val cursorY = DoubleArray(1)
        glfwGetCursorPos(win, cursorX, cursorY)
        val windowW = IntArray(1)
        val windowH = IntArray(1)
        val framebufferW = IntArray(1)
        val framebufferH = IntArray(1)
        glfwGetWindowSize(win, windowW, windowH)
        glfwGetFramebufferSize(win, framebufferW, framebufferH)
        val w = windowW[0].coerceAtLeast(1)
        val h = windowH[0].coerceAtLeast(1)
        return Pair(
            cursorX[0].toFloat() * framebufferW[0].toFloat() / w.toFloat(),
            cursorY[0].toFloat() * framebufferH[0].toFloat() / h.toFloat(),
        )
    }

    private fun saveSnapshot(controller: CytoController) {
        try {
            val bytes = controller.snapshotBytes()
            Files.write(SAVE_PATH, bytes)
            println("Saved Cyto snapshot (${bytes.size} bytes) to ${SAVE_PATH.toAbsolutePath()}")
        } catch (t: Throwable) {
            println("Failed saving Cyto snapshot: ${t.message}")
        }
    }

    private fun loadSnapshot(controller: CytoController) {
        try {
            if (!Files.exists(SAVE_PATH)) {
                println("No Cyto snapshot found at ${SAVE_PATH.toAbsolutePath()}")
                return
            }
            val bytes = Files.readAllBytes(SAVE_PATH)
            controller.restoreSnapshot(bytes)
            println("Loaded Cyto snapshot (${bytes.size} bytes)")
        } catch (t: Throwable) {
            println("Failed loading Cyto snapshot: ${t.message}")
        }
    }

    private fun autoLoadSnapshotAtStartup(controller: CytoController) {
        if (!Files.exists(SAVE_PATH)) return
        try {
            controller.restoreSnapshot(Files.readAllBytes(SAVE_PATH))
            println("Auto-loaded Cyto snapshot")
        } catch (t: Throwable) {
            println("Failed auto-loading Cyto snapshot: ${t.message}")
        }
    }

    private class MouseState {
        var dragged = false
        var uiConsumed = false
        var lastX = 0f
        var lastY = 0f
        var grabId: EntityId? = null
    }

    private const val DRAG_THRESHOLD_PX = 4f
}
