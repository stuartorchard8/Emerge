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
import kotlin.concurrent.thread
import kotlin.math.max
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
import org.emerge.sim.sync.LockstepClient
import org.emerge.sim.sync.LockstepHost

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

    // --- Networking wiring ---
    // We support:
    // - loopback: single device demo (host + 2 clients in-process)
    // - host: device acts as host (listens for 1 remote client)
    // - join: device joins a host at hostIp:port

    private val localPlayerId: PlayerId
    private val localClientPipe: Pipe
    private val hostPeers: Map<PlayerId, Pipe>?

    private val loopbackBotClientPipe: Pipe?

    @Volatile
    private var netStatus: String = "net: init"

    init {
        when (mode) {
            MainActivity.MODE_HOST -> {
                localPlayerId = PlayerId(0)

                // Create an in-process pipe pair for local client <-> host
                val local = Loopback.createPair()
                localClientPipe = local.first
                val localHostPipe = local.second

                // Accept one remote TCP client for player 1
                val listener = Tcp.listen(port = port, backlog = 1)
                val remote = DelegatingPipe()
                thread(isDaemon = true, name = "net-accept") {
                    netStatus = "net: waiting on :$port"
                    try {
                        remote.setDelegate(listener.accept())
                        netStatus = "net: connected"
                    } catch (t: Throwable) {
                        netStatus = "net: accept failed: ${t.javaClass.simpleName}"
                    }
                }

                hostPeers = mapOf(PlayerId(0) to localHostPipe, PlayerId(1) to remote)
                loopbackBotClientPipe = null
            }

            MainActivity.MODE_JOIN -> {
                localPlayerId = PlayerId(1)

                // IMPORTANT: never connect on the UI thread (will crash with NetworkOnMainThreadException).
                val remote = DelegatingPipe()
                localClientPipe = remote
                thread(isDaemon = true, name = "net-connect") {
                    netStatus = "net: connecting to $hostIp:$port"
                    try {
                        remote.setDelegate(Tcp.connect(host = hostIp, port = port))
                        netStatus = "net: connected"
                    } catch (t: Throwable) {
                        netStatus = "net: connect failed: ${t.javaClass.simpleName}"
                    }
                }

                hostPeers = null
                loopbackBotClientPipe = null
            }

            else -> {
                // loopback mode (default)
                localPlayerId = PlayerId(0)
                val local = Loopback.createPair()
                localClientPipe = local.first
                val localHostPipe = local.second

                val bot = Loopback.createPair()
                loopbackBotClientPipe = bot.first
                val botHostPipe = bot.second

                hostPeers = mapOf(PlayerId(0) to localHostPipe, PlayerId(1) to botHostPipe)
                netStatus = "net: loopback"
            }
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
            PlayerId(1) to CircleBody(
                playerId = PlayerId(1),
                pos = Vec2Fx(Fx.fromInt(600), Fx.fromInt(250)),
                vel = Vec2Fx(Fx(0), Fx(0)),
                radius = radius,
            ),
        ),
    )

    private val localClient = LockstepClient(
        playerId = localPlayerId,
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        pipe = localClientPipe,
    )

    private val host: LockstepHost<PhysicsState, PhysicsInput>? =
        hostPeers?.let { peers ->
            LockstepHost(
                initialState = initial,
                reducer = { s, inputs -> reducer.reduce(s, inputs) },
                inputCodec = inputCodec,
                peers = peers,
            )
        }

    private val loopbackBotClient: LockstepClient<PhysicsState, PhysicsInput>? =
        loopbackBotClientPipe?.let { pipe ->
            LockstepClient(
                playerId = PlayerId(1),
                initialState = initial,
                reducer = { s, inputs -> reducer.reduce(s, inputs) },
                inputCodec = inputCodec,
                pipe = pipe,
            )
        }

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
            // Local player input from touch
            localClient.sendLocalInput(currentTouchInput)

            // In loopback mode we simulate a 2nd client locally.
            loopbackBotClient?.let { botClient ->
                val t = localClient.tick.value.toInt()
                val botAx = if ((t / 30) % 2 == 0) 1 else -1
                val botAy = if ((t / 45) % 2 == 0) 1 else -1
                botClient.sendLocalInput(PhysicsInput(botAx, botAy))
            }

            host?.poll()
            localClient.poll()
            loopbackBotClient?.poll()

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

        val state = localClient.state
        val sx = width.toFloat() / worldW.toIntFloor().toFloat()
        val sy = height.toFloat() / worldH.toIntFloor().toFloat()
        val sr = max(0.1f, (sx + sy) * 0.5f)

        for ((pid, body) in state.bodies) {
            val p = if (pid == localPlayerId) paintMe else paintOther
            val r = body.radius.toIntFloor().toFloat()
            val cx = body.pos.x.toIntFloor().toFloat() * sx
            val cy = body.pos.y.toIntFloor().toFloat() * sy
            canvas.drawCircle(cx, cy, r * sr, p)
        }

        canvas.drawText("mode=$mode tick=${localClient.tick.value}", 16f, 40f, paintHud)
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
