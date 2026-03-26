package org.emerge.desktop

import org.emerge.demo.physics.*
import org.emerge.demo.physics.audio.CrashAudioSystem
import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.physics.*
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.primitives.Vec2
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.*
import kotlin.use

object DesktopGlSceneView {
    fun start(settings: LaunchSettings) = when (settings.mode) {
        LaunchMode.LOCAL -> Thread {
            val controller = PhysicsHostController(
                port = settings.port,
                cfg = PhysicsConfig(),
                gameMode = settings.gameMode,
                acceptRemoteClients = false,
            )
            runGl("Emerge local", controller)
        }.start()
        LaunchMode.HOST -> Thread {
            val controller = PhysicsHostController(
                port = settings.port,
                cfg = PhysicsConfig(),
                gameMode = settings.gameMode,
                acceptRemoteClients = true,
            )
            runGl("Emerge host (:${settings.port})", controller)
        }.start()
        LaunchMode.JOIN -> Thread {
            val controller = PhysicsJoinController(
                hostIp = settings.hostIp,
                port = settings.port,
            )
            runGl("Emerge join (${settings.hostIp}:${settings.port})", controller)
        }.start()
    }

    private fun runGl(title: String, controller: PhysicsController) {
        val pressedKeys = BooleanArray(512)

        val window = initWindow(title, pressedKeys)
        val dpiX = FloatArray(1)
        val dpiY = FloatArray(1)
        glfwGetWindowContentScale(window, dpiX, dpiY)
        val screenRenderer = ScreenRenderer(Vec2(dpiX[0], dpiY[0]))
        val crashAudio = CrashAudioSystem(DesktopOggCrashAudioEngine())

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateResolution(window, screenRenderer)

            val frame = processInput(controller, pressedKeys, screenRenderer)
            crashAudio.onFrame(frame)
            screenRenderer.draw(frame.state, frame.myId)

            glfwSwapBuffers(window)
        }

        crashAudio.release()
        screenRenderer.cleanup()
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

    private fun updateResolution(window: Long, screenRenderer: ScreenRenderer) {
        // Respond to window size changes
        MemoryStack.stackPush().use { st ->
            val sizeX = st.mallocInt(1)
            val sizeY = st.mallocInt(1)
            glfwGetFramebufferSize(window, sizeX, sizeY)

            screenRenderer.setResolution(
                Vec2(
                    max(1f, sizeX[0].toFloat()),
                    max(1f, sizeY[0].toFloat()),
                )
            )
        }
    }

    private fun processInput(
        controller: PhysicsController,
        pressed: BooleanArray,
        screenRenderer: ScreenRenderer,
    ): PhysicsFrame {
        // zoom controls: '-' zoom out, '=' zoom in
        if (pressed[GLFW_KEY_MINUS]) screenRenderer.zoomOut()
        if (pressed[GLFW_KEY_EQUAL]) screenRenderer.zoomIn()
        // camera rotation: Q left, E right
        if (pressed[GLFW_KEY_Q]) screenRenderer.rotateLeft()
        if (pressed[GLFW_KEY_E]) screenRenderer.rotateRight()

        // Rocket controls: W/Up thrust forward, A/D or Left/Right rotate.
        val thrust = if (pressed[GLFW_KEY_W] || pressed[GLFW_KEY_UP]) Int.MAX_VALUE else 0
        val turn = axis(
            pressed[GLFW_KEY_A] || pressed[GLFW_KEY_LEFT],
            pressed[GLFW_KEY_D] || pressed[GLFW_KEY_RIGHT],
        )
        val movementInput = PhysicsInput(thrust = thrust, turn = turn)

        return controller.tick(movementInput)
    }
}
