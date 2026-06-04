package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.cells.CellType
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL33C
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Desktop host for the Cyto demo, mirroring [DrocketsSceneView]: a GLFW/LWJGL GL 3.3
 * window driving a [CytoController] and [CytoRenderer]. Left-drag pans or drags a cell,
 * scroll zooms, a tap spawns/acts on cells, digits 1–6 pick the touch mode, and `[`/`]`
 * cycle the cell type. F5/F9 save/load a [org.emerge.demo.cyto.CytoSaveCodec] snapshot.
 *
 * The bespoke Cyto on-screen control UI is deferred (see the port plan); the current
 * mode + cell type are shown in the window title instead.
 */
object CytoSceneView {
    private val SAVE_PATH: Path = Path.of("cyto-save.bin")

    fun start() {
        Configuration.STACK_SIZE.set(512)
        Thread { runGl() }.start()
    }

    private fun runGl() {
        val controller = CytoController()
        var mode = CytoController.TouchMode.Base
        var cellType = CellType.Stem

        val window = initWindow(
            onSave = { saveSnapshot(controller) },
            onLoad = { loadSnapshot(controller) },
            onModeKey = { win, index -> mode = TOUCH_MODES.getOrElse(index) { mode }; updateTitle(win, mode, cellType) },
            onCycleType = { win, dir -> cellType = cycleType(cellType, dir); updateTitle(win, mode, cellType) },
        )
        updateTitle(window, mode, cellType)

        val cellTextureId = CytoCellTexture.load()
        val renderer = CytoRenderer(cellTextureId)
        autoLoadSnapshotAtStartup(controller)

        installMouseHandlers(window, controller, renderer) { mode to cellType }

        var lastTime = glfwGetTime()
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateResolution(window, renderer)

            val now = glfwGetTime()
            val delta = (now - lastTime).toFloat().coerceIn(0f, 0.25f)
            lastTime = now

            val frame = controller.tick(delta)

            GL33C.glClearColor(0f, 0f, 0f, 1f)
            GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT)
            renderer.draw(frame)

            glfwSwapBuffers(window)
        }

        GPU_deleteTexture(cellTextureId)
        renderer.cleanup()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun GPU_deleteTexture(id: Int) = org.emerge.render.torus.GPU.deleteTextures(id)

    private fun initWindow(
        onSave: () -> Unit,
        onLoad: () -> Unit,
        onModeKey: (window: Long, index: Int) -> Unit,
        onCycleType: (window: Long, dir: Int) -> Unit,
    ): Long {
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
                GLFW_KEY_LEFT_BRACKET -> onCycleType(win, -1)
                GLFW_KEY_RIGHT_BRACKET -> onCycleType(win, 1)
                in GLFW_KEY_1..GLFW_KEY_6 -> onModeKey(win, key - GLFW_KEY_1)
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
        modeAndType: () -> Pair<CytoController.TouchMode, CellType>,
    ) {
        val state = MouseState()

        glfwSetMouseButtonCallback(window) { win, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            val px = cursorPixel(win)
            when (action) {
                GLFW_PRESS -> {
                    state.primaryDown = true
                    state.dragged = false
                    state.lastX = px.first
                    state.lastY = px.second
                    val (mode, _) = modeAndType()
                    val world = renderer.screenToWorld(px.first, px.second)
                    state.grab = controller.grabAt(world[0], world[1], mode)
                    state.panning = state.grab == null
                }
                GLFW_RELEASE -> {
                    state.primaryDown = false
                    if (!state.dragged) {
                        val (mode, type) = modeAndType()
                        val world = renderer.screenToWorld(px.first, px.second)
                        controller.tapAt(world[0], world[1], mode, type)
                    }
                    state.grab?.release()
                    state.grab = null
                    state.panning = false
                    state.dragged = false
                }
            }
        }

        glfwSetCursorPosCallback(window) { win, _, _ ->
            if (!state.primaryDown) return@glfwSetCursorPosCallback
            val px = cursorPixel(win)
            val dx = px.first - state.lastX
            val dy = px.second - state.lastY
            if (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX) state.dragged = true

            val grab = state.grab
            if (grab != null) {
                val world = renderer.screenToWorld(px.first, px.second)
                grab.moveTo(world[0], world[1])
            } else if (state.panning) {
                renderer.panByPixels(dx, dy)
            }
            state.lastX = px.first
            state.lastY = px.second
        }

        glfwSetScrollCallback(window) { _, _, yoffset ->
            if (yoffset == 0.0) return@glfwSetScrollCallback
            val steps = yoffset.coerceIn(-24.0, 24.0)
            renderer.zoomByFactor(1.1.pow(steps).toFloat())
        }
    }

    private fun updateResolution(window: Long, renderer: CytoRenderer) {
        MemoryStack.stackPush().use { st ->
            val sizeX = st.mallocInt(1)
            val sizeY = st.mallocInt(1)
            glfwGetFramebufferSize(window, sizeX, sizeY)
            renderer.setResolution(max(1f, sizeX[0].toFloat()), max(1f, sizeY[0].toFloat()))
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

    private fun updateTitle(window: Long, mode: CytoController.TouchMode, type: CellType) {
        glfwSetWindowTitle(window, "Cyto — mode=${mode.name}  type=${type.name}  ([ ] cycle type, 1-6 mode, F5/F9 save/load)")
    }

    private fun cycleType(current: CellType, dir: Int): CellType {
        val entries = CellType.entries
        val next = (current.ordinal + dir + entries.size) % entries.size
        return entries[next]
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
            println("Loaded Cyto snapshot (${bytes.size} bytes) from ${SAVE_PATH.toAbsolutePath()}")
        } catch (t: Throwable) {
            println("Failed loading Cyto snapshot: ${t.message}")
        }
    }

    private fun autoLoadSnapshotAtStartup(controller: CytoController) {
        if (!Files.exists(SAVE_PATH)) return
        try {
            val bytes = Files.readAllBytes(SAVE_PATH)
            controller.restoreSnapshot(bytes)
            println("Auto-loaded Cyto snapshot (${bytes.size} bytes) from ${SAVE_PATH.toAbsolutePath()}")
        } catch (t: Throwable) {
            println("Failed auto-loading Cyto snapshot: ${t.message}")
        }
    }

    private val TOUCH_MODES = CytoController.TouchMode.entries

    private class MouseState {
        var primaryDown = false
        var dragged = false
        var panning = false
        var lastX = 0f
        var lastY = 0f
        var grab: CytoController.Grab? = null
    }

    private const val DRAG_THRESHOLD_PX = 4f
}
