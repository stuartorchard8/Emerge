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
import org.emerge.net.loopback.Loopback
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
        val view = PhysicsLockstepView(this)
        setContentView(view)
    }
}

private class PhysicsLockstepView(context: Activity) : View(context) {
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

    // host<->client pipes
    private val localPair = Loopback.createPair()
    private val localClientPipe = localPair.first
    private val localHostPipe = localPair.second

    // host<->bot pipes (simulates a second remote client)
    private val botPair = Loopback.createPair()
    private val botClientPipe = botPair.first
    private val botHostPipe = botPair.second

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

    private val host = LockstepHost(
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        peers = mapOf(PlayerId(0) to localHostPipe, PlayerId(1) to botHostPipe),
    )

    private val localClient = LockstepClient(
        playerId = PlayerId(0),
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        pipe = localClientPipe,
    )

    private val botClient = LockstepClient(
        playerId = PlayerId(1),
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        pipe = botClientPipe,
    )

    private val paintBg = Paint().apply { color = Color.rgb(0x11, 0x11, 0x11) }
    private val paintMe = Paint().apply { color = Color.rgb(0x2E, 0x86, 0xAB) }
    private val paintOther = Paint().apply { color = Color.rgb(0xF1, 0x8F, 0x01) }

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            // Local player input from touch
            localClient.sendLocalInput(currentTouchInput)

            // Bot input (simple deterministic pattern)
            val t = localClient.tick.value.toInt()
            val botAx = if ((t / 30) % 2 == 0) 1 else -1
            val botAy = if ((t / 45) % 2 == 0) 1 else -1
            botClient.sendLocalInput(PhysicsInput(botAx, botAy))

            host.poll()
            localClient.poll()
            botClient.poll()

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

        for ((pid, body) in state.bodies) {
            val p = if (pid.value == 0) paintMe else paintOther
            val r = body.radius.toIntFloor().toFloat()
            val cx = body.pos.x.toIntFloor().toFloat() * sx
            val cy = body.pos.y.toIntFloor().toFloat() * sy
            canvas.drawCircle(cx, cy, r * ((sx + sy) * 0.5f), p)
        }
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

