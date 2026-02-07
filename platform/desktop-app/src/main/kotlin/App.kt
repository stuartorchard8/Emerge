package org.emerge.desktop

import java.awt.*
import javax.swing.*
import org.emerge.demo.physics.*
import org.emerge.render.torus.*
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusOrthoCamera2D
import org.emerge.sim.core.physics.*
import org.emerge.sim.core.space.Torus2D
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.event.*
import kotlin.math.*
import kotlin.use

internal const val MAX_BODIES = 128

fun main(args: Array<String>) {
    // Single launch path: always start with an in-app launcher UI.
    // (Args are intentionally ignored now; configure Host/Join/Local from the launcher screen.)
    SwingUtilities.invokeLater { DesktopLauncher().show() }
}

private class DesktopLauncher {
    private val modeBox = JComboBox(LaunchMode.entries.map(LaunchMode::name).toTypedArray())
    private val hostIpField = JTextField("127.0.0.1", 16)
    private val portField = JTextField("7777", 6)

    private val frame = JFrame("Emerge - Launcher").apply {
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        contentPane = JPanel(GridBagLayout()).apply {
            background = Color(0x11, 0x11, 0x11)
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(8, 8, 8, 8)
                weightx = 1.0
            }

            fun row(y: Int, label: String, comp: Component) {
                c.gridy = y
                c.gridx = 0
                c.weightx = 0.0
                add(JLabel(label).apply { foreground = Color(0xEE, 0xEE, 0xEE) }, c)
                c.gridx = 1
                c.weightx = 1.0
                add(comp, c)
            }

            row(0, "Mode", modeBox)
            row(2, "Host IP (Join)", hostIpField)
            row(3, "Port", portField)

            val start = JButton("Start").apply {
                addActionListener {
                    val settings = readSettings()
                    dispose()
                    startDemo(settings)
                }
            }

            c.gridy = 4
            c.gridx = 0
            c.gridwidth = 2
            add(start, c)

            modeBox.addItemListener { syncEnabledFields() }
            syncEnabledFields()
        }

