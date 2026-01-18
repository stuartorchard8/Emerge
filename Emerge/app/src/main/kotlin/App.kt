package org.example.app

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import kotlin.concurrent.thread
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.net.loopback.Loopback
import org.emerge.net.api.Pipe
import org.emerge.net.tcp.Tcp
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsReducer
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.LockstepClient
import org.emerge.sim.sync.LockstepHost
import org.emerge.sim.sync.auth.AuthoritativeClient
import org.emerge.sim.sync.auth.AuthoritativeHost
import org.emerge.sim.sync.auth.StateCodec

fun main(args: Array<String>) {
    // Usage:
    // - (default) lockstep demo:
    //     gradlew :app:run
    // - run authoritative host (desktop):
    //     gradlew :app:run --args="host 7777"
    // - join Android host (authoritative):
    //     gradlew :app:run --args="join 192.168.0.102 7777"
    if (args.isNotEmpty()) {
        when (args[0]) {
            "host" -> {
                val port = args.getOrNull(1)?.toIntOrNull() ?: error("Missing/invalid port. Usage: host <port>")
                SwingUtilities.invokeLater { AuthoritativeHostSwingDemo(port).start() }
                return
            }
            "join" -> {
                val host = args.getOrNull(1) ?: error("Missing host ip. Usage: join <hostIp> <port>")
                val port = args.getOrNull(2)?.toIntOrNull() ?: error("Missing/invalid port. Usage: join <hostIp> <port>")
                SwingUtilities.invokeLater { AuthoritativeJoinSwingClient(host, port).start() }
                return
            }
        }
    }

    SwingUtilities.invokeLater { PhysicsLockstepSwingDemo().start() }
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

    private val inputCodec = object : Codec<PhysicsInput> {
        override fun encode(value: PhysicsInput): ByteArray =
            byteArrayOf(value.ax.toByte(), value.ay.toByte())

        override fun decode(bytes: ByteArray): PhysicsInput {
            require(bytes.size == 2)
            return PhysicsInput(bytes[0].toInt(), bytes[1].toInt())
        }
    }

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
    private val worldW = Fx.fromInt(800)
    private val worldH = Fx.fromInt(500)

    private val inputCodec = object : Codec<PhysicsInput> {
        override fun encode(value: PhysicsInput): ByteArray =
            byteArrayOf(value.ax.toByte(), value.ay.toByte())

        override fun decode(bytes: ByteArray): PhysicsInput {
            require(bytes.size == 2)
            return PhysicsInput(bytes[0].toInt(), bytes[1].toInt())
        }
    }

    private val stateCodec = object : StateCodec<PhysicsState> {
        override fun encode(state: PhysicsState): ByteArray {
            val w = ByteWriter()
            w.writeInt(state.width.raw)
            w.writeInt(state.height.raw)
            w.writeInt(state.bodies.size)
            for ((pid, body) in state.bodies) {
                w.writeInt(pid.value)
                w.writeInt(body.pos.x.raw)
                w.writeInt(body.pos.y.raw)
                w.writeInt(body.vel.x.raw)
                w.writeInt(body.vel.y.raw)
                w.writeInt(body.radius.raw)
            }
            return w.toByteArray()
        }

        override fun decode(bytes: ByteArray): PhysicsState {
            val c = ByteCursor(bytes)
            val width = Fx(c.readInt())
            val height = Fx(c.readInt())
            val n = c.readInt()
            val bodies = LinkedHashMap<PlayerId, CircleBody>(n)
            repeat(n) {
                val pid = PlayerId(c.readInt())
                val px = Fx(c.readInt())
                val py = Fx(c.readInt())
                val vx = Fx(c.readInt())
                val vy = Fx(c.readInt())
                val r = Fx(c.readInt())
                bodies[pid] = CircleBody(pid, Vec2Fx(px, py), Vec2Fx(vx, vy), r)
            }
            return PhysicsState(width = width, height = height, bodies = bodies)
        }
    }

    private val ui = AuthClientWindow(
        title = "Desktop Join ($hostIp:$port) - WASD",
        myColor = Color(0x2E86AB),
        worldW = worldW,
        worldH = worldH,
    )

    @Volatile private var reconnecting: Boolean = false

    fun start() {
        val remote = DelegatingPipe()

        val client = AuthoritativeClient(
            pipe = remote,
            inputCodec = inputCodec,
            stateCodec = stateCodec,
            onDisconnected = { reason ->
                ui.setStatus("disconnected ($reason)")
            },
        )
        ui.show()

        thread(isDaemon = true, name = "net-connect") {
            var attempt = 0
            while (true) {
                attempt += 1
                ui.setStatus("connecting $hostIp:$port (try $attempt)")
                try {
                    remote.setDelegate(Tcp.connect(hostIp, port))
                    ui.setStatus("connected (handshake)")
                    client.resetConnection("connect")
                    client.startHandshake(force = true)
                    break
                } catch (t: Throwable) {
                    val msg = t.message?.take(60) ?: ""
                    ui.setStatus("connect failed: ${t.javaClass.simpleName} $msg")
                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        Timer(16) {
            client.poll()
            // Send input once we have an assigned player id
            client.sendInput(ui.currentInput())
            if (client.connectionState == AuthoritativeClient.ConnectionState.DISCONNECTED && !reconnecting) {
                reconnecting = true
                thread(isDaemon = true, name = "net-reconnect") {
                    var attempt = 0
                    while (true) {
                        attempt += 1
                        ui.setStatus("reconnecting $hostIp:$port (try $attempt)")
                        try {
                            remote.setDelegate(Tcp.connect(hostIp, port))
                            ui.setStatus("reconnected (handshake)")
                            client.resetConnection("reconnect")
                            client.startHandshake(force = true)
                            reconnecting = false
                            break
                        } catch (t: Throwable) {
                            val msg = t.message?.take(60) ?: ""
                            ui.setStatus("reconnect failed: ${t.javaClass.simpleName} $msg")
                            try {
                                Thread.sleep(500L)
                            } catch (_: InterruptedException) {
                                reconnecting = false
                                break
                            }
                        }
                    }
                }
            }
            ui.repaintWorld(client)
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
            for ((pid, body) in state.bodies) {
                g2.color = if (body.playerId == myPlayerId) myColor else Color(0xCCCCCC)
                val r = body.radius.toIntFloor()
                val x = body.pos.x.toIntFloor() - r
                val y = body.pos.y.toIntFloor() - r
                g2.fillOval(x, y, r * 2, r * 2)
            }
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
            val myId = lastMyId
            for ((pid, body) in state.bodies) {
                g2.color = if (myId != null && pid == myId) myColor else Color(0xCCCCCC)
                val r = body.radius.toIntFloor()
                val x = body.pos.x.toIntFloor() - r
                val y = body.pos.y.toIntFloor() - r
                g2.fillOval(x, y, r * 2, r * 2)
            }

            g2.color = Color(0xEEEEEE)
            g2.drawString("tick=$lastTick playerId=${myId?.value ?: "?"}", 10, 20)
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

    fun repaintWorld(client: AuthoritativeClient<PhysicsState, PhysicsInput>) {
        lastState = client.state
        lastMyId = client.playerId
        lastTick = client.tick.value
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
    private val worldW = Fx.fromInt(800)
    private val worldH = Fx.fromInt(500)
    private val radius = Fx.fromInt(16)

    private val reducer = PhysicsReducer()

    private val inputCodec = object : Codec<PhysicsInput> {
        override fun encode(value: PhysicsInput): ByteArray =
            byteArrayOf(value.ax.toByte(), value.ay.toByte())

        override fun decode(bytes: ByteArray): PhysicsInput {
            require(bytes.size == 2)
            return PhysicsInput(bytes[0].toInt(), bytes[1].toInt())
        }
    }

    private val stateCodec = object : StateCodec<PhysicsState> {
        override fun encode(state: PhysicsState): ByteArray {
            val w = ByteWriter()
            w.writeInt(state.width.raw)
            w.writeInt(state.height.raw)
            w.writeInt(state.bodies.size)
            for ((pid, body) in state.bodies) {
                w.writeInt(pid.value)
                w.writeInt(body.pos.x.raw)
                w.writeInt(body.pos.y.raw)
                w.writeInt(body.vel.x.raw)
                w.writeInt(body.vel.y.raw)
                w.writeInt(body.radius.raw)
            }
            return w.toByteArray()
        }

        override fun decode(bytes: ByteArray): PhysicsState {
            val c = ByteCursor(bytes)
            val width = Fx(c.readInt())
            val height = Fx(c.readInt())
            val n = c.readInt()
            val bodies = LinkedHashMap<PlayerId, CircleBody>(n)
            repeat(n) {
                val pid = PlayerId(c.readInt())
                val px = Fx(c.readInt())
                val py = Fx(c.readInt())
                val vx = Fx(c.readInt())
                val vy = Fx(c.readInt())
                val r = Fx(c.readInt())
                bodies[pid] = CircleBody(pid, Vec2Fx(px, py), Vec2Fx(vx, vy), r)
            }
            return PhysicsState(width = width, height = height, bodies = bodies)
        }
    }

    private val initial = PhysicsState(
        width = worldW,
        height = worldH,
        bodies = mapOf(
            PlayerId(0) to CircleBody(
                playerId = PlayerId(0),
                pos = Vec2Fx(Fx.fromInt(200), Fx.fromInt(250)),
                vel = Vec2Fx(Fx(0), Fx(0)),
                radius = radius,
            ),
        ),
    )

    private val host = AuthoritativeHost(
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        joinPolicy = { s, pid ->
            val bodies = LinkedHashMap(s.bodies)
            val x = 100 + (pid.value * 70)
            val y = 250
            bodies[pid] = CircleBody(pid, Vec2Fx(Fx.fromInt(x), Fx.fromInt(y)), Vec2Fx(Fx(0), Fx(0)), radius)
            s.copy(bodies = bodies)
        },
    )

    private val ui = HostWindow(
        title = "Desktop Host (:$port) - WASD",
        myPlayerId = PlayerId(0),
        myColor = Color(0x2E86AB),
        worldW = worldW,
        worldH = worldH,
    )

    fun start() {
        ui.show()
        ui.setStatus("listening :$port")

        thread(isDaemon = true, name = "net-accept") {
            try {
                val listener = Tcp.listen(port = port, backlog = 8)
                while (true) {
                    val pipe = listener.accept()
                    host.acceptClient(pipe)
                    ui.setStatus("client joined")
                }
            } catch (t: Throwable) {
                ui.setStatus("accept failed: ${t.javaClass.simpleName}")
            }
        }

        Timer(16) {
            host.pollNetwork()
            host.setLocalInput(PlayerId(0), ui.currentInput())
            host.step()
            ui.repaintWorld(host.state, host.tick.value)
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
            for ((pid, body) in state.bodies) {
                g2.color = if (pid == myPlayerId) myColor else Color(0xCCCCCC)
                val r = body.radius.toIntFloor()
                val x = body.pos.x.toIntFloor() - r
                val y = body.pos.y.toIntFloor() - r
                g2.fillOval(x, y, r * 2, r * 2)
            }

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

private class DelegatingPipe : Pipe {
    @Volatile private var delegate: Pipe? = null

    fun setDelegate(pipe: Pipe) {
        delegate = pipe
    }

    override fun send(packet: ByteArray) {
        delegate?.send(packet)
    }

    override fun receive(): ByteArray? = delegate?.receive()
}
