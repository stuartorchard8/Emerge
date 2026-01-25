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
    private val renderBox = JComboBox(RenderBackend.entries.map(RenderBackend::name).toTypedArray())
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
            row(1, "Renderer", renderBox)
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
        renderBox.selectedItem = defaultLaunchSettings.renderBackend.name
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

    private fun selectedBackend(): RenderBackend =
        if (renderBox.selectedIndex == 0) RenderBackend.GPU else RenderBackend.CPU

    private fun readSettings(): LaunchSettings {
        val port = portField.text.trim().toIntOrNull() ?: 7777
        val hostIp = hostIpField.text.trim().ifBlank { "127.0.0.1" }
        return LaunchSettings(
            mode = selectedMode(),
            renderBackend = selectedBackend(),
            hostIp = hostIp,
            port = port,
        )
    }

    private fun startDemo(settings: LaunchSettings) {
        when (settings.mode) {
            LaunchMode.LOCAL -> {
                when (settings.renderBackend) {
                    // N.B. CPU technically opens a network connection
                    RenderBackend.CPU -> SwingUtilities.invokeLater { AuthoritativeHostSwingDemo(settings.port).start() }
                    RenderBackend.GPU -> Thread { runLocalGl(settings.port) }.start()
                }
            }
            LaunchMode.HOST -> {
                when (settings.renderBackend) {
                    RenderBackend.CPU -> SwingUtilities.invokeLater { AuthoritativeHostSwingDemo(settings.port).start() }
                    RenderBackend.GPU -> Thread { runHostGl(settings.port) }.start()
                }
            }
            LaunchMode.JOIN -> {
                when (settings.renderBackend) {
                    RenderBackend.CPU -> SwingUtilities.invokeLater { AuthoritativeJoinSwingClient(settings.hostIp, settings.port).start() }
                    RenderBackend.GPU -> Thread { runJoinGl(hostIp = settings.hostIp, port = settings.port) }.start()
                }
            }
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

private fun drawBodiesTorusTiled(
    g2: Graphics2D,
    widthPx: Int,
    heightPx: Int,
    torus: Torus2D,
    state: PhysicsState,
    topLeft: Vec2Fx,
    viewW: Fx,
    viewH: Fx,
    myId: PlayerId?,
    myColor: Color,
    otherColor: Color,
) {
    val viewWi = viewW.toIntFloor().coerceAtLeast(1)
    val viewHi = viewH.toIntFloor().coerceAtLeast(1)
    val scaleX = widthPx.toDouble() / viewWi.toDouble()
    val scaleY = heightPx.toDouble() / viewHi.toDouble()
    val scale = minOf(scaleX, scaleY)
    val ox = ((widthPx.toDouble() - (viewWi * scale)) * 0.5).toInt()
    val oy = ((heightPx.toDouble() - (viewHi * scale)) * 0.5).toInt()

    val offX = torus.tileOffsetsRawX()
    val offY = torus.tileOffsetsRawY()

    for ((pid, body) in state.bodies) {
        g2.color = if (myId != null && pid == myId) myColor else otherColor
        val r = (body.radius.toIntFloor().toDouble() * scale).toInt().coerceAtLeast(1)
        for (dx in offX) {
            for (dy in offY) {
                val localXRaw = body.pos.x.raw + dx - topLeft.x.raw
                val localYRaw = body.pos.y.raw + dy - topLeft.y.raw
                val localX = localXRaw.toDouble() / Fx.SCALE.toDouble()
                val localY = localYRaw.toDouble() / Fx.SCALE.toDouble()
                if (localX < -2.0 || localY < -2.0) continue
                if (localX > viewWi + 2.0 || localY > viewHi + 2.0) continue
                val cx = ox + (localX * scale).toInt()
                val cy = oy + (localY * scale).toInt()
                g2.fillOval(cx - r, cy - r, r * 2, r * 2)
            }
        }
    }
}

private class AuthoritativeJoinSwingClient(
    private val hostIp: String,
    private val port: Int,
) {
    private val cfg = PhysicsDemoConfig()
    private val worldW = cfg.worldW
    private val worldH = cfg.worldH
    private val initial: PhysicsState = createDefaultInitialState(cfg)
    private val controller = PhysicsAuthoritativeJoinController(hostIp = hostIp, port = port)

    private val ui = AuthClientWindow(
        title = "Desktop Join ($hostIp:$port) - WASD",
        myColor = Color(0x2E86AB),
        worldW = worldW,
        worldH = worldH,
    )

    @Volatile private var sawFirstState: Boolean = false

    fun start() {
        ui.show()

        Timer(16) {
            val frame = controller.tick(ui.currentInput())
            if (!sawFirstState && frame.state != null && frame.myId != null) {
                sawFirstState = true
                println("join-ui: first snapshot (playerId=${frame.myId} tick=${frame.tick})")
            }
            ui.repaintWorld(
                state = frame.state,
                myId = frame.myId,
                tick = frame.tick,
                status = frame.status,
                fallbackState = initial,
            )
        }.start()
    }
}

private class AuthClientWindow(
    title: String,
    private val myColor: Color,
    private val worldW: Fx,
    private val worldH: Fx,
) {
    private val pressed = HashSet<Int>()
    private var lastState: PhysicsState? = null
    private var lastMyId: PlayerId? = null
    private var lastTick: Long = 0L
    @Volatile private var status: String = ""
    private val torus = Torus2D(width = worldW, height = worldH)
    private val camera = TorusOrthoCamera2D(torus = torus, zoom = 2)

    private val panel = object : JPanel() {
        override fun getPreferredSize(): Dimension =
            Dimension(worldW.toIntFloor(), worldH.toIntFloor())

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0x111111)
            g2.fillRect(0, 0, width, height)

            val state = lastState
            val myId = lastMyId
            if (state != null) {
                val focus = if (myId != null) state.bodies[myId]?.pos else null
                val focusWrapped = focus ?: Vec2Fx(Fx(worldW.raw / 2), Fx(worldH.raw / 2))
                val topLeft = camera.topLeftForFocus(focusWrapped)
                drawBodiesTorusTiled(
                    g2 = g2,
                    widthPx = width,
                    heightPx = height,
                    torus = torus,
                    state = state,
                    topLeft = topLeft,
                    viewW = camera.viewW,
                    viewH = camera.viewH,
                    myId = myId,
                    myColor = myColor,
                    otherColor = Color(0xCCCCCC),
                )
            }

            g2.color = Color(0xEEEEEE)
            g2.drawString("tick=$lastTick playerId=${myId?.value ?: "?"}", 10, 20)
            if (status.isNotBlank()) {
                g2.drawString(status, 10, 40)
            }
            if (state == null) {
                g2.drawString("waiting for welcome/snapshot...", 10, 60)
            }
        }
    }

    private val frame = JFrame(title).apply {
        contentPane = panel
        panel.isFocusable = true
        pack()
        isLocationByPlatform = true
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        isResizable = false
        panel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                pressed.add(e.keyCode)
            }

            override fun keyReleased(e: KeyEvent) {
                pressed.remove(e.keyCode)
            }
        })
    }

    fun show() {
        frame.isVisible = true
        panel.requestFocusInWindow()
    }

    fun repaintWorld(
        state: PhysicsState?,
        myId: PlayerId?,
        tick: Long,
        status: String,
        fallbackState: PhysicsState,
    ) {
        lastState = state ?: fallbackState
        lastMyId = myId
        lastTick = tick
        this.status = status
        panel.repaint()
    }

    fun currentInput(): PhysicsInput {
        val left = KeyEvent.VK_A in pressed
        val right = KeyEvent.VK_D in pressed
        val up = KeyEvent.VK_W in pressed
        val down = KeyEvent.VK_S in pressed
        val ax = axis(left, right)
        val ay = axis(up, down)
        return PhysicsInput(ax, ay)
    }
}

