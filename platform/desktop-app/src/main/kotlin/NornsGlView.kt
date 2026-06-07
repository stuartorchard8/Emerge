package org.emerge.desktop

import org.emerge.demo.norns.world.NornsConfig
import org.emerge.demo.norns.world.NornsWorld
import org.emerge.demo.norns.world.WorldCreature
import org.emerge.render.torus.GPU
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.max

/**
 * GLFW desktop host for the Norns GPU view: a window showing the side-scroll colony as animated
 * blobs, camera auto-following the eldest creature. ESC quits, P pauses, [ / ] slow/speed.
 *
 * NOTE: not runnable in the authoring environment (no display), so this GL host is unverified by
 * me — the world + animation it drives are unit-tested. Run it and we iterate on any GL issues.
 */
object NornsGlView {
    private class State { var paused = false; var stepHz = 20.0 }

    fun run(seed: Long = 1L) {
        if (!glfwInit()) error("GLFW init failed")
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

        val window = glfwCreateWindow(1000, 620, "Norns", NULL, NULL)
        if (window == NULL) error("Failed to create GLFW window")

        val state = State()
        glfwSetKeyCallback(window) { win, key, _, action, _ ->
            if (action == GLFW_PRESS) when (key) {
                GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win, true)
                GLFW_KEY_P -> state.paused = !state.paused
                GLFW_KEY_LEFT_BRACKET -> state.stepHz = max(2.0, state.stepHz / 1.5)
                GLFW_KEY_RIGHT_BRACKET -> state.stepHz *= 1.5
            }
        }

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        GL.createCapabilities()

        val world = NornsWorld(NornsConfig(), seed)
        val renderer = NornsGlRenderer()
        val viewWidth = NornsRenderConfig().viewWidth

        var accumulator = 0.0
        var last = glfwGetTime()

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateViewport(window)

            val now = glfwGetTime()
            accumulator += now - last
            last = now
            val stepInterval = 1.0 / state.stepHz
            if (!state.paused) {
                var steps = 0
                while (accumulator >= stepInterval && steps < 8) { world.step(); accumulator -= stepInterval; steps++ }
            } else {
                accumulator = 0.0
            }

            val follow: WorldCreature? = world.creatures.maxByOrNull { it.biology.age }
            val cameraX = (follow?.x?.toFloat() ?: 0f) - viewWidth / 2f

            GL11.glClearColor(0.07f, 0.08f, 0.12f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            renderer.draw(world, cameraX, follow?.id)

            glfwSwapBuffers(window)
        }

        renderer.cleanup()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun updateViewport(window: Long) {
        MemoryStack.stackPush().use { st ->
            val w = st.mallocInt(1)
            val h = st.mallocInt(1)
            glfwGetFramebufferSize(window, w, h)
            GPU.setViewport(0, 0, max(1, w[0]), max(1, h[0]))
        }
    }
}

/** Entry point for the `runNornsGl` task. Optional arg: world seed. */
fun main(args: Array<String>) {
    NornsGlView.run(seed = args.getOrNull(0)?.toLongOrNull() ?: 1L)
}
