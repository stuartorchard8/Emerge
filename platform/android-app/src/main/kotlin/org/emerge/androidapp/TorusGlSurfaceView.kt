package org.emerge.androidapp

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.LaunchSettings
import org.emerge.demo.physics.PhysicsAuthoritativeController
import org.emerge.demo.physics.PhysicsAuthoritativeHostController
import org.emerge.demo.physics.PhysicsAuthoritativeJoinController
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.demo.physics.createDefaultInitialState
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2

/**
 * Android GPU shader renderer (OpenGL ES 2.0):
 * - Full-screen fragment shader samples torus space per pixel (infinite tiling when zoomed out).
 * - Simulation/networking stays on CPU and feeds uniforms each frame.
 */
internal class TorusGlSurfaceView(
    activity: Activity,
    settings: LaunchSettings,
) : GLSurfaceView(activity) {
    private val cfg = PhysicsConfig()
    private val initial: PhysicsState = createDefaultInitialState()

    private val controller: PhysicsAuthoritativeController =
        when (settings.mode) {
            LaunchMode.HOST -> PhysicsAuthoritativeHostController(port = settings.port, cfg = cfg, acceptRemoteClients = true)
            LaunchMode.LOCAL -> PhysicsAuthoritativeHostController(port = settings.port, cfg = cfg, acceptRemoteClients = false)
            LaunchMode.JOIN -> PhysicsAuthoritativeJoinController(hostIp = settings.hostIp, port = settings.port)
        }

    @Volatile private var currentTouchInput: PhysicsInput = PhysicsInput(0, 0)

    // Data shared to GL thread
    private val stateLock = Any()
    private var latestFrame = PhysicsFrame(
        initial,
        null,
        0L,
        "",
    )

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val f: PhysicsFrame = controller.tick(currentTouchInput)
            synchronized(stateLock) {
                latestFrame = f
            }

            requestRender()
            handler.postDelayed(this, 16L)
        }
    }

    init {
        setEGLContextClientVersion(2)
        val density = activity.resources.displayMetrics.density
        val renderer = TorusGlRenderer(
            getState = {
                synchronized(stateLock) {
                    latestFrame
                }
            },
            contentScale = Vec2(density, density),
        )
        setRenderer(renderer)
        // Must be set *after* setRenderer() (GLThread created), otherwise GLSurfaceView crashes.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tickRunnable)
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
}
