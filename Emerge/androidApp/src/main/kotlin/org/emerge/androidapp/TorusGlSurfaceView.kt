package org.emerge.androidapp

import android.app.Activity
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.concurrent.thread
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.emerge.net.api.Pipe
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusCoverTracker
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsReducer
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.auth.AuthoritativeClient
import org.emerge.sim.sync.auth.AuthoritativeHost
import org.emerge.sim.sync.auth.StateCodec

/**
 * Android GPU shader renderer (OpenGL ES 2.0):
 * - Full-screen fragment shader samples torus space per pixel (infinite tiling when zoomed out).
 * - Simulation/networking stays on CPU and feeds uniforms each frame.
 */
internal class TorusGlSurfaceView(
    private val activity: Activity,
    private val mode: String,
    private val hostIp: String,
    private val port: Int,
) : GLSurfaceView(activity) {
    private val worldW = Fx.fromInt(800)
    private val worldH = Fx.fromInt(500)
    private val radius = Fx.fromInt(16)

    private val reducer = PhysicsReducer()

    private val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    private val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec

    private val localPlayerId: PlayerId
    private val host: AuthoritativeHost<PhysicsState, PhysicsInput>?
    private val client: AuthoritativeClient<PhysicsState, PhysicsInput>?
    private val joinRemote: DelegatingPipe?

    @Volatile private var reconnecting: Boolean = false
    @Volatile private var netStatus: String = "net: init"

    private val initial = PhysicsState(
        width = worldW,
        height = worldH,
        bodies = mapOf(
            PlayerId(0) to CircleBody(PlayerId(0), Vec2Fx(Fx.fromInt(200), Fx.fromInt(250)), Vec2Fx(Fx(0), Fx(0)), radius),
            PlayerId(1) to CircleBody(PlayerId(1), Vec2Fx(Fx.fromInt(600), Fx.fromInt(250)), Vec2Fx(Fx(0), Fx(0)), radius),
        ),
    )

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
            host?.let { h ->
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

            val st = client?.state ?: host?.state ?: initial
            val myId = client?.playerId ?: localPlayerId
            val tick = client?.tick?.value ?: host?.tick?.value ?: 0L
            val status = netStatus
            synchronized(stateLock) {
                latestState = st
                latestMyId = myId
                latestTick = tick
                latestStatus = status
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

        when (mode) {
            MainActivity.MODE_HOST -> {
                localPlayerId = PlayerId(0)
                joinRemote = null
                client = null
                host = AuthoritativeHost(
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
            }

            MainActivity.MODE_JOIN -> {
                localPlayerId = PlayerId(0)
                host = null
                val remote = DelegatingPipe()
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
                // default: host-only loopback-ish (no join)
                localPlayerId = PlayerId(0)
                joinRemote = null
                client = null
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
                netStatus = "net: host-only (no join)"
            }
        }
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

    private var torus: Torus2D? = null
    private var tracker: TorusCoverTracker? = null

    // zoom < 1 => zoom out (view larger than world)
    private var zoom: Float = 0.75f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = linkProgram(vertexShaderSrc, fragmentShaderSrc(maxBodies))
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

        if (torus == null) torus = Torus2D(width = st.width, height = st.height)
        val t = torus!!

        val worldW = st.width.raw.toFloat() / Fx.SCALE.toFloat()
        val worldH = st.height.raw.toFloat() / Fx.SCALE.toFloat()

        val viewW = worldW / zoom
        val viewH = worldH / zoom

        val myId = frame.myId
        val focusWrapped =
            if (myId != null) st.bodies[myId]?.pos ?: Vec2Fx(Fx(st.width.raw / 2), Fx(st.height.raw / 2))
            else Vec2Fx(Fx(st.width.raw / 2), Fx(st.height.raw / 2))

        val tr = (tracker ?: TorusCoverTracker(t, focusWrapped)).also { tracker = it }
        val focusCover = tr.update(focusWrapped)
        val topLeftX = focusCover.x.raw.toFloat() / Fx.SCALE.toFloat() - viewW * 0.5f
        val topLeftY = focusCover.y.raw.toFloat() / Fx.SCALE.toFloat() - viewH * 0.5f

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
        GLES20.glUniform2f(uWorld, worldW, worldH)
        GLES20.glUniform2f(uView, viewW, viewH)
        GLES20.glUniform2f(uTopLeft, topLeftX, topLeftY)
        GLES20.glUniform1i(uMyId, myId?.value ?: -1)

        val bodies = st.bodies.values.toList()
        val n = minOf(maxBodies, bodies.size)
        for (i in 0 until maxBodies) {
            val base = i * 4
            if (i < n) {
                val b = bodies[i]
                bodiesFloats[base + 0] = b.pos.x.raw.toFloat() / Fx.SCALE.toFloat()
                bodiesFloats[base + 1] = b.pos.y.raw.toFloat() / Fx.SCALE.toFloat()
                bodiesFloats[base + 2] = b.radius.raw.toFloat() / Fx.SCALE.toFloat()
                bodiesFloats[base + 3] = b.playerId.value.toFloat()
            } else {
                bodiesFloats[base + 0] = 0f
                bodiesFloats[base + 1] = 0f
                bodiesFloats[base + 2] = 0f
                bodiesFloats[base + 3] = -1f
            }
        }
        GLES20.glUniform1i(uBodyCount, n)
        GLES20.glUniform4fv(uBodies0, maxBodies, bodiesFloats, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun linkProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            GLES20.glDeleteProgram(p)
            error("GL program link failed: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("GL shader compile failed: $log\n\n$src")
        }
        return s
    }

    private val vertexShaderSrc = """
        attribute vec2 aPos;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    private fun fragmentShaderSrc(maxBodies: Int): String = """
        precision mediump float;
        precision mediump int;
        #define MAX_BODIES $maxBodies

        uniform vec2 uResolution;
        uniform vec2 uWorld;
        uniform vec2 uView;
        uniform vec2 uTopLeft;
        uniform int uBodyCount;
        uniform int uMyId;
        uniform vec4 uBodies[MAX_BODIES];

        vec2 wrap2(vec2 p, vec2 size) {
            vec2 q = mod(p, size);
            if (q.x < 0.0) q.x += size.x;
            if (q.y < 0.0) q.y += size.y;
            return q;
        }

        float wrapDelta(float d, float size) {
            float halfSize = 0.5 * size;
            float x = mod(d + halfSize, size) - halfSize;
            return x;
        }

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution;
            vec2 cover = uTopLeft + uv * uView;
            vec2 p = wrap2(cover, uWorld);

            vec3 col = vec3(0.07, 0.07, 0.07);
            float best = 1e30;

            for (int i = 0; i < MAX_BODIES; i++) {
                if (i >= uBodyCount) break;
                vec4 b = uBodies[i];
                float dx = wrapDelta(p.x - b.x, uWorld.x);
                float dy = wrapDelta(p.y - b.y, uWorld.y);
                float d2 = dx*dx + dy*dy;
                float r2 = b.z*b.z;
                if (d2 <= r2 && d2 < best) {
                    best = d2;
                    int pid = int(b.w + 0.5);
                    if (pid == uMyId) col = vec3(0.18, 0.53, 0.67);
                    else col = vec3(0.80, 0.80, 0.80);
                }
            }
            gl_FragColor = vec4(col, 1.0);
        }
    """.trimIndent()
}