private class AuthoritativeHostSwingDemo(
    private val port: Int,
) {
    private val cfg = PhysicsDemoConfig()
    private val worldW = cfg.worldW
    private val worldH = cfg.worldH
    private val initial: PhysicsState = createDefaultInitialState(cfg)
    private val controller = PhysicsAuthoritativeHostController(port = port, cfg = cfg, acceptRemoteClients = true)

    private val ui = HostWindow(
        title = "Desktop Host (:$port) - WASD",
        myPlayerId = PlayerId(0),
        myColor = Color(0x2E86AB),
        worldW = worldW,
        worldH = worldH,
    )

    fun start() {
        ui.show()

        Timer(16) {
            val frame = controller.tick(ui.currentInput())
            ui.setStatus(frame.status)
            ui.repaintWorld(frame.state ?: initial, frame.tick)
        }.start()
    }
}

private class HostWindow(
    title: String,
    private val myPlayerId: PlayerId,
    private val myColor: Color,
    private val worldW: Fx,
    private val worldH: Fx,
) {
    private val pressed = HashSet<Int>()
    private var lastState: PhysicsState? = null
    private var lastTick: Long = 0L
    @Volatile private var status: String = ""
    private val torus = Torus2D(width = worldW, height = worldH)
    private val camera = TorusOrthoCamera2D(torus = torus, zoom = 2)

    private val panel = object : JPanel() {
        override fun getPreferredSize(): Dimension =
            Dimension(worldW.toIntFloor(), worldH.toIntFloor())

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val state = lastState ?: return

            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0x111111)
            g2.fillRect(0, 0, width, height)

            val focus = state.bodies[myPlayerId]?.pos ?: Vec2Fx(Fx(worldW.raw / 2), Fx(worldH.raw / 2))
            val topLeft = camera.topLeftForFocus(focus)
            drawBodiesTorusTiled(
                g2 = g2,
                widthPx = width,
                heightPx = height,
                torus = torus,
                state = state,
                topLeft = topLeft,
                viewW = camera.viewW,
                viewH = camera.viewH,
                myId = myPlayerId,
                myColor = myColor,
                otherColor = Color(0xCCCCCC),
            )

            g2.color = Color(0xEEEEEE)
            g2.drawString("tick=$lastTick hostPlayer=${myPlayerId.value}", 10, 20)
            if (status.isNotBlank()) {
                g2.drawString(status, 10, 40)
            }
        }
    }

    private val frame = JFrame(title).apply {
        contentPane = panel
        panel.isFocusable = true
        pack()
        isLocationByPlatform = true
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        isResizable = false
        panel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                pressed.add(e.keyCode)
            }

            override fun keyReleased(e: KeyEvent) {
                pressed.remove(e.keyCode)
            }
        })
    }

    fun show() {
        frame.isVisible = true
        panel.requestFocusInWindow()
    }

    fun repaintWorld(state: PhysicsState, tick: Long) {
        lastState = state
        lastTick = tick
        panel.repaint()
    }

    fun setStatus(text: String) {
        status = text
    }

    fun currentInput(): PhysicsInput {
        val left = KeyEvent.VK_A in pressed
        val right = KeyEvent.VK_D in pressed
        val up = KeyEvent.VK_W in pressed
        val down = KeyEvent.VK_S in pressed
        val ax = axis(left, right)
        val ay = axis(up, down)
        return PhysicsInput(ax, ay)
    }
}
