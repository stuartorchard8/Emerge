package org.emerge.desktop

import org.emerge.demo.drockets.DrocketsController
import org.emerge.demo.drockets.DrocketsFrame
import org.emerge.demo.drockets.DrocketsRenderer
import org.emerge.render.torus.GPU
import org.emerge.sim.core.physics.primitives.Vec2
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.max
import kotlin.math.pow

object DrocketsSceneView {
    fun start() {
        Configuration.STACK_SIZE.set(512); // Size in KB
        Thread {
            runGl()
        }.start()
    }

    private fun runGl() {
        val pressedKeys = BooleanArray(512)
        val window = initWindow("Drockets", pressedKeys)
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

        val controller = DrocketsController()
        var latestFrame: DrocketsFrame? = null
        installMouseHandlers(window, renderer) { latestFrame }

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateResolution(window, renderer)
            processCamera(pressedKeys, renderer)

            val frame = controller.tick()
            latestFrame = frame
            renderer.draw(frame.state)

            glfwSwapBuffers(window)
        }

        GPU.deleteTextures(drocketSpriteAtlasTextureId)
        GPU.deleteTextures(knightSpriteAtlasTextureId)
        renderer.cleanup()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun initWindow(title: String, pressedKeys: BooleanArray): Long {
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

        glfwSetKeyCallback(window) { win, key, _, action, _ ->
            if (key in 0 until pressedKeys.size) {
                pressedKeys[key] = (action != GLFW_RELEASE)
            }
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(win, true)
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
        renderer: DrocketsRenderer,
        latestFrame: () -> DrocketsFrame?,
    ) {
        glfwSetMouseButtonCallback(window) { win, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT || action != GLFW_PRESS) return@glfwSetMouseButtonCallback
            val frame = latestFrame() ?: return@glfwSetMouseButtonCallback

            val cursorX = DoubleArray(1)
            val cursorY = DoubleArray(1)
            glfwGetCursorPos(win, cursorX, cursorY)

            val windowW = IntArray(1)
            val windowH = IntArray(1)
            val framebufferW = IntArray(1)
            val framebufferH = IntArray(1)
            glfwGetWindowSize(win, windowW, windowH)
            glfwGetFramebufferSize(win, framebufferW, framebufferH)
            if (windowW[0] <= 0 || windowH[0] <= 0) return@glfwSetMouseButtonCallback

            renderer.tryFocusDrocketAt(
                state = frame.state,
                pixel = Vec2(
                    cursorX[0].toFloat() * framebufferW[0].toFloat() / windowW[0].toFloat(),
                    cursorY[0].toFloat() * framebufferH[0].toFloat() / windowH[0].toFloat(),
                ),
            )
        }

        // Scroll up (positive y) zooms in
        glfwSetScrollCallback(window) { _, _, yoffset ->
            if (yoffset == 0.0) return@glfwSetScrollCallback
            val steps = yoffset.coerceIn(-24.0, 24.0)
            renderer.zoomByFactor(1.1.pow(steps).toFloat())
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
    }
}
