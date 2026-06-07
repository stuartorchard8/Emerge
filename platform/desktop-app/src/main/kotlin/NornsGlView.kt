package org.emerge.desktop

import org.emerge.demo.norns.world.NornsConfig
import org.emerge.demo.norns.world.NornsView
import org.emerge.demo.norns.world.NornsWorld
import org.emerge.demo.norns.world.WorldCreature
import org.emerge.render.torus.GPU
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GLFW desktop host for the Norns GPU view: animated blobs in a side-scroll window, a text HUD,
 * camera following the eldest (or a clicked) creature.
 *   ESC quit · P pause · [ / ] slower/faster · LEFT-CLICK follow the creature under the cursor ·
 *   RIGHT-CLICK drop food.
 *
 * NOTE: unverified GL (no display in the authoring env). The world/animation/camera math are
 * unit-tested; run it and we iterate on pixels.
 */
object NornsGlView {
    private class State {
        var paused = false
        var stepHz = 10.0
        var fbW = 1000f
        var fbH = 620f
        var cameraCenterX = 0f
        var lockedFollowId: Int? = null // set by left-click; overrides the auto-follow
    }

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

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        GL.createCapabilities()

        val world = NornsWorld(NornsConfig(), seed)
        val renderer = NornsGlRenderer()
        val view = NornsView(world.cfg.worldWidth, world.cfg.floors)
        val state = State()

        glfwSetKeyCallback(window) { win, key, _, action, _ ->
            if (action == GLFW_PRESS) when (key) {
                GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win, true)
                GLFW_KEY_P -> state.paused = !state.paused
                GLFW_KEY_LEFT_BRACKET -> state.stepHz = max(2.0, state.stepHz / 1.5)
                GLFW_KEY_RIGHT_BRACKET -> state.stepHz *= 1.5
            }
        }
        glfwSetMouseButtonCallback(window) { win, button, action, _ ->
            if (action != GLFW_PRESS) return@glfwSetMouseButtonCallback
            val spot = cursorToWorld(win, view, state)
            when (button) {
                GLFW_MOUSE_BUTTON_LEFT -> state.lockedFollowId = world.creatureNear(spot.floor, spot.x, radius = 1.8f)?.id
                GLFW_MOUSE_BUTTON_RIGHT -> world.dropFood(spot.floor, spot.x.roundToInt())
            }
        }

        var accumulator = 0.0
        var last = glfwGetTime()
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateViewport(window, state)

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

            // a clicked creature takes priority; otherwise follow the eldest.
            val locked = state.lockedFollowId?.let { world.creatureById(it) }?.takeIf { it.alive }
            val follow: WorldCreature? = locked ?: world.creatures.maxByOrNull { it.biology.age }
            state.cameraCenterX = follow?.x ?: 0f

            GL11.glClearColor(0.07f, 0.08f, 0.12f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            renderer.draw(world, state.cameraCenterX, state.fbW, state.fbH, follow?.id)

            glfwSwapBuffers(window)
        }

        renderer.cleanup()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun updateViewport(window: Long, state: State) {
        MemoryStack.stackPush().use { st ->
            val w = st.mallocInt(1)
            val h = st.mallocInt(1)
            glfwGetFramebufferSize(window, w, h)
            state.fbW = max(1, w[0]).toFloat()
            state.fbH = max(1, h[0]).toFloat()
            GPU.setViewport(0, 0, w[0], h[0])
        }
    }

    /** Current cursor position → world spot, accounting for HiDPI (window vs framebuffer pixels). */
    private fun cursorToWorld(window: Long, view: NornsView, state: State) =
        MemoryStack.stackPush().use { st ->
            val cx = st.mallocDouble(1); val cy = st.mallocDouble(1)
            glfwGetCursorPos(window, cx, cy)
            val ww = st.mallocInt(1); val wh = st.mallocInt(1)
            glfwGetWindowSize(window, ww, wh)
            val px = (cx[0] * state.fbW / max(1, ww[0])).toFloat()
            val py = (cy[0] * state.fbH / max(1, wh[0])).toFloat()
            view.screenToWorld(px, py, state.fbW, state.fbH, state.cameraCenterX, state.fbW / state.fbH)
        }
}

/** Entry point for the `runNornsGl` task. Optional arg: world seed. */
fun main(args: Array<String>) {
    NornsGlView.run(seed = args.getOrNull(0)?.toLongOrNull() ?: 1L)
}
