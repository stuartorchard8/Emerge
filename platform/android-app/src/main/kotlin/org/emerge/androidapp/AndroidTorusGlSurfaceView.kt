package org.emerge.androidapp

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.LaunchSettings
import org.emerge.demo.physics.PhysicsController
import org.emerge.demo.physics.PhysicsHostController
import org.emerge.demo.physics.PhysicsJoinController
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.demo.physics.audio.CrashAudioSystem
import org.emerge.demo.physics.createDefaultInitialState
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsSnapshot
import org.emerge.sim.core.physics.primitives.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Android GPU shader renderer (OpenGL ES 3.0):
 * - Full-screen fragment shader samples torus space per pixel (infinite tiling when zoomed out).
 * - Simulation/networking stays on CPU and feeds uniforms each frame.
 */
internal class AndroidTorusGlSurfaceView(
    activity: Activity,
    private val settings: LaunchSettings,
) : GLSurfaceView(activity) {
    private val cfg = PhysicsConfig()
    private val renderer: AndroidTorusGlRenderer

    @Volatile private var currentTouchInput: PhysicsInput = PhysicsInput.ZERO
    @Volatile private var singleTouchActive: Boolean = false
    @Volatile private var singleTouchStartX: Float = 0f
    @Volatile private var singleTouchStartY: Float = 0f
    @Volatile private var singleTouchX: Float = 0f
    @Volatile private var singleTouchY: Float = 0f
    @Volatile private var singleTouchActionMasked: Int = MotionEvent.ACTION_CANCEL
    @Volatile private var cameraRotationRad: Float = 0f
    private var isTransformGestureActive: Boolean = false
    private var transformPrevSpanPx: Float = 0f
    private var transformPrevAngleRad: Float = 0f

    // Data shared to GL thread
    private val stateLock = Any()
    private var latestFrame = PhysicsFrame(
        PhysicsSnapshot().mutable,
        null,
        0L,
        "sim: starting",
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private var simThread: HandlerThread? = null
    private var simHandler: Handler? = null
    private var simTickRunnable: Runnable? = null
    private var controller: PhysicsController? = null
    private var crashAudioSystem: CrashAudioSystem? = null

    init {
        setEGLContextClientVersion(3)
        val density = activity.resources.displayMetrics.density
        renderer = AndroidTorusGlRenderer(
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
        crashAudioSystem = CrashAudioSystem(AndroidOggCrashAudioEngine(context.assets))
        startSimulationLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSimulationLoop()
        crashAudioSystem?.release()
        crashAudioSystem = null
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
                cameraRotationRad = normalizeAngleRad(cameraRotationRad + rotationDeltaRad)

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
            latestFrame.state.raw.playerAngle(pid)?.toFloat() ?: 0f
        }

    private fun currentPlayerAngularVelocityTurns(): Float =
        synchronized(stateLock) {
            val pid = latestFrame.myId ?: return@synchronized 0f
            latestFrame.state.raw.playerAngularVelocity(pid)?.toFloat() ?: 0f
        }

    private fun clearSingleTouchState() {
        singleTouchActive = false
        singleTouchStartX = 0f
        singleTouchStartY = 0f
        singleTouchX = 0f
        singleTouchY = 0f
        singleTouchActionMasked = MotionEvent.ACTION_CANCEL
    }

    private fun updateSingleTouchState(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> clearSingleTouchState()

            MotionEvent.ACTION_DOWN -> {
                singleTouchActive = true
                singleTouchStartX = event.x
                singleTouchStartY = event.y
                singleTouchX = event.x
                singleTouchY = event.y
                singleTouchActionMasked = event.actionMasked
            }

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
            width,
            height,
//            singleTouchStartX,
//            singleTouchStartY,
            width/2.0f,
            height/2.0f,
            singleTouchX,
            singleTouchY,
            singleTouchActionMasked,
            currentPlayerAngleTurns(),
            currentPlayerAngularVelocityTurns(),
            cameraRotationRad,
        )
    }

    private fun startSimulationLoop() {
        if (simThread != null) {
            return
        }
        val thread = HandlerThread("android-sim-loop")
        thread.start()
        val handler = Handler(thread.looper)
        simThread = thread
        simHandler = handler
        val tickRunnable = object : Runnable {
            override fun run() {
                try {
                    val simController =
                        controller ?: run {
                            publishFrame(
                                PhysicsFrame(
                                    state = createDefaultInitialState(),
                                    myId = defaultPlayerIdFor(settings),
                                    tick = 0L,
                                    status = "sim: starting",
                                ),
                            )
                            createController(settings).also { controller = it }
                        }
                    currentTouchInput = computeTouchInputForCurrentOrientation()
                    val frame = simController.tick(currentTouchInput)
                    crashAudioSystem?.onFrame(frame)
                    publishFrame(frame)
                } catch (t: Throwable) {
                    Log.e(TAG, "Simulation loop failed", t)
                    publishFrame(
                        PhysicsFrame(
                            state = PhysicsSnapshot().mutable,
                            myId = null,
                            tick = 0L,
                            status = "sim failed: ${t.javaClass.simpleName}",
                        ),
                    )
                } finally {
                    if (simHandler != null) {
                        simHandler?.postDelayed(this, 16L)
                    }
                }
            }
        }
        simTickRunnable = tickRunnable
        handler.post(tickRunnable)
    }

    private fun stopSimulationLoop() {
        simTickRunnable?.let { runnable ->
            simHandler?.removeCallbacks(runnable)
        }
        simTickRunnable = null
        simHandler = null
        controller = null
        simThread?.quitSafely()
        simThread = null
    }

    private fun createController(settings: LaunchSettings): PhysicsController =
        when (settings.mode) {
            LaunchMode.HOST -> PhysicsHostController(port = settings.port, cfg = cfg, gameMode = settings.gameMode, acceptRemoteClients = true)
            LaunchMode.LOCAL -> PhysicsHostController(port = settings.port, cfg = cfg, gameMode = settings.gameMode, acceptRemoteClients = false)
            LaunchMode.JOIN -> PhysicsJoinController(hostIp = settings.hostIp, port = settings.port)
        }

    private fun publishFrame(frame: PhysicsFrame) {
        synchronized(stateLock) {
            latestFrame = frame
        }
        mainHandler.post {
            requestRender()
        }
    }

    private fun defaultPlayerIdFor(settings: LaunchSettings): PlayerId? =
        when (settings.mode) {
            LaunchMode.HOST,
            LaunchMode.LOCAL,
            -> PlayerId(0)

            LaunchMode.JOIN -> null
        }

    companion object {
        private const val TAG = "TorusGlSurfaceView"
    }
}