        pack()
        isLocationByPlatform = true
        isResizable = false
    }

    fun show() {
        val defaultLaunchSettings = LaunchSettings()
        modeBox.selectedItem = defaultLaunchSettings.mode.name
        hostIpField.text = defaultLaunchSettings.hostIp
        portField.text = defaultLaunchSettings.port.toString()
        frame.isVisible = true
    }

    private fun syncEnabledFields() {
        val mode = selectedMode()
        hostIpField.isEnabled = (mode == LaunchMode.JOIN)
    }

    private fun selectedMode(): LaunchMode =
        when (modeBox.selectedIndex) {
            1 -> LaunchMode.HOST
            2 -> LaunchMode.JOIN
            else -> LaunchMode.LOCAL
        }

    private fun readSettings(): LaunchSettings {
        val port = portField.text.trim().toIntOrNull() ?: 7777
        val hostIp = hostIpField.text.trim().ifBlank { "127.0.0.1" }
        return LaunchSettings(
            mode = selectedMode(),
            hostIp = hostIp,
            port = port,
        )
    }

    private fun startDemo(settings: LaunchSettings) {
        when (settings.mode) {
            LaunchMode.LOCAL -> Thread { runLocalGl(settings.port) }.start()
            LaunchMode.HOST -> Thread { runHostGl(settings.port) }.start()
            LaunchMode.JOIN -> Thread { runJoinGl(hostIp = settings.hostIp, port = settings.port) }.start()
        }
    }

    fun runLocalGl(port: Int) {
        val controller = PhysicsAuthoritativeHostController(port = port, cfg = PhysicsDemoConfig(), acceptRemoteClients = false)
        runGl("Emerge local-gl", controller)
    }

    fun runHostGl(port: Int) {
        val controller = PhysicsAuthoritativeHostController(port = port, cfg = PhysicsDemoConfig(), acceptRemoteClients = true)
        runGl("Emerge host-gl (:$port)", controller)
    }

    fun runJoinGl(hostIp: String, port: Int) {
        val controller = PhysicsAuthoritativeJoinController(hostIp = hostIp, port = port)
        runGl("Emerge join-gl ($hostIp:$port)", controller)
    }

    fun runGl(title: String, controller: PhysicsAuthoritativeController) {
        if (!glfwInit()) error("GLFW init failed")
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)

        val window = glfwCreateWindow(960, 600, title, NULL, NULL)
        if (window == NULL) error("Failed to create GLFW window")

        val pressed = BooleanArray(512)
        glfwSetKeyCallback(window) { win, key, _, action, _ ->
            if (key in 0 until pressed.size) {
                pressed[key] = (action != GLFW_RELEASE)
            }
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(win, true)
            }
        }

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        GL.createCapabilities()

        val program = TorusGlProgramFactory.createProgramGl330(MAX_BODIES)
        glUseProgram(program)

        // Fullscreen triangle (no VBO needed), but some drivers want a VAO bound in core profile.
        val vao = glGenVertexArrays()
        glBindVertexArray(vao)

        // Uniform locations
        val uResolution = glGetUniformLocation(program, "uResolution")
        val uWorld = glGetUniformLocation(program, "uWorld")
        val uView = glGetUniformLocation(program, "uView")
        val uCenter = glGetUniformLocation(program, "uCenter")
        val uBodyCount = glGetUniformLocation(program, "uBodyCount")
        val uMyId = glGetUniformLocation(program, "uMyId")
        val uBodies = glGetUniformLocation(program, "uBodies")

        var zoom = 0.75f // <1 => zoom out (see multiple tiles)
        val view = TorusViewComputer()
        val bodiesFloats = FloatArray(4 * MAX_BODIES)

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()

            // zoom controls: '-' zoom out, '=' zoom in
            if (pressed[GLFW_KEY_MINUS]) zoom = max(0.05f, zoom * 0.98f)
            if (pressed[GLFW_KEY_EQUAL]) zoom = min(20f, zoom * 1.02f)

            // WASD input
            val ax = axis(pressed[GLFW_KEY_A], pressed[GLFW_KEY_D])
            val ay = axis(pressed[GLFW_KEY_W], pressed[GLFW_KEY_S])
            val frame = controller.tick(PhysicsInput(ax, ay))
            val state: PhysicsState? = frame.state
            val myId = frame.myId

            MemoryStack.stackPush().use { st ->
                val pw = st.mallocInt(1)
                val ph = st.mallocInt(1)
                glfwGetFramebufferSize(window, pw, ph)
                val fbW = max(1, pw[0])
                val fbH = max(1, ph[0])
                glViewport(0, 0, fbW, fbH)

                glClearColor(0.07f, 0.07f, 0.07f, 1f)
                glClear(GL_COLOR_BUFFER_BIT)

                glUniform2f(uResolution, fbW.toFloat(), fbH.toFloat())

                if (state != null) {
                    val params = view.compute(state = state, myId = myId, zoom = zoom)
                    glUniform2f(uWorld, params.worldW, params.worldH)
                    glUniform2f(uView, params.viewW, params.viewH)
                    glUniform2f(uCenter, params.topLeftCoverX, params.topLeftCoverY)

                    glUniform1i(uMyId, myId?.value ?: -1)
                    val bodies = state.bodies.values.toList()
                    val n = min(MAX_BODIES, bodies.size)
                    glUniform1i(uBodyCount, n)

                    val fb = st.mallocFloat(4 * MAX_BODIES)
                    packBodiesToFloatArray(state = state, maxBodies = MAX_BODIES, out = bodiesFloats)
                    fb.put(bodiesFloats, 0, 4 * MAX_BODIES)
                    fb.flip()
                    glUniform4fv(uBodies, fb)
                } else {
                    // no state yet: still set something valid
                    glUniform2f(uWorld, 1f, 1f)
                    glUniform2f(uView, 1f, 1f)
                    glUniform2f(uCenter, 0f, 0f)
                    glUniform1i(uMyId, -1)
                    glUniform1i(uBodyCount, 0)
                }

                glDrawArrays(GL_TRIANGLES, 0, 3)
            }

            glfwSwapBuffers(window)
        }

        glDeleteProgram(program)
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}
