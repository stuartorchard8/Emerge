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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Android GPU shader renderer (OpenGL ES 3.0):
 * - Full-screen fragment shader samples torus space per pixel (infinite tiling when zoomed out).
 * - Simulation/networking stays on CPU and feeds uniforms each frame.
 */
internal class TorusGlSurfaceView(
    activity: Activity,
    settings: LaunchSettings,
) : GLSurfaceView(activity) {
    private val cfg = PhysicsConfig()
    private val initial: PhysicsState = createDefaultInitialState()
    private val renderer: TorusGlRenderer

    private val controller: PhysicsAuthoritativeController =
        when (settings.mode) {
            LaunchMode.HOST -> PhysicsAuthoritativeHostController(port = settings.port, cfg = cfg, acceptRemoteClients = true)
            LaunchMode.LOCAL -> PhysicsAuthoritativeHostController(port = settings.port, cfg = cfg, acceptRemoteClients = false)
            LaunchMode.JOIN -> PhysicsAuthoritativeJoinController(hostIp = settings.hostIp, port = settings.port)
        }

    @Volatile private var currentTouchInput: PhysicsInput = PhysicsInput.ZERO
    @Volatile private var singleTouchActive: Boolean = false
    @Volatile private var singleTouchX: Float = 0f
    @Volatile private var singleTouchY: Float = 0f
    @Volatile private var singleTouchActionMasked: Int = MotionEvent.ACTION_CANCEL
    private var isTransformGestureActive: Boolean = false
    private var transformPrevSpanPx: Float = 0f
    private var transformPrevAngleRad: Float = 0f

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
            currentTouchInput = computeTouchInputForCurrentOrientation()
            val f: PhysicsFrame = controller.tick(currentTouchInput)
            synchronized(stateLock) {
                latestFrame = f
            }

            requestRender()
            handler.postDelayed(this, 16L)
        }
    }

    init {
        setEGLContextClientVersion(3)
        val density = activity.resources.displayMetrics.density
        renderer = TorusGlRenderer(
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
        if (event.pointerCount >= 2) {
            handleTransformTouch(event)
            clearSingleTouchState()
            currentTouchInput = PhysicsInput.ZERO
            return true
        }

        resetTransformGesture()
        updateSingleTouchState(event)
        return true
    }

    private fun handleTransformTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_DOWN,
            -> {
                beginTransformGesture(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTransformGestureActive) {
                    beginTransformGesture(event)
                    return
                }
                val currSpanPx = twoPointerSpanPx(event)
                val currAngleRad = twoPointerAngleRad(event)
                if (transformPrevSpanPx <= 0f || currSpanPx <= 0f) {
                    transformPrevSpanPx = currSpanPx
                    transformPrevAngleRad = currAngleRad
                    return
                }

                val zoomFactor = currSpanPx / transformPrevSpanPx
                val rotationDeltaRad = normalizeAngleRad(currAngleRad - transformPrevAngleRad)
                transformPrevSpanPx = currSpanPx
                transformPrevAngleRad = currAngleRad

                queueEvent {
                    renderer.applyCameraGesture(zoomFactor, rotationDeltaRad)
                }
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                resetTransformGesture()
            }
        }
    }

    private fun beginTransformGesture(event: MotionEvent) {
        isTransformGestureActive = true
        transformPrevSpanPx = twoPointerSpanPx(event)
        transformPrevAngleRad = twoPointerAngleRad(event)
    }

    private fun resetTransformGesture() {
        isTransformGestureActive = false
        transformPrevSpanPx = 0f
        transformPrevAngleRad = 0f
    }

    private fun twoPointerSpanPx(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return hypot(dx, dy)
    }

    private fun twoPointerAngleRad(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return atan2(dy, dx)
    }

    private fun normalizeAngleRad(angle: Float): Float {
        var out = angle
        val pi = PI.toFloat()
        val twoPi = (2.0 * PI).toFloat()
        while (out > pi) out -= twoPi
        while (out < -pi) out += twoPi
        return out
    }

    private fun currentPlayerAngleTurns(): Float =
        synchronized(stateLock) {
            val pid = latestFrame.myId ?: return@synchronized 0f
            latestFrame.state.bodies[pid]?.ang?.toFloat() ?: 0f
        }

    private fun clearSingleTouchState() {
        singleTouchActive = false
        singleTouchX = 0f
        singleTouchY = 0f
        singleTouchActionMasked = MotionEvent.ACTION_CANCEL
    }

    private fun updateSingleTouchState(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> clearSingleTouchState()

            else -> {
                singleTouchActive = true
                singleTouchX = event.x
                singleTouchY = event.y
                singleTouchActionMasked = event.actionMasked
            }
        }
    }

    private fun computeTouchInputForCurrentOrientation(): PhysicsInput {
        if (!singleTouchActive) return PhysicsInput.ZERO
        if (width <= 0 || height <= 0) return PhysicsInput.ZERO
        return TouchInputMapper.toPhysicsInput(
            widthPx = width,
            heightPx = height,
            x = singleTouchX,
            y = singleTouchY,
            actionMasked = singleTouchActionMasked,
            rocketAngleTurns = currentPlayerAngleTurns(),
        )
    }
}
