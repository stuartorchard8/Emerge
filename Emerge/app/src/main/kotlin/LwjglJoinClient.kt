package org.example.app

import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusCoverTracker
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.auth.AuthoritativeClient
import org.emerge.sim.sync.auth.StateCodec
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_KEY_A
import org.lwjgl.glfw.GLFW.GLFW_KEY_D
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_KEY_W
import org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import org.lwjgl.glfw.GLFW.GLFW_RESIZABLE
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetFramebufferSize
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwSetKeyCallback
import org.lwjgl.glfw.GLFW.glfwShowWindow
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFW.glfwWindowShouldClose
import org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL33C.GL_FLOAT
import org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL33C.GL_LINK_STATUS
import org.lwjgl.opengl.GL33C.GL_TRIANGLES
import org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL33C.glClear
import org.lwjgl.opengl.GL33C.glClearColor
import org.lwjgl.opengl.GL33C.glDeleteProgram
import org.lwjgl.opengl.GL33C.glDeleteShader
import org.lwjgl.opengl.GL33C.glDrawArrays
import org.lwjgl.opengl.GL33C.glGetProgramInfoLog
import org.lwjgl.opengl.GL33C.glGetProgrami
import org.lwjgl.opengl.GL33C.glGetShaderInfoLog
import org.lwjgl.opengl.GL33C.glGetShaderi
import org.lwjgl.opengl.GL33C.glGetUniformLocation
import org.lwjgl.opengl.GL33C.glLinkProgram
import org.lwjgl.opengl.GL33C.glShaderSource
import org.lwjgl.opengl.GL33C.glUseProgram
import org.lwjgl.opengl.GL33C.glUniform1f
import org.lwjgl.opengl.GL33C.glUniform1i
import org.lwjgl.opengl.GL33C.glUniform2f
import org.lwjgl.opengl.GL33C.glUniform4fv
import org.lwjgl.opengl.GL33C.glCompileShader
import org.lwjgl.opengl.GL33C.glCreateProgram
import org.lwjgl.opengl.GL33C.glCreateShader
import org.lwjgl.opengl.GL33C.glAttachShader
import org.lwjgl.opengl.GL33C.glBindBuffer
import org.lwjgl.opengl.GL33C.glBindVertexArray
import org.lwjgl.opengl.GL33C.glBufferData
import org.lwjgl.opengl.GL33C.glEnableVertexAttribArray
import org.lwjgl.opengl.GL33C.glGenBuffers
import org.lwjgl.opengl.GL33C.glGenVertexArrays
import org.lwjgl.opengl.GL33C.glVertexAttribPointer
import org.lwjgl.opengl.GL33C.glViewport
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL

internal const val MAX_BODIES = 128

