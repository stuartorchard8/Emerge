package org.emerge.androidapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.concurrent.thread
import org.emerge.net.loopback.Loopback
import org.emerge.net.tcp.Tcp
import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsReducer
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.auth.AuthoritativeClient
import org.emerge.sim.sync.auth.AuthoritativeHost
import org.emerge.sim.sync.auth.StateCodec

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LOOPBACK
        val hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 7777)
        val view = PhysicsLockstepView(this, mode = mode, hostIp = hostIp, port = port)
        setContentView(view)
    }

    companion object {
        const val EXTRA_MODE = "mode" // "host" | "join" | "loopback"
        const val EXTRA_HOST_IP = "hostIp"
        const val EXTRA_PORT = "port"

        const val MODE_HOST = "host"
        const val MODE_JOIN = "join"
        const val MODE_LOOPBACK = "loopback"
    }
}

private class PhysicsLockstepView(
    context: Activity,
    private val mode: String,
    private val hostIp: String,
    private val port: Int,
) : View(context) {
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
            // width/height are constant in this demo; still encode for robustness.
            val w = org.emerge.net.codec.ByteWriter()
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
            val c = org.emerge.net.codec.ByteCursor(bytes)
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

    // --- Networking wiring ---
    // We support:
    // - loopback: single device demo (host + 2 clients in-process)
    // - host: device acts as host (listens for 1 remote client)
    // - join: device joins a host at hostIp:port

    private val localPlayerId: PlayerId
    private val localClientPipe: Pipe?
    private val host: AuthoritativeHost<PhysicsState, PhysicsInput>?
    private val client: AuthoritativeClient<PhysicsState, PhysicsInput>?

    private val loopbackBotClientPipe: Pipe?
    private val joinRemote: DelegatingPipe?

    @Volatile
    private var reconnecting: Boolean = false

    @Volatile
    private var netStatus: String = "net: init"

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
            PlayerId(1) to CircleBody(
                playerId = PlayerId(1),
                pos = Vec2Fx(Fx.fromInt(600), Fx.fromInt(250)),
                vel = Vec2Fx(Fx(0), Fx(0)),
                radius = radius,
            ),
        ),
    )

    init {
        when (mode) {
            MainActivity.MODE_HOST -> {
                localPlayerId = PlayerId(0)

                // Create an in-process pipe pair for local client <-> host
                localClientPipe = null
                loopbackBotClientPipe = null
                joinRemote = null

                host = AuthoritativeHost(
                    initialState = initial,
                    reducer = { s, inputs -> reducer.reduce(s, inputs) },
                    inputCodec = inputCodec,
                    stateCodec = stateCodec,
                    joinPolicy = { s, pid ->
                        // Spawn new player at deterministic spot.
                        val bodies = LinkedHashMap(s.bodies)
                        val x = 100 + (pid.value * 70)
                        val y = 250
                        bodies[pid] = CircleBody(pid, Vec2Fx(Fx.fromInt(x), Fx.fromInt(y)), Vec2Fx(Fx(0), Fx(0)), radius)
                        s.copy(bodies = bodies)
                    },
                )

                // Accept clients continuously
                thread(isDaemon = true, name = "net-accept-loop") {
                    try {
                        netStatus = "net: host listening :$port"
                        val listener = Tcp.listen(port = port, backlog = 8)
                        while (true) {
                            val pipe = listener.accept()
                            host.acceptClient(pipe)
                            netStatus = "net: client joined"
                        }
                    } catch (t: Throwable) {
                        netStatus = "net: accept failed: ${t.javaClass.simpleName}"
                    }
                }

                client = null
            }

            MainActivity.MODE_JOIN -> {
                // placeholder; actual assigned id comes from welcome snapshot.
                localPlayerId = PlayerId(0)
                loopbackBotClientPipe = null
                host = null

                val remote = DelegatingPipe()
                localClientPipe = remote
                joinRemote = remote
                client = AuthoritativeClient(
                    pipe = remote,
                    inputCodec = inputCodec,
                    stateCodec = stateCodec,
                    onDisconnected = { reason ->
                        netStatus = "net: disconnected ($reason)"
                    },
                )

                thread(isDaemon = true, name = "net-connect") {
                    var attempt = 0
                    while (true) {
                        attempt += 1
                        netStatus = "net: connecting to $hostIp:$port (try $attempt)"
                        try {
                            remote.setDelegate(Tcp.connect(host = hostIp, port = port))
                            netStatus = "net: connected (handshake)"
                            client.resetConnection("connect")
                            client.startHandshake(force = true)
                            break
                        } catch (t: Throwable) {
                            val msg = t.message?.take(60) ?: ""
                            netStatus = "net: connect failed: ${t.javaClass.simpleName} $msg"
                            try {
                                Thread.sleep(500L)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                }
            }

            else -> {
                // loopback mode (default)
                localPlayerId = PlayerId(0)
                loopbackBotClientPipe = null
                joinRemote = null
                localClientPipe = null
                host = AuthoritativeHost(
                    initialState = initial,
                    reducer = { s, inputs -> reducer.reduce(s, inputs) },
                    inputCodec = inputCodec,
                    stateCodec = stateCodec,
                    joinPolicy = { s, pid ->
                        val bodies = LinkedHashMap(s.bodies)
                        bodies[pid] = CircleBody(pid, Vec2Fx(Fx.fromInt(400), Fx.fromInt(250)), Vec2Fx(Fx(0), Fx(0)), radius)
                        s.copy(bodies = bodies)
                    },
                )
                client = null
                netStatus = "net: host-only (no join)"
            }
        }
    }

    // Authoritative mode keeps "state of record" on the host; join clients render the last snapshot.

    private val paintBg = Paint().apply { color = Color.rgb(0x11, 0x11, 0x11) }
    private val paintMe = Paint().apply { color = Color.rgb(0x2E, 0x86, 0xAB) }
    private val paintOther = Paint().apply { color = Color.rgb(0xF1, 0x8F, 0x01) }
    private val paintHud = Paint().apply {
        color = Color.rgb(0xEE, 0xEE, 0xEE)
        textSize = 32f
        isAntiAlias = true
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            host?.let { h ->
                // Host always runs immediately
                h.pollNetwork()
                h.setLocalInput(PlayerId(0), currentTouchInput)
                h.step()
            }

            client?.let { c ->
                c.poll()
                c.sendInput(currentTouchInput)

                if (mode == MainActivity.MODE_JOIN &&
                    c.connectionState == AuthoritativeClient.ConnectionState.DISCONNECTED &&
                    !reconnecting
                ) {
                    startReconnect(c)
                }
            }

            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    private var currentTouchInput: PhysicsInput = PhysicsInput(0, 0)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tickRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        val state = client?.state ?: host?.state ?: initial
        val sx = width.toFloat() / worldW.toIntFloor().toFloat()
        val sy = height.toFloat() / worldH.toIntFloor().toFloat()
        val sr = max(0.1f, (sx + sy) * 0.5f)

        for ((pid, body) in state.bodies) {
            val myId = client?.playerId ?: localPlayerId
            val p = if (pid == myId) paintMe else paintOther
            val r = body.radius.toIntFloor().toFloat()
            val cx = body.pos.x.toIntFloor().toFloat() * sx
            val cy = body.pos.y.toIntFloor().toFloat() * sy
            canvas.drawCircle(cx, cy, r * sr, p)
        }

        val tick = client?.tick?.value ?: host?.tick?.value ?: 0L
        canvas.drawText("mode=$mode tick=$tick", 16f, 40f, paintHud)
        canvas.drawText(netStatus, 16f, 80f, paintHud)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val cx = width * 0.5f
        val cy = height * 0.5f
        val dx = x - cx
        val dy = y - cy

        currentTouchInput = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> PhysicsInput(0, 0)
            else -> {
                val ax = when {
                    dx < -40f -> -1
                    dx > 40f -> 1
                    else -> 0
                }
                val ay = when {
                    dy < -40f -> -1
                    dy > 40f -> 1
                    else -> 0
                }
                PhysicsInput(ax, ay)
            }
        }
        return true
    }

    private fun startReconnect(c: AuthoritativeClient<PhysicsState, PhysicsInput>) {
        val remote = joinRemote ?: return
        reconnecting = true
        thread(isDaemon = true, name = "net-reconnect") {
            var attempt = 0
            while (true) {
                attempt += 1
                netStatus = "net: reconnecting $hostIp:$port (try $attempt)"
                try {
                    remote.setDelegate(Tcp.connect(host = hostIp, port = port))
                    netStatus = "net: reconnected (handshake)"
                    c.resetConnection("reconnect")
                    c.startHandshake(force = true)
                    reconnecting = false
                    break
                } catch (t: Throwable) {
                    val msg = t.message?.take(60) ?: ""
                    netStatus = "net: reconnect failed: ${t.javaClass.simpleName} $msg"
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
