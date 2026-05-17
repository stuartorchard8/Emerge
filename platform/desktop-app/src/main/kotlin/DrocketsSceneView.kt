package org.emerge.desktop

import org.emerge.demo.drockets.CladogramFilterMode
import org.emerge.demo.drockets.CladogramLayoutMode
import org.emerge.demo.drockets.DrocketsController
import org.emerge.demo.drockets.DrocketsFrame
import org.emerge.demo.drockets.DrocketsRenderer
import org.emerge.render.torus.GPU
import org.emerge.sim.core.physics.primitives.Vec2
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.pow

object DrocketsSceneView {
    private val SAVE_PATH: Path = Path.of("drockets-save.bin")
    /**
     * Lineage overlay UI prefs persistence. Kept separate from the sim snapshot so
     * tweaking layout settings doesn't require resaving the world, and so the prefs
     * can be diffed / hand-edited as plain text.
     */
    private val PREFS_PATH: Path = Path.of("drockets-prefs.txt")
    @Volatile
    private var activeRenderer: DrocketsRenderer? = null

    fun start() {
        Configuration.STACK_SIZE.set(512); // Size in KB
        Thread {
            runGl()
        }.start()
    }

    private fun runGl() {
        val pressedKeys = BooleanArray(512)
        val controller = DrocketsController()
        val window = initWindow("Drockets", pressedKeys, controller)
        val dpiX = FloatArray(1)
        val dpiY = FloatArray(1)
        glfwGetWindowContentScale(window, dpiX, dpiY)

        val drocketSpriteAtlasTextureId = DrocketsSpriteAtlas.load()
        val knightSpriteAtlasTextureId = KnightSpriteAtlas.load()

        val renderer = DrocketsRenderer(
            contentScale = Vec2(dpiX[0], dpiY[0]),
            drocketSpriteAtlasTextureId = drocketSpriteAtlasTextureId,
            knightSpriteAtlasTextureId = knightSpriteAtlasTextureId,
        )
        activeRenderer = renderer
        autoLoadSnapshotAtStartup(controller)
        autoLoadOverlayPrefsAtStartup(renderer)

        var latestFrame: DrocketsFrame? = null
        installMouseHandlers(window, renderer) { latestFrame }

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateResolution(window, renderer)
            processCamera(pressedKeys, renderer)

            val frame = controller.tick()
            latestFrame = frame
            renderer.draw(frame)

            glfwSwapBuffers(window)
        }

