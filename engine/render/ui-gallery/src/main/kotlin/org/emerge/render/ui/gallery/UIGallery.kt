package org.emerge.render.ui.gallery

import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.PanelBuilder
import org.emerge.render.torus.ui.Ui
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.max

fun main() {
    UIGallery.run()
}

/** Initial window size, shared with the PNG snapshot so both show the same layout. */
const val GALLERY_WIDTH = 1800
const val GALLERY_HEIGHT = 900

/** Scroll-offset key for the scroll-area demo (the widget tree is rebuilt each frame, so scroll state
 *  lives in [Ui] under this id). */
const val SCROLL_DEMO_ID = "gallery-scroll"

/**
 * Interactive showcase for the shared immediate-mode UI toolkit: every widget kind, one panel each.
 * [buildGalleryFrame] holds the widget tree (also rendered headlessly by [UIGallerySnapshot]);
 * [run] is the live GLFW host around it.
 */
object UIGallery {

    /** Builds one frame of the gallery widget tree — shared by the live window and the PNG snapshot. */
    fun buildGalleryFrame(ui: Ui, state: GalleryState, fps: Float) {
        ui.frame {
            // Left side: three columns of widget demos (stacked pairs so they fit beside the state panel).
            panel(Anchor.TopLeft) { textWidgets() }
            panel(Anchor.TopLeft) { gapDemo() }
            panel(Anchor.TopLeft, newColumn = true) { buttons(state) }
            panel(Anchor.TopLeft, newColumn = true) { pickers(state) }
            panel(Anchor.TopLeft) { steppers(state) }
            panel(Anchor.TopLeft, newColumn = true) { chipsAndSegments(state) }
            panel(Anchor.TopLeft) { listRows(state) }
            // Right side: everything the widgets mutate, in one place.
            panel(Anchor.TopRight) { statePanel(state, fps) }
            // A scrolling, clipped viewport — content taller than its box. Drag inside it or wheel over it;
            // rows clipped out of view are neither drawn nor clickable.
            scrollArea(
                SCROLL_DEMO_ID,
                x = GALLERY_WIDTH - 380f, y = 415f, w = 360f, h = 345f,
                background = 0x10182CF0L,
            ) {
                title("SCROLL AREA (drag / wheel)")
                row("40 rows in a 345px viewport", 0x9A9A9AFFL)
                gap(6f)
                for (i in 1..40) {
                    button("Row $i" + if (state.scrollPick == i) "  <" else "", if (state.scrollPick == i) 0x3A6EA5FFL else 0x2A3550FFL) {
                        state.scrollPick = i
                    }
                }
            }
            // Bottom corners: anchor/stacking demo.
            panel(Anchor.BottomLeft) { bottomLeft() }
            panel(Anchor.BottomRight) { bottomRight(state) }
        }
    }

    private fun PanelBuilder.textWidgets() {
        title("TEXT WIDGETS")
        row("Standard gray row text")
        row("This row uses medium gray (0xC8C8C8FF)", 0xC8C8C8FFL)
        row("Colored row: cyan", 0x00AACCFFL)
        row("Colored row: amber", 0xEEDD44FFL)
        gap(8f)
        keyValue("Label", "Value")
        keyValue("Font Size", "18px row height")
        keyValue("Left/Right", "key left-aligned, value right")
        keyValue("Custom Key", "Custom Value", 0xAA66FFFFL, 0xFF8800FFL)
    }

    private fun PanelBuilder.buttons(state: GalleryState) {
        title("BUTTONS")
        button("Click Me (${state.clickMe})", 0x3A6EA5FFL) { state.clickMe++ }
        button("Delete", 0xCC3333FFL) { state.delete++ }
        button("Save", 0x2E8B40FFL) { state.save++ }
        button("Special", 0x8A5BC0FFL) { state.special++ }
        gap()
        title("SPAN BUTTON")
        button(
            listOf(
                "SAVE" to null,
                " / " to 0xEEDD44FFL,
                "DELETE" to 0xCC3333FFL,
                " / " to 0x8A5BC0FFL,
                "CANCEL" to null,
            ),
            0x303848FFL,
        ) { state.spanBtn++ }
        gap()
        title("ACTION ROW")
        actionRow(listOf(
            Triple("Undo", 0x303848FFL) { state.undo++ },
            Triple("Redo", 0x303848FFL) { state.redo++ },
            Triple("Clear", 0xCC3333FFL) { state.clear++ },
            Triple("Reset", 0x3A6EA5FFL) { state.reset++ },
        ))
    }