fun runJoinGl(hostIp: String, port: Int, maxRunMs: Long? = null): Boolean {
    val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec

    val remote = DelegatingPipe()
    val client = AuthoritativeClient(
        pipe = remote,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        onDisconnected = { reason ->
            println("join-gl: disconnected ($reason)")
        },
    )

    var sawFirstSnapshot: Boolean = false

    thread(isDaemon = true, name = "net-connect") {
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                println("join-gl: connecting $hostIp:$port (try $attempt)")
                remote.setDelegate(Tcp.connect(hostIp, port))
                client.resetConnection("connect")
                client.startHandshake(force = true)
                println("join-gl: connected (handshake)")
                break
            } catch (t: Throwable) {
                val msg = t.message?.take(100) ?: ""
                println("join-gl: connect failed: ${t.javaClass.simpleName} $msg")
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    if (!glfwInit()) error("GLFW init failed")
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)

    val window = glfwCreateWindow(960, 600, "Emerge join-gl ($hostIp:$port)", NULL, NULL)
    if (window == NULL) error("Failed to create GLFW window")

    val pressed = BooleanArray(512)
    glfwSetKeyCallback(window) { win, key, _, action, _ ->
        if (key in 0 until pressed.size) {
            pressed[key] = (action != GLFW_RELEASE)
        }
        if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
            glfwSetWindowShouldClose(win, true)
        }
    }

    glfwMakeContextCurrent(window)
    glfwSwapInterval(1)
    glfwShowWindow(window)
    GL.createCapabilities()

    val program = buildProgram(vertexShaderSrc, fragmentShaderSrc)
    glUseProgram(program)

    // Fullscreen triangle (no VBO needed), but some drivers want a VAO bound in core profile.
    val vao = glGenVertexArrays()
    glBindVertexArray(vao)

    // Uniform locations
    val uResolution = glGetUniformLocation(program, "uResolution")
    val uWorld = glGetUniformLocation(program, "uWorld")
    val uView = glGetUniformLocation(program, "uView")
    val uTopLeft = glGetUniformLocation(program, "uTopLeft")
    val uBodyCount = glGetUniformLocation(program, "uBodyCount")
    val uMyId = glGetUniformLocation(program, "uMyId")
    val uBodies = glGetUniformLocation(program, "uBodies")

    var zoom = 0.75f // <1 => zoom out (see multiple tiles)
    var torus: Torus2D? = null
    var tracker: TorusCoverTracker? = null
    val startedAt = System.currentTimeMillis()

    while (!glfwWindowShouldClose(window)) {
        glfwPollEvents()

        // zoom controls: '-' zoom out, '=' zoom in
        if (pressed[GLFW_KEY_MINUS]) zoom = max(0.05f, zoom * 0.98f)
        if (pressed[GLFW_KEY_EQUAL]) zoom = min(20f, zoom * 1.02f)

        client.poll()
        if (!sawFirstSnapshot && client.state != null && client.playerId != null) {
            sawFirstSnapshot = true
            println("join-gl: first snapshot (playerId=${client.playerId} tick=${client.tick.value})")
        }

        // WASD input
        val ax = axis(pressed[GLFW_KEY_A], pressed[GLFW_KEY_D])
        val ay = axis(pressed[GLFW_KEY_W], pressed[GLFW_KEY_S])
        client.sendInput(PhysicsInput(ax, ay))

        val state = client.state
        val myId = client.playerId

        // Update world/torus info once we have state
        if (state != null && torus == null) {
            torus = Torus2D(width = state.width, height = state.height)
        }

        // framebuffer size
        MemoryStack.stackPush().use { st ->
            val pw = st.mallocInt(1)
            val ph = st.mallocInt(1)
            glfwGetFramebufferSize(window, pw, ph)
            val fbW = max(1, pw[0])
            val fbH = max(1, ph[0])
            glViewport(0, 0, fbW, fbH)

            glClearColor(0.07f, 0.07f, 0.07f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)

            glUniform2f(uResolution, fbW.toFloat(), fbH.toFloat())

            if (state != null && torus != null) {
                val t = torus!!
                val worldW = state.width.raw.toFloat() / Fx.SCALE.toFloat()
                val worldH = state.height.raw.toFloat() / Fx.SCALE.toFloat()
                glUniform2f(uWorld, worldW, worldH)

                val viewW = worldW / zoom
                val viewH = worldH / zoom
                glUniform2f(uView, viewW, viewH)

                // focus point: my body if known else center
                val focusWrapped: Vec2Fx =
                    if (myId != null) state.bodies[myId]?.pos ?: Vec2Fx(Fx(state.width.raw / 2), Fx(state.height.raw / 2))
                    else Vec2Fx(Fx(state.width.raw / 2), Fx(state.height.raw / 2))

                val tr = (tracker ?: TorusCoverTracker(t, focusWrapped)).also { tracker = it }
                val focusCover = tr.update(focusWrapped)
                val topLeftCoverX = focusCover.x.raw.toFloat() / Fx.SCALE.toFloat() - viewW * 0.5f
                val topLeftCoverY = focusCover.y.raw.toFloat() / Fx.SCALE.toFloat() - viewH * 0.5f
                glUniform2f(uTopLeft, topLeftCoverX, topLeftCoverY)

                glUniform1i(uMyId, myId?.value ?: -1)

                val bodies = state.bodies.values.toList()
                val n = min(MAX_BODIES, bodies.size)
                glUniform1i(uBodyCount, n)

                val fb = st.mallocFloat(4 * MAX_BODIES)
                for (i in 0 until MAX_BODIES) {
                    val base = i * 4
                    if (i < n) {
                        val b = bodies[i]
                        fb.put(base + 0, b.pos.x.raw.toFloat() / Fx.SCALE.toFloat())
                        fb.put(base + 1, b.pos.y.raw.toFloat() / Fx.SCALE.toFloat())
                        fb.put(base + 2, b.radius.raw.toFloat() / Fx.SCALE.toFloat())
                        fb.put(base + 3, b.playerId.value.toFloat())
                    } else {
                        fb.put(base + 0, 0f)
                        fb.put(base + 1, 0f)
                        fb.put(base + 2, 0f)
                        fb.put(base + 3, -1f)
                    }
                }
                glUniform4fv(uBodies, fb)
            } else {
                // no state yet: still set something valid
                glUniform2f(uWorld, 1f, 1f)
                glUniform2f(uView, 1f, 1f)
                glUniform2f(uTopLeft, 0f, 0f)
                glUniform1i(uMyId, -1)
                glUniform1i(uBodyCount, 0)
            }

            glDrawArrays(GL_TRIANGLES, 0, 3)
        }

        glfwSwapBuffers(window)

        if (maxRunMs != null && (System.currentTimeMillis() - startedAt) >= maxRunMs) {
            glfwSetWindowShouldClose(window, true)
        }
    }

    glDeleteProgram(program)
    glfwDestroyWindow(window)
    glfwTerminate()
    return sawFirstSnapshot
}

