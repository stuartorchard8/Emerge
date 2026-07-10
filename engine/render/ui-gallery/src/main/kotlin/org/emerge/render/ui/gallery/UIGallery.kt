package org.emerge.render.ui.gallery
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.Ui
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.max

fun main() {
    UIGallery.run()
}

object UIGallery {
    /** Runs the gallery window on the calling thread until closed. */
    fun run() {
        if (!glfwInit()) error("GLFW init failed")
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)

        val window = glfwCreateWindow(1280, 860, "UI Gallery", NULL, NULL)
        if (window == NULL) error("Failed to create GLFW window")

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        org.lwjgl.opengl.GL.createCapabilities()

        val ui = Ui()

        val state = GalleryState()
        val mouse = object { var down = false; var x = 0f; var y = 0f }

        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            val (px, py) = cursorPixel(window)
            if (action == GLFW_PRESS) {
                mouse.down = true
                mouse.x = px
                mouse.y = py
                val hit = ui.hitTest(px, py)
                if (hit) return@glfwSetMouseButtonCallback
            } else {
                mouse.down = false
                ui.releaseHold()
            }
        }

        glfwSetCursorPosCallback(window) { _, xpos, ypos ->
            mouse.x = xpos.toFloat()
            mouse.y = ypos.toFloat()
        }

        var lastTime = glfwGetTime()
        var fps = 0f
        var frameCount = 0
        var lastFpsTime = lastTime

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateResolution(window, ui)

            val now = glfwGetTime()
            val delta = (now - lastTime).toFloat().coerceIn(0f, 0.25f)
            lastTime = now

            frameCount++
            if (now - lastFpsTime >= 1.0) {
                fps = frameCount.toFloat()
                frameCount = 0
                lastFpsTime = now
            }

            if (mouse.down) {
                ui.updateHold(mouse.x, mouse.y, delta)
            }

            buildGalleryFrame(ui, state, fps)

            // ── Render ──
            ui.draw()

            glfwSwapBuffers(window)
        }

        ui.cleanup()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    /** Builds one frame of the gallery widget tree — shared by the live window and the PNG snapshot. */
    fun buildGalleryFrame(ui: Ui, state: GalleryState, fps: Float) {
            ui.frame {
                // ── Title / Row / KeyValue demo ──
                panel(Anchor.TopLeft) {
                    title("TEXT WIDGETS", 0xFFFFFFFFL)
                    row("Standard gray row text")
                    row("This row uses medium gray (0xC8C8C8FF)", 0xC8C8C8FFL)
                    row("Colored row: cyan", 0x00AACCFFL)
                    row("Colored row: amber", 0xEEDD44FFL)
                    gap(8f)
                    keyValue("Label", "Value")
                    keyValue("Font Size", "18px row height")
                    keyValue("Left/Right", "key left-aligned, value right")
                    keyValue("Custom Key", "Custom Value", 0xAA66FFL, 0xFF8800L)
                }

                // ── Buttons ──
                panel(Anchor.TopLeft, newColumn = true) {
                    title("BUTTONS")
                    button("Click Me (0)", 0x3A6EA5FFL) { state.btn1++ }
                    row("Counter: ${state.btn1}")
                    button("Delete", 0xCC3333FFL) { state.btn2++ }
                    row("Delete count: ${state.btn2}")
                    button("Save", 0x2E8B40FFL) { state.btn3++ }
                    row("Save count: ${state.btn3}")
                    button("Special", 0x8A5BC0FFL) { state.btn4++ }
                    row("Special count: ${state.btn4}")
                    gap()
                    title("SPAN BUTTON")
                    button(listOf("SAVE" to null, " / " to 0xEEDD44FF, "DELETE" to 0xCC3333FF, " / " to 0x8A5BC0FF, "CANCEL" to null), 0x303848FFL) { state.spanBtn++ }
                    row("Span button clicks: ${state.spanBtn}")
                    gap()
                    actionRow(listOf(
                        Triple("Undo", 0x303848FFL) { state.undo++ },
                        Triple("Redo", 0x303848FFL) { state.redo++ },
                        Triple("Clear", 0xCC3333FFL) { state.clear++ },
                        Triple("Reset", 0x3A6EA5FFL) { state.reset++ },
                    ))
                    row("Undo: ${state.undo} | Redo: ${state.redo} | Clear: ${state.clear} | Reset: ${state.reset}")
                }

                // ── Picker/Dropdown ──
                panel(Anchor.TopLeft, newColumn = true) {
                    title("PICKER / DROPDOWN")
                    picker(
                        "Color",
                        state.pickerColor,
                        listOf("Red", "Green", "Blue", "Amber", "Purple", "Cyan"),
                        state.pickerColorOpen,
                        { state.pickerColorOpen = !state.pickerColorOpen },
                        { idx ->
                            state.pickerColor = listOf("Red", "Green", "Blue", "Amber", "Purple", "Cyan")[idx]
                            state.pickerColorOpen = false
                        },
                    )
                    gap()
                    picker(
                        "Preset",
                        state.pickerPreset,
                        listOf("Default", "Verbose", "Minimal", "Debug", "Production"),
                        state.pickerPresetOpen,
                        { state.pickerPresetOpen = !state.pickerPresetOpen },
                        { idx ->
                            state.pickerPreset = listOf("Default", "Verbose", "Minimal", "Debug", "Production")[idx]
                            state.pickerPresetOpen = false
                        },
                    )
                    gap()
                    row("Selected color: ${state.pickerColor}", 0x9A9A9AFFL)
                    row("Selected preset: ${state.pickerPreset}", 0x9A9A9AFFL)
                }

                // ── Stepper ──
                panel(Anchor.TopLeft, newColumn = true) {
                    title("STEPPER (hold to repeat)")
                    stepper("Scale", state.stepperScale.toString()) { delta ->
                        state.stepperScale = (state.stepperScale + delta).coerceIn(0, 10000)
                    }
                    gap()
                    stepper("Offset", state.stepperOffset.toString()) { delta ->
                        state.stepperOffset = (state.stepperOffset + delta).coerceIn(-5000, 5000)
                    }
                    gap()
                    stepper("Rate", state.stepperRate.toString()) { delta ->
                        state.stepperRate = (state.stepperRate + delta).coerceIn(0, 100000)
                    }
                    gap()
                    row("Hold +/- buttons to see accelerating repeat")
                    row("Values: S:${state.stepperScale} O:${state.stepperOffset} R:${state.stepperRate}")
                }

                // ── Gap demo ──
                panel(Anchor.TopLeft, newColumn = true) {
                    title("GAP / SPACING")
                    row("Small gap (default 6px):", 0x9A9A9AFFL)
                    gap(6f)
                    row("Medium gap (16px):", 0x9A9A9AFFL)
                    gap(16f)
                    row("Large gap (32px):", 0x9A9A9AFFL)
                    gap(32f)
                    row("Extra large gap (60px):", 0x9A9A9AFFL)
                    gap(60f)
                }

                // ── State panel (top right) ──
                panel(Anchor.TopRight) {
                    title("STATE PANEL", 0xEEDD44FFL)
                    keyValue("FPS", "${fps.toInt()}")
                    gap()
                    keyValue("btn1 clicks", state.btn1.toString())
                    keyValue("btn2 clicks", state.btn2.toString())
                    keyValue("btn3 clicks", state.btn3.toString())
                    keyValue("btn4 clicks", state.btn4.toString())
                    keyValue("spanBtn clicks", state.spanBtn.toString())
                    keyValue("undo", state.undo.toString())
                    keyValue("redo", state.redo.toString())
                    keyValue("clear", state.clear.toString())
                    keyValue("reset", state.reset.toString())
                    gap()
                    keyValue("color", state.pickerColor)
                    keyValue("preset", state.pickerPreset)
                    gap()
                    keyValue("scale", state.stepperScale.toString())
                    keyValue("offset", state.stepperOffset.toString())
                    keyValue("rate", state.stepperRate.toString())
                }

                // ── Multi-anchor layout demo ──
                panel(Anchor.BottomLeft) {
                    title("BOTTOM-LEFT PANEL", 0x2E8B40FFL)
                    row("Anchored BottomLeft")
                    row("Stacks above the edge")
                    keyValue("x", "0..1280")
                    keyValue("y", "0..860")
                }

                panel(Anchor.BottomRight) {
                    title("BOTTOM-RIGHT PANEL", 0xCC3333FFL)
                    row("Anchored BottomRight")
                    row("Another stack at same anchor")
                    gap()
                    row("This panel sits above the bottom-right corner")
                    gap()
                    button("I'm a button", 0x3A6EA5FFL) { state.brBtn++ }
                    row("BottomRight clicks: ${state.brBtn}")
                }
            }
    }

    private fun updateResolution(window: Long, ui: Ui) {
        MemoryStack.stackPush().use { st ->
            val sizeX = st.mallocInt(1)
            val sizeY = st.mallocInt(1)
            glfwGetFramebufferSize(window, sizeX, sizeY)
            ui.setResolution(max(1f, sizeX[0].toFloat()), max(1f, sizeY[0].toFloat()))
        }
    }

    private fun cursorPixel(window: Long): Pair<Float, Float> {
        val cursorX = DoubleArray(1)
        val cursorY = DoubleArray(1)
        glfwGetCursorPos(window, cursorX, cursorY)
        val windowW = IntArray(1)
        val windowH = IntArray(1)
        val framebufferW = IntArray(1)
        val framebufferH = IntArray(1)
        glfwGetWindowSize(window, windowW, windowH)
        glfwGetFramebufferSize(window, framebufferW, framebufferH)
        val w = windowW[0].coerceAtLeast(1)
        val h = windowH[0].coerceAtLeast(1)
        return Pair(
            cursorX[0].toFloat() * framebufferW[0].toFloat() / w.toFloat(),
            cursorY[0].toFloat() * framebufferH[0].toFloat() / h.toFloat(),
        )
    }
}

/** Mutable state holder tracking all widget values across frames. */
class GalleryState(
    var btn1: Int = 0,
    var btn2: Int = 0,
    var btn3: Int = 0,
    var btn4: Int = 0,
    var spanBtn: Int = 0,
    var undo: Int = 0,
    var redo: Int = 0,
    var clear: Int = 0,
    var reset: Int = 0,
    var brBtn: Int = 0,
    var pickerColor: String = "Red",
    var pickerColorOpen: Boolean = false,
    var pickerPreset: String = "Default",
    var pickerPresetOpen: Boolean = false,
    var stepperScale: Int = 100,
    var stepperOffset: Int = 0,
    var stepperRate: Int = 1000,
)