        GPU.deleteTextures(drocketSpriteAtlasTextureId)
        GPU.deleteTextures(knightSpriteAtlasTextureId)
        renderer.cleanup()
        activeRenderer = null
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun initWindow(
        title: String,
        pressedKeys: BooleanArray,
        controller: DrocketsController,
    ): Long {
        if (!glfwInit()) error("GLFW init failed")
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

        val window = glfwCreateWindow(960, 600, title, NULL, NULL)
        if (window == NULL) error("Failed to create GLFW window")

        glfwSetKeyCallback(window) { win, key, _, action, mods ->
            if (key in 0 until pressedKeys.size) {
                pressedKeys[key] = (action != GLFW_RELEASE)
            }
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(win, true)
            }
            if (action == GLFW_PRESS && key == GLFW_KEY_F5) {
                saveSnapshot(controller)
            }
            if (action == GLFW_PRESS && key == GLFW_KEY_F9) {
                loadSnapshot(controller)
            }
            if (action == GLFW_PRESS && key == GLFW_KEY_F3) {
                activeRenderer?.togglePhenotypeDebugHud()
            }
            if (action == GLFW_PRESS && key == GLFW_KEY_F2) {
                activeRenderer?.toggleLineageOverlay()
                saveOverlayPrefs(activeRenderer)
            }
            if (action == GLFW_PRESS && key == GLFW_KEY_F6) {
                activeRenderer?.cycleLineageOverlayFilter()
                saveOverlayPrefs(activeRenderer)
            }
            if (action == GLFW_PRESS && key == GLFW_KEY_F7) {
                activeRenderer?.cycleLineageOverlayLayoutMode()
                saveOverlayPrefs(activeRenderer)
            }
            // Ctrl+Up / Ctrl+Down step the force-directed solver's relaxation rate
            // by ×2 / ÷2. Press+repeat both fire so holding the chord ramps quickly;
            // the solver clamps to a finite range so a held key won't blow up.
            val ctrl = (mods and GLFW_MOD_CONTROL) != 0
            val pressOrRepeat = action == GLFW_PRESS || action == GLFW_REPEAT
            if (ctrl && pressOrRepeat && key == GLFW_KEY_UP) {
                activeRenderer?.nudgeLineageOverlayForceScale(2f)
                saveOverlayPrefs(activeRenderer)
            }
            if (ctrl && pressOrRepeat && key == GLFW_KEY_DOWN) {
                activeRenderer?.nudgeLineageOverlayForceScale(0.5f)
                saveOverlayPrefs(activeRenderer)
            }
        }

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        org.lwjgl.opengl.GL.createCapabilities()
        return window
    }

    private fun saveSnapshot(controller: DrocketsController) {
        try {
            val bytes = controller.snapshotBytes()
            Files.write(SAVE_PATH, bytes)
            activeRenderer?.setOverlayStatus("Saved (${bytes.size} bytes)")
            println("Saved Drockets snapshot (${bytes.size} bytes) to ${SAVE_PATH.toAbsolutePath()}")
        } catch (t: Throwable) {
            activeRenderer?.setOverlayStatus("Save failed: ${t.message ?: "unknown error"}", durationMs = 4_000)
            println("Failed saving Drockets snapshot: ${t.message}")
        }
    }

    private fun loadSnapshot(controller: DrocketsController) {
        try {
            if (!Files.exists(SAVE_PATH)) {
                activeRenderer?.setOverlayStatus("No save file found", durationMs = 3_000)
                println("No Drockets snapshot found at ${SAVE_PATH.toAbsolutePath()}")
                return
            }
            val bytes = Files.readAllBytes(SAVE_PATH)
            controller.restoreSnapshot(bytes)
            activeRenderer?.setOverlayStatus("Loaded (${bytes.size} bytes)")
            println("Loaded Drockets snapshot (${bytes.size} bytes) from ${SAVE_PATH.toAbsolutePath()}")
        } catch (t: Throwable) {
            activeRenderer?.setOverlayStatus("Load failed: ${t.message ?: "unknown error"}", durationMs = 4_000)
            println("Failed loading Drockets snapshot: ${t.message}")
        }
    }

    private fun autoLoadSnapshotAtStartup(controller: DrocketsController) {
        if (!Files.exists(SAVE_PATH)) return
        try {
            val bytes = Files.readAllBytes(SAVE_PATH)
            controller.restoreSnapshot(bytes)
            activeRenderer?.setOverlayStatus("Auto-loaded save (${bytes.size} bytes)", durationMs = 3_500)
            println("Auto-loaded Drockets snapshot (${bytes.size} bytes) from ${SAVE_PATH.toAbsolutePath()}")
        } catch (t: Throwable) {
            activeRenderer?.setOverlayStatus("Auto-load failed: ${t.message ?: "unknown error"}", durationMs = 4_000)
            println("Failed auto-loading Drockets snapshot: ${t.message}")
        }
    }

    /**
     * Persists the lineage-overlay UI state to [PREFS_PATH]. Called after any F2/F6/F7
     * toggle or Ctrl+Up/Down nudge so the user's settings survive between runs without
     * needing an explicit save. Failures are logged but don't surface in the UI — losing
     * a prefs write isn't worth interrupting the user for.
     */
    private fun saveOverlayPrefs(renderer: DrocketsRenderer?) {
        if (renderer == null) return
        try {
            val text = buildString {
                appendLine("# drockets-prefs — written by DrocketsSceneView on every overlay toggle.")
                appendLine("overlay.active=${renderer.isLineageOverlayActive}")
                appendLine("overlay.filter=${renderer.lineageOverlayFilter.name}")
                appendLine("overlay.layoutMode=${renderer.lineageOverlayLayoutMode.name}")
                appendLine("overlay.forceScale=${renderer.lineageOverlayForceScale}")
            }
            Files.write(PREFS_PATH, text.toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            println("Failed saving Drockets overlay prefs: ${t.message}")
        }
    }

    /**
     * Reads [PREFS_PATH] and applies it to [renderer]. Missing keys, unparseable values,
     * and missing-file all silently fall through to current defaults — prefs are a
     * nice-to-have, not a hard dependency.
     */
    private fun autoLoadOverlayPrefsAtStartup(renderer: DrocketsRenderer) {
        if (!Files.exists(PREFS_PATH)) return
        try {
            val text = Files.readAllBytes(PREFS_PATH).toString(Charsets.UTF_8)
            val kv = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq < 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
                }
                .toMap()
            val active = kv["overlay.active"]?.toBooleanStrictOrNull()
                ?: renderer.isLineageOverlayActive
            val filter = kv["overlay.filter"]?.let { runCatching { CladogramFilterMode.valueOf(it) }.getOrNull() }
                ?: renderer.lineageOverlayFilter
            val layoutMode = kv["overlay.layoutMode"]?.let { runCatching { CladogramLayoutMode.valueOf(it) }.getOrNull() }
                ?: renderer.lineageOverlayLayoutMode
            val forceScale = kv["overlay.forceScale"]?.toFloatOrNull()
                ?: renderer.lineageOverlayForceScale
            renderer.applyLineageOverlayPrefs(
                active = active,
                filter = filter,
                layoutMode = layoutMode,
                forceScale = forceScale,
            )
            println("Auto-loaded Drockets overlay prefs from ${PREFS_PATH.toAbsolutePath()}")
        } catch (t: Throwable) {
            println("Failed auto-loading Drockets overlay prefs: ${t.message}")
        }
    }

    private fun installMouseHandlers(
        window: Long,
        renderer: DrocketsRenderer,
        latestFrame: () -> DrocketsFrame?,
    ) {
        val state = MouseState()

        glfwSetMouseButtonCallback(window) { win, button, action, mods ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            val px = cursorPixel(win)
            val shift = (mods and GLFW_MOD_SHIFT) != 0
            when (action) {
                GLFW_PRESS -> {
                    state.primaryDown = true
                    state.dragged = false
                    state.dragStartX = px.x
                    state.dragStartY = px.y
                    state.lastCursorX = px.x
                    state.lastCursorY = px.y
                }
                GLFW_RELEASE -> {
                    state.primaryDown = false
                    if (!state.dragged) {
                        val frame = latestFrame() ?: return@glfwSetMouseButtonCallback
                        val now = System.currentTimeMillis()
                        val withinTime = (now - state.lastClickTimeMs) < DOUBLE_CLICK_MS
                        val withinSpace =
                            kotlin.math.abs(px.x - state.lastClickPxX) < DOUBLE_CLICK_PX &&
                                kotlin.math.abs(px.y - state.lastClickPxY) < DOUBLE_CLICK_PX
                        val isDoubleClick = withinTime && withinSpace
                        if (isDoubleClick && renderer.isLineageOverlayActive) {
                            renderer.handleLineageOverlayDoubleClick(frame, px)
                            // Reset so a third quick click doesn't chain.
                            state.lastClickTimeMs = 0L
                        } else {
                            renderer.handlePrimaryClick(frame, px, shift = shift)
                            state.lastClickTimeMs = now
                            state.lastClickPxX = px.x
                            state.lastClickPxY = px.y
                        }
                    }
                    state.dragged = false
                }
            }
        }

        glfwSetCursorPosCallback(window) { win, _, _ ->
            val px = cursorPixel(win)
            val frame = latestFrame()
            if (state.primaryDown && renderer.isLineageOverlayActive) {
                val dx = px.x - state.lastCursorX
                val dy = px.y - state.lastCursorY
                if (!state.dragged) {
                    val totalDx = px.x - state.dragStartX
                    val totalDy = px.y - state.dragStartY
                    if (kotlin.math.abs(totalDx) > DRAG_THRESHOLD_PX ||
                        kotlin.math.abs(totalDy) > DRAG_THRESHOLD_PX
                    ) {
                        state.dragged = true
                    }
                }
                if (state.dragged) {
                    renderer.panLineageOverlayByPixels(dx, dy)
                }
            } else if (renderer.isLineageOverlayActive && frame != null) {
                renderer.hoverLineageOverlay(px, frame)
            }
            state.lastCursorX = px.x
            state.lastCursorY = px.y
        }

        // Scroll up (positive y) zooms in. Lineage overlay takes priority when active so
        // wheel-zoom navigates the tree; otherwise wheel zooms the world camera.
        glfwSetScrollCallback(window) { win, _, yoffset ->
            if (yoffset == 0.0) return@glfwSetScrollCallback
            val steps = yoffset.coerceIn(-24.0, 24.0)
            val factor = 1.1.pow(steps).toFloat()
            val px = cursorPixel(win)
            if (renderer.isLineageOverlayActive) {
                renderer.zoomLineageOverlayAtCursor(px, factor)
                return@glfwSetScrollCallback
            }
            renderer.zoomByFactor(factor)
        }
    }

    private fun updateResolution(window: Long, renderer: DrocketsRenderer) {
        MemoryStack.stackPush().use { st ->
            val sizeX = st.mallocInt(1)
            val sizeY = st.mallocInt(1)
            glfwGetFramebufferSize(window, sizeX, sizeY)
            renderer.setResolution(
                Vec2(
                    max(1f, sizeX[0].toFloat()),
                    max(1f, sizeY[0].toFloat()),
                )
            )
        }
    }

    private fun processCamera(pressed: BooleanArray, renderer: DrocketsRenderer) {
        if (pressed[GLFW_KEY_Q]) renderer.rotateLeft()
        if (pressed[GLFW_KEY_E]) renderer.rotateRight()
        if (pressed[GLFW_KEY_0]) renderer.focusPlanet()

        // Arrow keys pan the lineage overlay while it's active. Signs follow the
        // "look in this direction" convention (pressing Right shifts the camera
        // right, so content scrolls left); the deltas are passed in the same
        // screen-pixel convention as the mouse-drag handler, so positive dx pushes
        // content right and positive dy pushes content down. Skipped while Ctrl is
        // held so Ctrl+Up/Down still nudges force scale instead of panning.
        val ctrl = pressed[GLFW_KEY_LEFT_CONTROL] || pressed[GLFW_KEY_RIGHT_CONTROL]
        if (renderer.isLineageOverlayActive && !ctrl) {
            var dx = 0f
            var dy = 0f
            if (pressed[GLFW_KEY_LEFT]) dx += OVERLAY_PAN_SPEED_PX
            if (pressed[GLFW_KEY_RIGHT]) dx -= OVERLAY_PAN_SPEED_PX
            if (pressed[GLFW_KEY_UP]) dy += OVERLAY_PAN_SPEED_PX
            if (pressed[GLFW_KEY_DOWN]) dy -= OVERLAY_PAN_SPEED_PX
            if (dx != 0f || dy != 0f) renderer.panLineageOverlayByPixels(dx, dy)
        }
    }

    /**
     * Reads the cursor position in framebuffer-pixel space. GLFW reports cursor coords
     * in window units, which may differ from framebuffer units on high-DPI displays;
     * rescaling by the framebuffer-to-window ratio keeps coordinates consistent with the
     * renderer's resolution.
     */
    private fun cursorPixel(win: Long): Vec2 {
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
        return Vec2(
            cursorX[0].toFloat() * framebufferW[0].toFloat() / w.toFloat(),
            cursorY[0].toFloat() * framebufferH[0].toFloat() / h.toFloat(),
        )
    }

    private class MouseState {
        var primaryDown: Boolean = false
        var dragged: Boolean = false
        var dragStartX: Float = 0f
        var dragStartY: Float = 0f
        var lastCursorX: Float = 0f
        var lastCursorY: Float = 0f
        var lastClickTimeMs: Long = 0L
        var lastClickPxX: Float = 0f
        var lastClickPxY: Float = 0f
    }

    private const val DOUBLE_CLICK_MS = 400L
    private const val DOUBLE_CLICK_PX = 6f
    private const val DRAG_THRESHOLD_PX = 4f
    /** Per-frame pan delta (in screen pixels) applied while an arrow key is held. */
    private const val OVERLAY_PAN_SPEED_PX = 10f
}
