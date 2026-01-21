package org.example.app

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import kotlin.system.exitProcess
import org.emerge.demo.physics.PhysicsAuthoritativeHostController
import org.emerge.demo.physics.PhysicsAuthoritativeJoinController
import org.emerge.demo.physics.PhysicsDemoConfig
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.LaunchSettings
import org.emerge.demo.physics.RenderBackend
import org.emerge.demo.physics.createDefaultInitialState
import org.emerge.net.loopback.Loopback
import org.emerge.net.api.Pipe
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.camera.TorusOrthoCamera2D
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsReducer
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.LockstepClient
import org.emerge.sim.sync.LockstepHost
import org.emerge.sim.sync.auth.AuthoritativeClient
import org.emerge.sim.sync.auth.StateCodec

fun main(args: Array<String>) {
    // Single launch path: always start with an in-app launcher UI.
    // (Args are intentionally ignored now; configure Host/Join/Local from the launcher screen.)
    SwingUtilities.invokeLater { DesktopLauncher().show() }
}

private class DesktopLauncher {
    private val modeBox = JComboBox(arrayOf("Local", "Host", "Join"))
    private val renderBox = JComboBox(arrayOf("GPU (OpenGL)", "CPU (Swing)"))
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

            fun row(y: Int, label: String, comp: java.awt.Component) {
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
        setLocationByPlatform(true)
        isResizable = false
    }

    fun show() {
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
                // Desktop local demo is the existing lockstep loopback Swing UI.
                SwingUtilities.invokeLater { PhysicsLockstepSwingDemo().start() }
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

/**
 * Headless join smoke test for verifying that desktop can connect + handshake + receive first snapshot.
 */
private fun joinOnce(hostIp: String, port: Int): Boolean {
    val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec
    val pipe =
        try {
            Tcp.connect(hostIp, port)
        } catch (t: Throwable) {
            val msg = t.message?.take(120) ?: ""
            println("join-once: connect failed: ${t.javaClass.simpleName} $msg")
            return false
        }

    val client = AuthoritativeClient(
        pipe = pipe,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        onDisconnected = { reason ->
            println("join-once: disconnected: $reason")
        },
    )

    println("join-once: connecting $hostIp:$port")
    client.resetConnection("join-once")
    client.startHandshake(force = true)

    val deadlineMs = System.currentTimeMillis() + 6_000L
    while (System.currentTimeMillis() < deadlineMs) {
        client.poll()
        if (client.connectionState == AuthoritativeClient.ConnectionState.CONNECTED && client.playerId != null && client.state != null) {
            println("join-once: OK (playerId=${client.playerId} tick=${client.tick.value})")
            return true
        }
        if (client.connectionState == AuthoritativeClient.ConnectionState.DISCONNECTED) {
            val r = client.lastDisconnectReason ?: "disconnected"
            println("join-once: FAILED ($r)")
            return false
        }
        try {
            Thread.sleep(5L)
        } catch (_: InterruptedException) {
            break
        }
    }

    val r = client.lastDisconnectReason ?: "timeout waiting for welcome/snapshot"
    println("join-once: FAILED ($r)")
    return false
}

private class PhysicsLockstepSwingDemo {
    private val worldW = Fx.fromInt(800)
    private val worldH = Fx.fromInt(500)
    private val radius = Fx.fromInt(16)

    private val pair0 = Loopback.createPair()
    private val c0 = pair0.first
    private val h0 = pair0.second

    private val pair1 = Loopback.createPair()
    private val c1 = pair1.first
    private val h1 = pair1.second

    private val reducer = PhysicsReducer()

    private val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec

    private val initial = PhysicsState(
        width = worldW,
        height = worldH,
        bodies = mapOf(
            PlayerId(0) to CircleBody(
                playerId = PlayerId(0),
                pos = Vec2Fx(Fx.fromInt(200), Fx.fromInt(250)),
                vel = Vec2Fx(Fx.fromRaw(0), Fx.fromRaw(0)),
                radius = radius,
            ),
            PlayerId(1) to CircleBody(
                playerId = PlayerId(1),
                pos = Vec2Fx(Fx.fromInt(600), Fx.fromInt(250)),
                vel = Vec2Fx(Fx.fromRaw(0), Fx.fromRaw(0)),
                radius = radius,
            ),
        ),
    )

    private val host = LockstepHost(
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        peers = mapOf(PlayerId(0) to h0, PlayerId(1) to h1),
    )

    private val client0 = LockstepClient(
        playerId = PlayerId(0),
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        pipe = c0,
    )

    private val client1 = LockstepClient(
        playerId = PlayerId(1),
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        pipe = c1,
    )

    private val ui0 = ClientWindow(
        title = "Client 0 (WASD)",
        myPlayerId = PlayerId(0),
        client = client0,
        myColor = Color(0x2E86AB),
        worldW = worldW,
        worldH = worldH,
        useWasd = true,
    )
    private val ui1 = ClientWindow(
        title = "Client 1 (Arrows)",
        myPlayerId = PlayerId(1),
        client = client1,
        myColor = Color(0xF18F01),
        worldW = worldW,
        worldH = worldH,
        useWasd = false,
    )

    fun start() {
        ui0.show()
        ui1.show()

        // 60Hz lockstep tick
        Timer(16) {
            client0.sendLocalInput(ui0.currentInput())
            client1.sendLocalInput(ui1.currentInput())
            host.poll()
            client0.poll()
            client1.poll()
            ui0.repaintWorld()
            ui1.repaintWorld()
        }.start()
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
    private val controller = PhysicsAuthoritativeJoinController(hostIp = hostIp, port = port, cfg = cfg)

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

private class ClientWindow(
    title: String,
    private val myPlayerId: PlayerId,
    private val client: LockstepClient<PhysicsState, PhysicsInput>,
    private val myColor: Color,
    private val worldW: Fx,
    private val worldH: Fx,
    private val useWasd: Boolean,
) {
    private val pressed = HashSet<Int>()
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

            val state = client.state
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
        }
    }

    private val frame = JFrame(title).apply {
        contentPane = panel
        panel.isFocusable = true
        panel.requestFocusInWindow()
        pack()
        setLocationByPlatform(true)
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

    fun repaintWorld() = panel.repaint()

    fun currentInput(): PhysicsInput {
        val (ax, ay) = if (useWasd) {
            val left = KeyEvent.VK_A in pressed
            val right = KeyEvent.VK_D in pressed
            val up = KeyEvent.VK_W in pressed
            val down = KeyEvent.VK_S in pressed
            axis(left, right) to axis(up, down)
        } else {
            val left = KeyEvent.VK_LEFT in pressed
            val right = KeyEvent.VK_RIGHT in pressed
            val up = KeyEvent.VK_UP in pressed
            val down = KeyEvent.VK_DOWN in pressed
            axis(left, right) to axis(up, down)
        }
        return PhysicsInput(ax, ay)
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
        setLocationByPlatform(true)
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
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0x111111)
            g2.fillRect(0, 0, width, height)

            val state = lastState ?: return
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
        setLocationByPlatform(true)
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
