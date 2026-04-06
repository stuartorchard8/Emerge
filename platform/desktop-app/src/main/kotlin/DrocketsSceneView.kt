package org.emerge.desktop

import org.emerge.demo.drockets.DrocketsController
import org.emerge.demo.drockets.DrocketsRenderer
import org.emerge.render.torus.GPU
import org.emerge.sim.core.physics.primitives.Vec2
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.max

object DrocketsSceneView {
    fun start() {
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

        val spriteAtlasTextureId = DrocketsSpriteAtlas.load()

        val renderer = DrocketsRenderer(
            contentScale = Vec2(dpiX[0], dpiY[0]),
            spriteAtlasTextureId = spriteAtlasTextureId,
            spriteAtlasColumns = 3,
            spriteAtlasRows = 1,
        )
        repeat(120) { renderer.zoomOut() }

        val controller = DrocketsController()

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateResolution(window, renderer)
            processCamera(pressedKeys, renderer)

            val frame = controller.tick()
            renderer.draw(frame.state)

            glfwSwapBuffers(window)
        }

        GPU.deleteTextures(spriteAtlasTextureId)
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
        if (pressed[GLFW_KEY_MINUS]) renderer.zoomOut()
        if (pressed[GLFW_KEY_EQUAL]) renderer.zoomIn()
        if (pressed[GLFW_KEY_Q]) renderer.rotateLeft()
        if (pressed[GLFW_KEY_E]) renderer.rotateRight()
    }
}
