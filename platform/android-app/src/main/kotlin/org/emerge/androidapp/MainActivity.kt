package org.emerge.androidapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import org.emerge.demo.physics.AuthoritativeDemoFrame
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.LaunchSettings
import org.emerge.demo.physics.PhysicsAuthoritativeHostController
import org.emerge.demo.physics.PhysicsAuthoritativeJoinController
import org.emerge.demo.physics.PhysicsDemoConfig
import org.emerge.demo.physics.RenderBackend
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
        // Single launch path: always start in-app with a launcher UI.
        // (We still read intent extras only to prefill the UI for convenience.)
        val initial = LaunchSettings(
            mode = when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_HOST -> LaunchMode.HOST
                MODE_JOIN -> LaunchMode.JOIN
                else -> LaunchMode.LOCAL
            },
            hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: "127.0.0.1",
            port = intent.getIntExtra(EXTRA_PORT, 7777),
            renderBackend = when (intent.getStringExtra(EXTRA_RENDERER)) {
                RENDERER_CANVAS -> RenderBackend.CPU
                else -> RenderBackend.GPU
            },
        )
        setContentView(LauncherView(activity = this, initial = initial))
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

private class LauncherView(
    private val activity: Activity,
    initial: LaunchSettings,
) : LinearLayout(activity) {
    private val modeSpinner: Spinner
    private val rendererSpinner: Spinner
    private val hostIpEdit: EditText
    private val portEdit: EditText

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(0x11, 0x11, 0x11))
        val pad = (16f * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        modeSpinner = Spinner(context)
        modeSpinner.adapter = themedSpinnerAdapter(listOf("Local", "Host", "Join"))

        rendererSpinner = Spinner(context)
        rendererSpinner.adapter = themedSpinnerAdapter(listOf("GPU (GL)", "CPU (Canvas)"))

        hostIpEdit = EditText(context).apply {
            hint = "Host IP (for Join)"
            setText(initial.hostIp)
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
            setHintTextColor(Color.rgb(0x88, 0x88, 0x88))
        }

        portEdit = EditText(context).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial.port.toString())
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
            setHintTextColor(Color.rgb(0x88, 0x88, 0x88))
        }

        val start = Button(context).apply {
            text = "Start"
            setOnClickListener { startSelected() }
        }

        val modeLabel = TextView(context).apply {
            text = "Mode"
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
        }
        val rendererLabel = TextView(context).apply {
            text = "Renderer"
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
        }

        // Ensure spinners are visible on dark background regardless of theme defaults.
        modeSpinner.setBackgroundColor(Color.rgb(0x22, 0x22, 0x22))
        rendererSpinner.setBackgroundColor(Color.rgb(0x22, 0x22, 0x22))

        addView(modeLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(modeSpinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(rendererLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(rendererSpinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hostIpEdit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(portEdit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(start, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Prefill selections
        modeSpinner.setSelection(
            when (initial.mode) {
                LaunchMode.LOCAL -> 0
                LaunchMode.HOST -> 1
                LaunchMode.JOIN -> 2
            },
        )
        rendererSpinner.setSelection(if (initial.renderBackend == RenderBackend.GPU) 0 else 1)
        syncEnabledFields()

        modeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    syncEnabledFields()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                    syncEnabledFields()
                }
            }
    }

    private fun syncEnabledFields() {
        hostIpEdit.isEnabled = selectedMode() == LaunchMode.JOIN
    }

    private fun themedSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            init {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.rgb(0x11, 0x11, 0x11))
                return v
            }
        }

    private fun selectedMode(): LaunchMode =
        when (modeSpinner.selectedItemPosition) {
            1 -> LaunchMode.HOST
            2 -> LaunchMode.JOIN
            else -> LaunchMode.LOCAL
        }

    private fun selectedRenderBackend(): RenderBackend =
        if (rendererSpinner.selectedItemPosition == 0) RenderBackend.GPU else RenderBackend.CPU

    private fun startSelected() {
        val settings = LaunchSettings(
            mode = selectedMode(),
            renderBackend = selectedRenderBackend(),
            hostIp = hostIpEdit.text?.toString()?.trim().orEmpty().ifBlank { "127.0.0.1" },
            port = portEdit.text?.toString()?.toIntOrNull() ?: 7777,
        )

        val content: View =
            when (settings.renderBackend) {
                RenderBackend.CPU -> PhysicsLockstepView(activity, settings = settings)
                RenderBackend.GPU -> TorusGlSurfaceView(activity, mode = settings.mode, hostIp = settings.hostIp, port = settings.port)
            }
        activity.setContentView(content)
    }
}

private class PhysicsLockstepView(
    context: Activity,
    private val settings: LaunchSettings,
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
        when (settings.mode) {
            LaunchMode.HOST -> {
                hostController = PhysicsAuthoritativeHostController(port = settings.port, cfg = cfg, acceptRemoteClients = true)
                joinController = null
            }

            LaunchMode.JOIN -> {
                hostController = null
                joinController = PhysicsAuthoritativeJoinController(hostIp = settings.hostIp, port = settings.port, cfg = cfg)
            }

            else -> {
                // default: host-only loopback-ish (no join)
                hostController = PhysicsAuthoritativeHostController(port = settings.port, cfg = cfg, acceptRemoteClients = false)
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

        canvas.drawText("mode=${settings.mode} tick=${f.tick}", 16f, 40f, paintHud)
        canvas.drawText(f.status, 16f, 80f, paintHud)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        currentTouchInput = TouchInputMapper.toPhysicsInput(
            widthPx = width,
            heightPx = height,
            x = event.x,
            y = event.y,
            actionMasked = event.actionMasked,
        )
        return true
    }

    // reconnect logic is handled inside PhysicsAuthoritativeJoinController
}
