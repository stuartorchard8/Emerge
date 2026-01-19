package org.emerge.androidapp

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.emerge.demo.physics.AuthoritativeDemoFrame
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.PhysicsAuthoritativeHostController
import org.emerge.demo.physics.PhysicsAuthoritativeJoinController
import org.emerge.demo.physics.PhysicsDemoConfig
import org.emerge.demo.physics.TorusShaderSources
import org.emerge.demo.physics.TorusGlProgramFactory
import org.emerge.demo.physics.TorusViewComputer
import org.emerge.demo.physics.createDefaultInitialState
import org.emerge.demo.physics.packBodiesToFloatArray
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState

/**
 * Android GPU shader renderer (OpenGL ES 2.0):
 * - Full-screen fragment shader samples torus space per pixel (infinite tiling when zoomed out).
 * - Simulation/networking stays on CPU and feeds uniforms each frame.
 */
internal class TorusGlSurfaceView(
    private val activity: Activity,
    private val mode: LaunchMode,
    private val hostIp: String,
    private val port: Int,
) : GLSurfaceView(activity) {
    private val cfg = PhysicsDemoConfig()
    private val initial: PhysicsState = createDefaultInitialState(cfg)

    private val hostController: PhysicsAuthoritativeHostController? =
        when (mode) {
            LaunchMode.HOST -> PhysicsAuthoritativeHostController(port = port, cfg = cfg, acceptRemoteClients = true)
            LaunchMode.LOCAL -> PhysicsAuthoritativeHostController(port = port, cfg = cfg, acceptRemoteClients = false)
            LaunchMode.JOIN -> null
        }

    private val joinController: PhysicsAuthoritativeJoinController? =
        when (mode) {
            LaunchMode.JOIN -> PhysicsAuthoritativeJoinController(hostIp = hostIp, port = port, cfg = cfg)
            else -> null
        }

    @Volatile private var currentTouchInput: PhysicsInput = PhysicsInput(0, 0)

    // Data shared to GL thread
    private val stateLock = Any()
    private var latestState: PhysicsState = initial
    private var latestMyId: PlayerId? = null
    private var latestTick: Long = 0L
    private var latestStatus: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val f: AuthoritativeDemoFrame =
                when {
                    hostController != null -> hostController.tick(currentTouchInput)
                    joinController != null -> joinController.tick(currentTouchInput)
                    else -> AuthoritativeDemoFrame(state = initial, myId = PlayerId(0), tick = 0L, status = "net: init")
                }
            synchronized(stateLock) {
                latestState = f.state ?: initial
                latestMyId = f.myId
                latestTick = f.tick
                latestStatus = f.status
            }

            requestRender()
            handler.postDelayed(this, 16L)
        }
    }

    init {
        setEGLContextClientVersion(2)
        val renderer = TorusGlRenderer(
            getState = {
                synchronized(stateLock) {
                    GlFrame(
                        state = latestState,
                        myId = latestMyId,
                        tick = latestTick,
                        status = latestStatus,
                    )
                }
            }
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

private data class GlFrame(
    val state: PhysicsState,
    val myId: PlayerId?,
    val tick: Long,
    val status: String,
)

private class TorusGlRenderer(
    private val getState: () -> GlFrame,
) : GLSurfaceView.Renderer {
    private var program: Int = 0
    private var aPos: Int = -1

    private var uResolution: Int = -1
    private var uWorld: Int = -1
    private var uView: Int = -1
    private var uTopLeft: Int = -1
    private var uBodyCount: Int = -1
    private var uMyId: Int = -1
    private var uBodies0: Int = -1

    private val maxBodies = 128
    private val bodiesFloats = FloatArray(4 * maxBodies)

    private val view = TorusViewComputer()

    // zoom < 1 => zoom out (view larger than world)
    private var zoom: Float = 0.75f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = TorusGlProgramFactory.createProgramGles2(maxBodies)
        aPos = GLES20.glGetAttribLocation(program, "aPos")

        uResolution = GLES20.glGetUniformLocation(program, "uResolution")
        uWorld = GLES20.glGetUniformLocation(program, "uWorld")
        uView = GLES20.glGetUniformLocation(program, "uView")
        uTopLeft = GLES20.glGetUniformLocation(program, "uTopLeft")
        uBodyCount = GLES20.glGetUniformLocation(program, "uBodyCount")
        uMyId = GLES20.glGetUniformLocation(program, "uMyId")
        uBodies0 = GLES20.glGetUniformLocation(program, "uBodies[0]")

        GLES20.glClearColor(0.07f, 0.07f, 0.07f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = getState()
        val st = frame.state
        val myId = frame.myId
        val params = view.compute(state = st, myId = myId, zoom = zoom)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Full-screen triangle in clip space
        // (x,y): (-1,-1), (3,-1), (-1,3)
        val verts = floatArrayOf(
            -1f, -1f,
            3f, -1f,
            -1f, 3f,
        )
        val vb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        vb.put(verts).position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb)

        // uniforms
        // resolution comes from viewport; GLES doesn't expose it, but we can query it
        val vp = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
        GLES20.glUniform2f(uResolution, vp[2].toFloat(), vp[3].toFloat())
        GLES20.glUniform2f(uWorld, params.worldW, params.worldH)
        GLES20.glUniform2f(uView, params.viewW, params.viewH)
        GLES20.glUniform2f(uTopLeft, params.topLeftCoverX, params.topLeftCoverY)
        GLES20.glUniform1i(uMyId, myId?.value ?: -1)

        val n = minOf(maxBodies, st.bodies.size)
        packBodiesToFloatArray(state = st, maxBodies = maxBodies, out = bodiesFloats)
        GLES20.glUniform1i(uBodyCount, n)
        GLES20.glUniform4fv(uBodies0, maxBodies, bodiesFloats, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(aPos)
    }

}