    private fun PanelBuilder.pickers(state: GalleryState) {
        title("PICKER / DROPDOWN")
        picker("Color", state.color)
        gap()
        picker("Preset", state.preset)
    }

    /** Wires a [PickerState] to the toolkit's picker widget. */
    private fun PanelBuilder.picker(label: String, ps: PickerState) {
        picker(
            label, ps.value, ps.options, ps.open,
            onToggle = { ps.open = !ps.open },
            onPick = { idx ->
                ps.value = ps.options[idx]
                ps.open = false
            },
        )
    }

    private fun PanelBuilder.steppers(state: GalleryState) {
        title("STEPPER (hold to repeat)")
        stepper("Scale", state.scale.toString()) { d -> state.scale = (state.scale + d).coerceIn(0, 10_000) }
        gap()
        stepper("Offset", state.offset.toString()) { d -> state.offset = (state.offset + d).coerceIn(-5_000, 5_000) }
        gap()
        stepper("Rate", state.rate.toString()) { d -> state.rate = (state.rate + d).coerceIn(0, 100_000) }
        gap()
        row("Hold +/- to see accelerating repeat")
    }

    /** Chips + segmented controls — the progressive-disclosure widgets: a chip shows a value and opens its
     *  editor; a segmented control picks between 2-3 options with no drill-down at all. */
    private fun PanelBuilder.chipsAndSegments(state: GalleryState) {
        title("CHIPS")
        chip("", "DIVIDE (MITOSIS)", 0x35507AFFL) { state.chipTaps++ }
        chip("MORPHOGEN", state.morphogen) { state.chipTaps++ }
        chip("GROUP", "DIVISION") { state.chipTaps++ }
        gap()
        title("SEGMENTED")
        segmented("CMP", listOf(">", "<"), state.cmp) { state.cmp = it }
        segmented("ORIENT", listOf("ALONG", "ACROSS"), state.orient) { state.orient = it }
        // Deliberately long: segments size to their widest label rather than clipping it.
        segmented("KEEP", listOf("MOTHER", "DAUGHTER"), state.keep) { state.keep = it }
    }

    /** List rows — a picker sheet's options, each able to explain itself. */
    private fun PanelBuilder.listRows(state: GalleryState) {
        title("LIST ROWS (picker sheet)")
        listRow("MITOSIS", "Divide into two cells", state.action == 0) { state.action = 0 }
        listRow("LYSE", "Tear biomass from a neighbour", state.action == 1) { state.action = 1 }
        listRow("CONVERT", "Lock cytoplasm into biomass", state.action == 2) { state.action = 2 }
        listRow("Plain row, no description", selected = state.action == 3) { state.action = 3 }
    }

    private fun PanelBuilder.gapDemo() {
        title("GAP / SPACING")
        row("Small gap (6px) below:", 0x9A9A9AFFL)
        gap(6f)
        row("Medium gap (16px) below:", 0x9A9A9AFFL)
        gap(16f)
        row("Large gap (32px) below:", 0x9A9A9AFFL)
        gap(32f)
        row("(end)", 0x9A9A9AFFL)
    }

    private fun PanelBuilder.statePanel(state: GalleryState, fps: Float) {
        title("STATE PANEL", 0xEEDD44FFL)
        keyValue("FPS", "${fps.toInt()}")
        gap()
        keyValue("Click Me", state.clickMe.toString())
        keyValue("Delete", state.delete.toString())
        keyValue("Save", state.save.toString())
        keyValue("Special", state.special.toString())
        keyValue("span button", state.spanBtn.toString())
        keyValue("undo / redo", "${state.undo} / ${state.redo}")
        keyValue("clear / reset", "${state.clear} / ${state.reset}")
        gap()
        keyValue("color", state.color.value)
        keyValue("preset", state.preset.value)
        gap()
        keyValue("scale", state.scale.toString())
        keyValue("offset", state.offset.toString())
        keyValue("rate", state.rate.toString())
        gap()
        keyValue("bottom-right", state.brBtn.toString())
        gap()
        keyValue("chip taps", state.chipTaps.toString())
        keyValue("cmp / orient / keep", "${state.cmp} / ${state.orient} / ${state.keep}")
        keyValue("action row", state.action.toString())
        keyValue("scroll pick", state.scrollPick.toString())
    }

