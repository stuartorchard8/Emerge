package org.emerge.desktop

import org.emerge.demo.physics.*
import org.emerge.render.torus.*
import org.emerge.render.torus.shader.WorldShader
import org.emerge.sim.core.physics.*
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL33C
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
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

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
        org.lwjgl.opengl.GL.createCapabilities()

        GL33C.glClearColor(0.07f, 0.07f, 0.07f, 1f)

        val worldShader = WorldShader(MAX_BODIES)

        // Fullscreen triangle (no VBO needed), but some drivers want a VAO bound in core profile.
        val vao = GL33C.glGenVertexArrays()
        GL33C.glBindVertexArray(vao)

        val vbo = GL33C.glGenBuffers()
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo)
        GL33C.glEnableVertexAttribArray(worldShader.aPos)
        // Capture the VBO binding into the VAO's attrib state.
        GL33C.glVertexAttribPointer(worldShader.aPos, 2, GL33C.GL_FLOAT, false, 2 * 4, 0L)
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0)


        var zoom = 0.75f // <1 => zoom out (see multiple tiles)
        val view = TorusViewComputer()

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
                GL33C.glViewport(0, 0, fbW, fbH)
                val aspectRatio = (fbW.toFloat() / fbH.toFloat())

                val worldViewportMinY = if (aspectRatio < 1f) -0.9f else -1f
                val worldViewportMaxY =  1f
                val worldViewportMinX = -1f
                val worldViewportMaxX =  if (aspectRatio < 1f) 1f else 0.9f
                val worldViewportCenterX =  (worldViewportMinX+worldViewportMaxX)/2f
                val worldViewportCenterY = (worldViewportMinY+worldViewportMaxY)/2f
                val verts = floatArrayOf(
                    worldViewportMinX, worldViewportMinY,
                    worldViewportMaxX, worldViewportMinY,
                    worldViewportMinX, worldViewportMaxY,
                    worldViewportMaxX, worldViewportMaxY,
                )
                val vertexFloatBuffer = st.mallocFloat(verts.size)
                vertexFloatBuffer.put(verts).flip()
                GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo)
                GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, vertexFloatBuffer, GL33C.GL_STATIC_DRAW)
                GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0)

                GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT)

                val params = view.compute(state = state, myId = myId, zoom = zoom)

                // uniforms
                worldShader.setResolution(fbW.toFloat(), fbH.toFloat())
                worldShader.setWorld(params.worldSizeX, params.worldSizeY)
                worldShader.setZoom(params.zoom)
                worldShader.setCenter(
                    params.viewFocusX-worldViewportCenterX*params.zoom*min(aspectRatio, 1f),
                    params.viewFocusY+worldViewportCenterY*params.zoom/max(aspectRatio, 1f),
                )
                worldShader.setMyId(myId?.value ?: -1)
                worldShader.setBodies(state.bodies.values.toList())

                worldShader.draw()
            }

            glfwSwapBuffers(window)
        }

        worldShader.deleteProgram()
        GL33C.glDeleteBuffers(vbo)
        GL33C.glDeleteVertexArrays(vao)
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}
