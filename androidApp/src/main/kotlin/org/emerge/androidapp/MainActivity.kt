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
import org.emerge.demo.physics.AuthoritativeDemoFrame
import org.emerge.demo.physics.PhysicsAuthoritativeHostController
import org.emerge.demo.physics.PhysicsAuthoritativeJoinController
import org.emerge.demo.physics.PhysicsDemoConfig
import org.emerge.demo.physics.createDefaultInitialState
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusOrthoCamera2D
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LOOPBACK
        val hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 7777)
        // Prefer GPU shader renderer; allow fallback via --es renderer canvas
        val renderer = intent.getStringExtra(EXTRA_RENDERER) ?: RENDERER_GL
        val content: View =
            if (renderer == RENDERER_CANVAS) {
                PhysicsLockstepView(this, mode = mode, hostIp = hostIp, port = port)
            } else {
                TorusGlSurfaceView(this, mode = mode, hostIp = hostIp, port = port)
            }
        setContentView(content)
    }

    companion object {
        const val EXTRA_MODE = "mode" // "host" | "join" | "loopback"
        const val EXTRA_HOST_IP = "hostIp"
        const val EXTRA_PORT = "port"
        const val EXTRA_RENDERER = "renderer" // "gl" | "canvas"

        const val MODE_HOST = "host"
        const val MODE_JOIN = "join"
        const val MODE_LOOPBACK = "loopback"

        const val RENDERER_GL = "gl"
        const val RENDERER_CANVAS = "canvas"
    }
}

private class PhysicsLockstepView(
    context: Activity,
    private val mode: String,
    private val hostIp: String,
    private val port: Int,
) : View(context) {
    private val cfg = PhysicsDemoConfig()
    private val worldW = cfg.worldW
    private val worldH = cfg.worldH
    private val initial: PhysicsState = createDefaultInitialState(cfg)

    private val hostController: PhysicsAuthoritativeHostController?
    private val joinController: PhysicsAuthoritativeJoinController?

    @Volatile private var lastFrame: AuthoritativeDemoFrame =
        AuthoritativeDemoFrame(
            state = initial,
            myId = PlayerId(0),
            tick = 0L,
            status = "net: init",
        )

    init {
        when (mode) {
            MainActivity.MODE_HOST -> {
                hostController = PhysicsAuthoritativeHostController(port = port, cfg = cfg, acceptRemoteClients = true)
                joinController = null
            }

            MainActivity.MODE_JOIN -> {
                hostController = null
                joinController = PhysicsAuthoritativeJoinController(hostIp = hostIp, port = port, cfg = cfg)
            }

            else -> {
                // default: host-only loopback-ish (no join)
                hostController = PhysicsAuthoritativeHostController(port = port, cfg = cfg, acceptRemoteClients = false)
                joinController = null
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

    private val torus = Torus2D(width = worldW, height = worldH)
    private val camera = TorusOrthoCamera2D(torus = torus, zoom = 2)

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val f =
                when {
                    hostController != null -> hostController.tick(currentTouchInput)
                    joinController != null -> joinController.tick(currentTouchInput)
                    else -> lastFrame
                }
            lastFrame = f

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

        val f = lastFrame
        val state = f.state ?: initial
        val myId = f.myId ?: PlayerId(0)
        val focus = state.bodies[myId]?.pos ?: Vec2Fx(Fx(worldW.raw / 2), Fx(worldH.raw / 2))
        // Canvas fallback: torus tiling (no per-pixel rasterization)
        val topLeft = camera.topLeftForFocus(focus)
        val viewW = max(1, camera.viewW.toIntFloor())
        val viewH = max(1, camera.viewH.toIntFloor())

        val scaleX = width.toFloat() / viewW.toFloat()
        val scaleY = height.toFloat() / viewH.toFloat()
        val s = max(0.1f, minOf(scaleX, scaleY))
        val ox = (width - (viewW * s)).coerceAtLeast(0f) * 0.5f
        val oy = (height - (viewH * s)).coerceAtLeast(0f) * 0.5f

        val offX = torus.tileOffsetsRawX()
        val offY = torus.tileOffsetsRawY()

        for ((pid, body) in state.bodies) {
            val p = if (pid == myId) paintMe else paintOther
            val r = (body.radius.raw.toFloat() / Fx.SCALE.toFloat() * s).coerceAtLeast(1f)
            for (dx in offX) {
                for (dy in offY) {
                    val localXRaw = body.pos.x.raw + dx - topLeft.x.raw
                    val localYRaw = body.pos.y.raw + dy - topLeft.y.raw
                    val localX = localXRaw.toFloat() / Fx.SCALE.toFloat()
                    val localY = localYRaw.toFloat() / Fx.SCALE.toFloat()
                    if (localX < -2f || localY < -2f) continue
                    if (localX > viewW + 2f || localY > viewH + 2f) continue
                    val cx = ox + (localX * s)
                    val cy = oy + (localY * s)
                    canvas.drawCircle(cx, cy, r, p)
                }
            }
        }

        canvas.drawText("mode=$mode tick=${f.tick}", 16f, 40f, paintHud)
        canvas.drawText(f.status, 16f, 80f, paintHud)
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

    // reconnect logic is handled inside PhysicsAuthoritativeJoinController
}
