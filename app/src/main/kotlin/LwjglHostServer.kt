package org.example.app

import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
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
import org.emerge.sim.sync.auth.AuthoritativeHost
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
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
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
import org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL33C.GL_TRIANGLES
import org.lwjgl.opengl.GL33C.glClear
import org.lwjgl.opengl.GL33C.glClearColor
import org.lwjgl.opengl.GL33C.glDeleteProgram
import org.lwjgl.opengl.GL33C.glDrawArrays
import org.lwjgl.opengl.GL33C.glGetUniformLocation
import org.lwjgl.opengl.GL33C.glUniform1i
import org.lwjgl.opengl.GL33C.glUniform2f
import org.lwjgl.opengl.GL33C.glUniform4fv
import org.lwjgl.opengl.GL33C.glUseProgram
import org.lwjgl.opengl.GL33C.glViewport
import org.lwjgl.opengl.GL33C.glGenVertexArrays
import org.lwjgl.opengl.GL33C.glBindVertexArray
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL

fun runHostGl(port: Int) {
    val worldW = Fx.fromInt(800)
    val worldH = Fx.fromInt(500)
    val radius = Fx.fromInt(16)

    val reducer = PhysicsReducer()
    val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec

    val initial = PhysicsState(
        width = worldW,
        height = worldH,
        bodies = mapOf(
            PlayerId(0) to CircleBody(
                playerId = PlayerId(0),
                pos = Vec2Fx(Fx.fromInt(200), Fx.fromInt(250)),
                vel = Vec2Fx(Fx(0), Fx(0)),
                radius = radius,
            ),
        ),
    )

    val host = AuthoritativeHost(
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

    thread(isDaemon = true, name = "net-accept") {
        try {
            val listener = Tcp.listen(port = port, backlog = 8)
            println("host-gl: listening :$port")
            while (true) {
                val pipe = listener.accept()
                host.acceptClient(pipe)
                println("host-gl: client joined")
            }
        } catch (t: Throwable) {
            val msg = t.message?.take(120) ?: ""
            println("host-gl: accept failed: ${t.javaClass.simpleName} $msg")
        }
    }

    if (!glfwInit()) error("GLFW init failed")
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)

    val window = glfwCreateWindow(960, 600, "Emerge host-gl (:$port)", NULL, NULL)
    if (window == NULL) error("Failed to create GLFW window")

    val pressed = BooleanArray(512)
    glfwSetKeyCallback(window) { win, key, _, action, _ ->
        if (key in 0 until pressed.size) pressed[key] = (action != GLFW_RELEASE)
        if (key == GLFW_KEY_ESCAPE && action != GLFW_RELEASE) glfwSetWindowShouldClose(win, true)
    }

    glfwMakeContextCurrent(window)
    glfwSwapInterval(1)
    glfwShowWindow(window)
    GL.createCapabilities()

    val program = buildProgram(vertexShaderSrc, fragmentShaderSrc)
    glUseProgram(program)
    val vao = glGenVertexArrays()
    glBindVertexArray(vao)

    val uResolution = glGetUniformLocation(program, "uResolution")
    val uWorld = glGetUniformLocation(program, "uWorld")
    val uView = glGetUniformLocation(program, "uView")
    val uTopLeft = glGetUniformLocation(program, "uTopLeft")
    val uBodyCount = glGetUniformLocation(program, "uBodyCount")
    val uMyId = glGetUniformLocation(program, "uMyId")
    val uBodies = glGetUniformLocation(program, "uBodies")

    var zoom = 0.75f
    val torus = Torus2D(width = worldW, height = worldH)
    val tracker = TorusCoverTracker(torus, initial.bodies[PlayerId(0)]!!.pos)

    while (!glfwWindowShouldClose(window)) {
        glfwPollEvents()

        if (pressed[GLFW_KEY_MINUS]) zoom = max(0.05f, zoom * 0.98f)
        if (pressed[GLFW_KEY_EQUAL]) zoom = min(20f, zoom * 1.02f)

        val ax = axis(pressed[GLFW_KEY_A], pressed[GLFW_KEY_D])
        val ay = axis(pressed[GLFW_KEY_W], pressed[GLFW_KEY_S])
        host.setLocalInput(PlayerId(0), PhysicsInput(ax, ay))

        host.pollNetwork()
        host.step()

        val state = host.state
        val myId = PlayerId(0)

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
            val worldWf = state.width.raw.toFloat() / Fx.SCALE.toFloat()
            val worldHf = state.height.raw.toFloat() / Fx.SCALE.toFloat()
            glUniform2f(uWorld, worldWf, worldHf)

            val viewW = worldWf / zoom
            val viewH = worldHf / zoom
            glUniform2f(uView, viewW, viewH)

            val focusWrapped = state.bodies[myId]?.pos ?: Vec2Fx(Fx(state.width.raw / 2), Fx(state.height.raw / 2))
            val focusCover = tracker.update(focusWrapped)
            val topLeftCoverX = focusCover.x.raw.toFloat() / Fx.SCALE.toFloat() - viewW * 0.5f
            val topLeftCoverY = focusCover.y.raw.toFloat() / Fx.SCALE.toFloat() - viewH * 0.5f
            glUniform2f(uTopLeft, topLeftCoverX, topLeftCoverY)

            glUniform1i(uMyId, myId.value)
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
            glDrawArrays(GL_TRIANGLES, 0, 3)
        }

        glfwSwapBuffers(window)
    }

    glDeleteProgram(program)
    glfwDestroyWindow(window)
    glfwTerminate()
}

