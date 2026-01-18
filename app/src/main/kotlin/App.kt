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
import kotlin.system.exitProcess
import org.emerge.demo.physics.PhysicsAuthoritativeHostController
import org.emerge.demo.physics.PhysicsAuthoritativeJoinController
import org.emerge.demo.physics.PhysicsDemoConfig
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
    // Usage:
    // - (default) lockstep demo:
    //     gradlew :app:run
    // - run authoritative host (desktop) [GPU default]:
    //     gradlew :app:run --args="host 7777"
    // - join Android host (authoritative) [GPU default]:
    //     gradlew :app:run --args="join 192.168.0.102 7777"
    // - run authoritative host (desktop) with Swing renderer:
    //     gradlew :app:run --args="host-swing 7777"
    // - join Android host (authoritative) with Swing renderer:
    //     gradlew :app:run --args="join-swing 192.168.0.102 7777"
    // - join Android host (authoritative) with LWJGL/OpenGL shader renderer:
    //     gradlew :app:run --args="join-gl 192.168.0.102 7777"
    // - join Android host (LWJGL/OpenGL) for a few seconds then exit (test automation):
    //     gradlew :app:run --args="join-gl-once 192.168.0.102 7777 4000"
    // - join Android host once (headless smoke test; exits):
    //     gradlew :app:run --args="join-once 192.168.0.102 7777"
    if (args.isNotEmpty()) {
        when (args[0]) {
            "host" -> {
                val port = args.getOrNull(1)?.toIntOrNull() ?: error("Missing/invalid port. Usage: host <port>")
                runHostGl(port)
                return
            }
            "host-gl" -> {
                val port = args.getOrNull(1)?.toIntOrNull() ?: error("Missing/invalid port. Usage: host-gl <port>")
                runHostGl(port)
                return
            }
            "host-swing" -> {
                val port = args.getOrNull(1)?.toIntOrNull() ?: error("Missing/invalid port. Usage: host-swing <port>")
                SwingUtilities.invokeLater { AuthoritativeHostSwingDemo(port).start() }
                return
            }
            "join" -> {
                val host = args.getOrNull(1) ?: error("Missing host ip. Usage: join <hostIp> <port>")
                val port = args.getOrNull(2)?.toIntOrNull() ?: error("Missing/invalid port. Usage: join <hostIp> <port>")
                runJoinGl(hostIp = host, port = port)
                return
            }
            "join-swing" -> {
                val host = args.getOrNull(1) ?: error("Missing host ip. Usage: join-swing <hostIp> <port>")
                val port = args.getOrNull(2)?.toIntOrNull() ?: error("Missing/invalid port. Usage: join-swing <hostIp> <port>")
                SwingUtilities.invokeLater { AuthoritativeJoinSwingClient(host, port).start() }
                return
            }
            "join-gl" -> {
                val host = args.getOrNull(1) ?: error("Missing host ip. Usage: join-gl <hostIp> <port>")
                val port = args.getOrNull(2)?.toIntOrNull() ?: error("Missing/invalid port. Usage: join-gl <hostIp> <port>")
                runJoinGl(hostIp = host, port = port)
                return
            }
            "join-gl-once" -> {
                val host = args.getOrNull(1) ?: error("Missing host ip. Usage: join-gl-once <hostIp> <port> <ms>")
                val port = args.getOrNull(2)?.toIntOrNull() ?: error("Missing/invalid port. Usage: join-gl-once <hostIp> <port> <ms>")
                val ms = args.getOrNull(3)?.toLongOrNull() ?: error("Missing/invalid ms. Usage: join-gl-once <hostIp> <port> <ms>")
                val ok = runJoinGl(hostIp = host, port = port, maxRunMs = ms)
                exitProcess(if (ok) 0 else 1)
            }
            "join-once" -> {
                val host = args.getOrNull(1) ?: error("Missing host ip. Usage: join-once <hostIp> <port>")
                val port = args.getOrNull(2)?.toIntOrNull() ?: error("Missing/invalid port. Usage: join-once <hostIp> <port>")
                val ok = joinOnce(hostIp = host, port = port)
                exitProcess(if (ok) 0 else 1)
            }
        }
    }

    SwingUtilities.invokeLater { PhysicsLockstepSwingDemo().start() }
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

    private fun axis(neg: Boolean, pos: Boolean): Int =
        when {
            neg && !pos -> -1
            pos && !neg -> 1
            else -> 0
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

    private fun axis(neg: Boolean, pos: Boolean): Int =
        when {
            neg && !pos -> -1
            pos && !neg -> 1
            else -> 0
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

    private fun axis(neg: Boolean, pos: Boolean): Int =
        when {
            neg && !pos -> -1
            pos && !neg -> 1
            else -> 0
        }
}
