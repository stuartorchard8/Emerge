package org.emerge.desktop

import org.emerge.demo.physics.*
import org.emerge.render.torus.*
import org.emerge.sim.core.physics.*
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.*
import kotlin.use

object GlSceneView {
    fun start(settings: LaunchSettings) {
        when (settings.mode) {
            LaunchMode.LOCAL -> Thread { runLocalGl(settings.port) }.start()
            LaunchMode.HOST -> Thread { runHostGl(settings.port) }.start()
            LaunchMode.JOIN -> Thread { runJoinGl(hostIp = settings.hostIp, port = settings.port) }.start()
        }
    }

    private fun runLocalGl(port: Int) {
        val controller = PhysicsAuthoritativeHostController(port = port, cfg = PhysicsConfig(), acceptRemoteClients = false)
        runGl("Emerge local-gl", controller)
    }

    private fun runHostGl(port: Int) {
        val controller = PhysicsAuthoritativeHostController(port = port, cfg = PhysicsConfig(), acceptRemoteClients = true)
        runGl("Emerge host-gl (:$port)", controller)
    }

    private fun runJoinGl(hostIp: String, port: Int) {
        val controller = PhysicsAuthoritativeJoinController(hostIp = hostIp, port = port)
        runGl("Emerge join-gl ($hostIp:$port)", controller)
    }

    private fun runGl(title: String, controller: PhysicsAuthoritativeController) {
        if (!glfwInit()) error("GLFW init failed")
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)

        val window = glfwCreateWindow(960, 600, title, NULL, NULL)
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
        val uCenter = glGetUniformLocation(program, "uCenter")
        val uBodyCount = glGetUniformLocation(program, "uBodyCount")
        val uMyId = glGetUniformLocation(program, "uMyId")
        val uBodies = glGetUniformLocation(program, "uBodies")

        var zoom = 0.75f // <1 => zoom out (see multiple tiles)
        val view = TorusViewComputer()
        val bodiesFloats = FloatArray(4 * MAX_BODIES)

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()

            // zoom controls: '-' zoom out, '=' zoom in
            if (pressed[GLFW_KEY_MINUS]) zoom = max(0.05f, zoom * 0.98f)
            if (pressed[GLFW_KEY_EQUAL]) zoom = min(20f, zoom * 1.02f)

            // WASD input
            val ax = axis(pressed[GLFW_KEY_A], pressed[GLFW_KEY_D])
            val ay = axis(pressed[GLFW_KEY_W], pressed[GLFW_KEY_S])
            val frame = controller.tick(PhysicsInput(ax, ay))
            val state: PhysicsState = frame.state
            val myId = frame.myId

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

                val params = view.compute(state = state, myId = myId, zoom = zoom)
                glUniform2f(uWorld, params.worldW, params.worldH)
                glUniform2f(uView, params.viewW, params.viewH)
                glUniform2f(uCenter, params.topLeftCoverX, params.topLeftCoverY)

                glUniform1i(uMyId, myId?.value ?: -1)
                val bodies = state.bodies.values.toList()
                val n = min(MAX_BODIES, bodies.size)
                glUniform1i(uBodyCount, n)

                val fb = st.mallocFloat(4 * MAX_BODIES)
                packBodiesToFloatArray(state = state, maxBodies = MAX_BODIES, out = bodiesFloats)
                fb.put(bodiesFloats, 0, 4 * MAX_BODIES)
                fb.flip()
                glUniform4fv(uBodies, fb)

                glDrawArrays(GL_TRIANGLES, 0, 3)
            }

            glfwSwapBuffers(window)
        }

        glDeleteProgram(program)
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}