    private fun PanelBuilder.bottomLeft() {
        title("BOTTOM-LEFT PANEL", 0x2E8B40FFL)
        row("Anchored BottomLeft")
        keyValue("window", "${GALLERY_WIDTH}x$GALLERY_HEIGHT")
    }

    private fun PanelBuilder.bottomRight(state: GalleryState) {
        title("BOTTOM-RIGHT PANEL", 0xCC3333FFL)
        row("Anchored BottomRight")
        button("I'm a button", 0x3A6EA5FFL) { state.brBtn++ }
    }

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

        val window = glfwCreateWindow(GALLERY_WIDTH, GALLERY_HEIGHT, "UI Gallery", NULL, NULL)
        if (window == NULL) error("Failed to create GLFW window")

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        org.lwjgl.opengl.GL.createCapabilities()

        val ui = Ui()
        val state = GalleryState()
        var mouseDown = false

        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            if (action == GLFW_PRESS) {
                mouseDown = true
                val (px, py) = cursorPixel(window)
                ui.hitTestDown(px, py)
            } else {
                mouseDown = false
                val (px, py) = cursorPixel(window)
                ui.hitTestUp(px, py)
                ui.releaseHold()
            }
        }

        // Drag inside a scroll area scrolls it (and cancels the pending click).
        glfwSetCursorPosCallback(window) { _, _, _ ->
            if (!mouseDown) return@glfwSetCursorPosCallback
            val (px, py) = cursorPixel(window)
            ui.dragTo(px, py)
        }

        // Wheel scrolls whichever area is under the cursor.
        glfwSetScrollCallback(window) { _, _, yoffset ->
            val (px, py) = cursorPixel(window)
            ui.scrollAreaAt(px, py)?.let { ui.scrollBy(it, (-yoffset * 40.0).toFloat()) }
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

            if (mouseDown) {
                val (px, py) = cursorPixel(window)
                ui.updateHold(px, py, delta)
            }

            buildGalleryFrame(ui, state, fps)

            glClearColor(0.07f, 0.07f, 0.09f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            ui.draw()

            glfwSwapBuffers(window)
        }

        ui.cleanup()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun updateResolution(window: Long, ui: Ui) {
        MemoryStack.stackPush().use { st ->
            val sizeX = st.mallocInt(1)
            val sizeY = st.mallocInt(1)
            glfwGetFramebufferSize(window, sizeX, sizeY)
            glViewport(0, 0, max(1, sizeX[0]), max(1, sizeY[0]))
            ui.setResolution(max(1f, sizeX[0].toFloat()), max(1f, sizeY[0].toFloat()))
        }
    }

    /** Cursor position in framebuffer pixels (the toolkit's coordinate space), scaled for HiDPI. */
    private fun cursorPixel(window: Long): Pair<Float, Float> {
        MemoryStack.stackPush().use { st ->
            val cx = st.mallocDouble(1)
            val cy = st.mallocDouble(1)
            glfwGetCursorPos(window, cx, cy)
            val winW = st.mallocInt(1)
            val winH = st.mallocInt(1)
            glfwGetWindowSize(window, winW, winH)
            val fbW = st.mallocInt(1)
            val fbH = st.mallocInt(1)
            glfwGetFramebufferSize(window, fbW, fbH)
            val w = max(1, winW[0])
            val h = max(1, winH[0])
            return Pair(
                cx[0].toFloat() * fbW[0] / w,
                cy[0].toFloat() * fbH[0] / h,
            )
        }
    }
}

/** A dropdown's choices plus its mutable selection/open state. */
class PickerState(val options: List<String>, var value: String = options.first(), var open: Boolean = false)

/** Mutable state the gallery widgets read and write across frames. */
class GalleryState {
    // Button click counters.
    var clickMe = 0
    var delete = 0
    var save = 0
    var special = 0
    var spanBtn = 0
    var undo = 0
    var redo = 0
    var clear = 0
    var reset = 0
    var brBtn = 0

    val color = PickerState(listOf("Red", "Green", "Blue", "Amber", "Purple", "Cyan"))
    val preset = PickerState(listOf("Default", "Verbose", "Minimal", "Debug", "Production"))

    var scale = 100
    var offset = 0
    var rate = 1000

    /** Which row of the scroll-area demo was last clicked (proves clipped rows aren't clickable). */
    var scrollPick = 0

    // Chips / segmented / list rows.
    var chipTaps = 0
    var morphogen = "(NONE)"
    var cmp = 0
    var orient = 0
    var keep = 1
    var action = 0
}
