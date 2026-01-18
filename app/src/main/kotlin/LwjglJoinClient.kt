package org.example.app

import kotlin.math.max
import kotlin.math.min
import org.emerge.demo.physics.PhysicsAuthoritativeJoinController
import org.emerge.demo.physics.PhysicsDemoConfig
import org.emerge.demo.physics.TorusGlProgramFactory
import org.emerge.demo.physics.TorusViewComputer
import org.emerge.demo.physics.packBodiesToFloatArray
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
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
import org.lwjgl.opengl.GL33C.GL_FLOAT
import org.lwjgl.opengl.GL33C.GL_LINK_STATUS
import org.lwjgl.opengl.GL33C.GL_TRIANGLES
import org.lwjgl.opengl.GL33C.glClear
import org.lwjgl.opengl.GL33C.glClearColor
import org.lwjgl.opengl.GL33C.glDeleteProgram
import org.lwjgl.opengl.GL33C.glDeleteShader
import org.lwjgl.opengl.GL33C.glDrawArrays
import org.lwjgl.opengl.GL33C.glGetProgrami
import org.lwjgl.opengl.GL33C.glGetUniformLocation
import org.lwjgl.opengl.GL33C.glUseProgram
import org.lwjgl.opengl.GL33C.glUniform1f
import org.lwjgl.opengl.GL33C.glUniform1i
import org.lwjgl.opengl.GL33C.glUniform2f
import org.lwjgl.opengl.GL33C.glUniform4fv
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
    val cfg = PhysicsDemoConfig()
    val controller = PhysicsAuthoritativeJoinController(hostIp = hostIp, port = port, cfg = cfg)

    var sawFirstSnapshot: Boolean = false

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

    val program = TorusGlProgramFactory.createProgramGl330(MAX_BODIES)
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
    val view = TorusViewComputer()
    val bodiesFloats = FloatArray(4 * MAX_BODIES)
    val startedAt = System.currentTimeMillis()

    while (!glfwWindowShouldClose(window)) {
        glfwPollEvents()

        // zoom controls: '-' zoom out, '=' zoom in
        if (pressed[GLFW_KEY_MINUS]) zoom = max(0.05f, zoom * 0.98f)
        if (pressed[GLFW_KEY_EQUAL]) zoom = min(20f, zoom * 1.02f)

        // WASD input
        val ax = axis(pressed[GLFW_KEY_A], pressed[GLFW_KEY_D])
        val ay = axis(pressed[GLFW_KEY_W], pressed[GLFW_KEY_S])
        val frame = controller.tick(PhysicsInput(ax, ay))
        val state: PhysicsState? = frame.state
        val myId = frame.myId
        if (!sawFirstSnapshot && state != null && myId != null) {
            sawFirstSnapshot = true
            println("join-gl: first snapshot (playerId=$myId tick=${frame.tick})")
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

            if (state != null) {
                val params = view.compute(state = state, myId = myId, zoom = zoom)
                glUniform2f(uWorld, params.worldW, params.worldH)
                glUniform2f(uView, params.viewW, params.viewH)
                glUniform2f(uTopLeft, params.topLeftCoverX, params.topLeftCoverY)

                glUniform1i(uMyId, myId?.value ?: -1)

                val bodies = state.bodies.values.toList()
                val n = min(MAX_BODIES, bodies.size)
                glUniform1i(uBodyCount, n)

                val fb = st.mallocFloat(4 * MAX_BODIES)
                packBodiesToFloatArray(state = state, maxBodies = MAX_BODIES, out = bodiesFloats)
                fb.put(bodiesFloats, 0, 4 * MAX_BODIES)
                fb.flip()
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

// Shader compile/link is now provided by demo-physics via TorusGlProgramFactory.