internal fun axis(neg: Boolean, pos: Boolean): Int =
    when {
        neg && !pos -> -1
        pos && !neg -> 1
        else -> 0
    }

internal fun buildProgram(vs: String, fs: String): Int {
    val v = compileShader(GL_VERTEX_SHADER, vs)
    val f = compileShader(GL_FRAGMENT_SHADER, fs)
    val p = glCreateProgram()
    glAttachShader(p, v)
    glAttachShader(p, f)
    glLinkProgram(p)
    val ok = glGetProgrami(p, GL_LINK_STATUS)
    if (ok == 0) {
        val log = glGetProgramInfoLog(p)
        glDeleteShader(v)
        glDeleteShader(f)
        glDeleteProgram(p)
        error("Program link failed:\n$log")
    }
    glDeleteShader(v)
    glDeleteShader(f)
    return p
}

internal fun compileShader(type: Int, src: String): Int {
    val s = glCreateShader(type)
    glShaderSource(s, src)
    glCompileShader(s)
    val ok = glGetShaderi(s, GL_COMPILE_STATUS)
    if (ok == 0) {
        val log = glGetShaderInfoLog(s)
        glDeleteShader(s)
        error("Shader compile failed:\n$log\n\nSource:\n$src")
    }
    return s
}

internal val vertexShaderSrc = """
    #version 330 core
    void main() {
        // fullscreen triangle
        vec2 p;
        if (gl_VertexID == 0) p = vec2(-1.0, -1.0);
        else if (gl_VertexID == 1) p = vec2(3.0, -1.0);
        else p = vec2(-1.0, 3.0);
        gl_Position = vec4(p, 0.0, 1.0);
    }
""".trimIndent()

internal val fragmentShaderSrc = """
    #version 330 core
    out vec4 FragColor;

    uniform vec2 uResolution;
    uniform vec2 uWorld;
    uniform vec2 uView;
    uniform vec2 uTopLeft;      // cover-space (unwrapped) top-left in world units

    uniform int uBodyCount;
    uniform int uMyId;
    uniform vec4 uBodies[$MAX_BODIES]; // x,y,r,playerId

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

        for (int i = 0; i < uBodyCount; i++) {
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

        FragColor = vec4(col, 1.0);
    }
""".trimIndent()

