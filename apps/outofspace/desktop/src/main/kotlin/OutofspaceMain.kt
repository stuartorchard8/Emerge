package org.emerge.desktop

import org.emerge.demo.outofspace.FrameShift
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.OutofspaceHud
import org.emerge.demo.outofspace.OutofspaceRenderer
import org.emerge.demo.outofspace.audio.ImpactAudioSystem
import org.emerge.demo.outofspace.DeleteLayer
import org.emerge.demo.outofspace.Tool
import org.emerge.demo.outofspace.Mode
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.Brush
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.render.torus.ui.Ui
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.max
import kotlin.math.pow

/**
 * Desktop host: a GLFW window, an input loop, and a call to the shared game every frame.
 *
 * The host owns a GL context, real time and platform input, and knows no game rules whatsoever —
 * all of those live in `:apps:outofspace:core`, which is why the Android and web hosts beside it are
 * the same loop in a different dialect.
 *
 * Pointer routing is UI first, then the world. Left-drag paints a run of machines, which makes
 * laying a belt line feel like drawing rather than clicking; middle-drag pans.
 */
fun main() {
    if (!glfwInit()) error("GLFW init failed")
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)

    val window = glfwCreateWindow(1440, 900, "Out of Space", NULL, NULL)
    if (window == NULL) error("Failed to create GLFW window")
    glfwMakeContextCurrent(window)
    glfwSwapInterval(1)
    glfwShowWindow(window)
    org.lwjgl.opengl.GL.createCapabilities()

    // Everything GL must be constructed after the context is current.
    val profiler = PipelineProfiler()
    val controller = OutofspaceController().also { it.profiler = profiler }
    val renderer = OutofspaceRenderer()
    val hud = OutofspaceHud()
    val ui = Ui()
    renderer.centreOn(controller.state)
    // Auto-resume: load the most recent save if one exists.
    OoSaves.mostRecent()?.let {
        val text = OoSaves.load(it)
        if (text != null) {
            val state = Save.read(text)
            controller.reset(state)
            renderer.centreOn(state)
            println("resumed from: $it")
        }
    }
    // Sound is a host's business and the loop's last passenger: it reads the state the tick just
    // produced and never writes to it, so a machine with no sound device simply skips these lines.
    val impactAudio = ImpactAudioSystem(DesktopImpactAudioEngine())

    hud.onTogglePause = { controller.paused = !controller.paused }
    hud.onReset = { controller.reset(); renderer.centreOn(controller.state) }
    hud.onFit = { controller.fit() }
    hud.canSave = true
    hud.onSave = {
        val mostRecent = OoSaves.mostRecent()
        val defaultName = mostRecent ?: "world @ ${controller.tick}"
        val openSave = if (mostRecent != null) "Save over '$mostRecent'" else "Save"
        val openLoad = "Load"
        hud.openSaveLoadDialog(
            onSave = { name ->
                val sanitized = OoSaves.save(name, Save.write(controller.state))
                hud.saveStatus = "saved: $sanitized"
                hud.closeSaveLoadDialog()
            },
            onLoad = { name ->
                val text = OoSaves.load(name)
                if (text != null) {
                    val state = Save.read(text)
                    controller.reset(state)
                    renderer.centreOn(state)
                    hud.saveStatus = "loaded: $name"
                } else {
                    hud.saveStatus = "load failed: $name not found"
                }
                hud.closeSaveLoadDialog()
            },
            onDelete = { name ->
                OoSaves.delete(name)
                hud.saveStatus = "deleted: $name"
                hud.closeSaveLoadDialog()
            },
            saves = OoSaves.list(),
            saveMode = true,
            defaultName = defaultName,
        )
    }
    hud.onLoad = {
        val defaultName = OoSaves.mostRecent() ?: "world @ ${controller.tick}"
        hud.openSaveLoadDialog(
            onSave = { name ->
                val sanitized = OoSaves.save(name, Save.write(controller.state))
                hud.saveStatus = "saved: $sanitized"
                hud.closeSaveLoadDialog()
            },
            onLoad = { name ->
                val text = OoSaves.load(name)
                if (text != null) {
                    val state = Save.read(text)
                    controller.reset(state)
                    renderer.centreOn(state)
                    hud.saveStatus = "loaded: $name"
                } else {
                    hud.saveStatus = "load failed: $name not found"
                }
                hud.closeSaveLoadDialog()
            },
            onDelete = { name ->
                OoSaves.delete(name)
                hud.saveStatus = "deleted: $name"
                hud.closeSaveLoadDialog()
            },
            saves = OoSaves.list(),
            saveMode = false,
            defaultName = defaultName,
        )
    }

    var leftDown = false
    var middleDown = false
    var rightDown = false
    // Held, not evented: a key repeat is a stutter and a key event is a step, and panning is neither.
    val panKeys = BooleanArray(4)   // W, A, S, D
    var uiConsumed = false
    var lastX = 0f
    var lastY = 0f
    var hovered = TileIndex.NONE
    // So a drag paints each tile once rather than re-issuing the same edit every frame.
    var lastPainted = TileIndex.NONE
    // `hovered` is recomputed from the pointer every time it moves, so it heals itself; `lastPainted`
    // persists across frames and would dedupe against a tile that has moved. Dropped when the grid
    // grows — one repainted tile is harmless, a missed one is not.
    val frame = FrameShift(controller.state)

    glfwSetMouseButtonCallback(window) { _, button, action, _ ->
        val (px, py) = cursorPixel(window)
        when (button) {
            GLFW_MOUSE_BUTTON_LEFT -> if (action == GLFW_PRESS) {
                leftDown = true
                lastX = px; lastY = py
                uiConsumed = ui.hitTestDown(px, py)
                if (!uiConsumed) {
                    val tile = renderer.tileIndexAt(px, py, controller.state)
                    if (tile != TileIndex.NONE) {
                        controller.apply(tile)
                        lastPainted = tile
                        // The bellows is a hold, so pressing merely opens the valve — the
                        // controller emits one edit a tick for as long as this stays set.
                        if (controller.tool == Tool.Inject || controller.tool == Tool.InjectWater) controller.injectTile = tile
                    }
                }
            } else {
                leftDown = false
                lastPainted = TileIndex.NONE
                controller.endDrag()
                controller.injectTile = TileIndex.NONE
                if (uiConsumed) { ui.hitTestUp(px, py); ui.releaseHold() }
                uiConsumed = false
            }

            // Panning, which is what every other game does with this button. Deleting used to live
            // here and now has a tool of its own — see [Tool].
            GLFW_MOUSE_BUTTON_RIGHT -> {
                rightDown = action == GLFW_PRESS
                lastX = px; lastY = py
            }

            GLFW_MOUSE_BUTTON_MIDDLE -> {
                middleDown = action == GLFW_PRESS
                lastX = px; lastY = py
            }
        }
    }

    glfwSetCursorPosCallback(window) { _, _, _ ->
        val (px, py) = cursorPixel(window)
        ui.hover(px, py)
        hovered = renderer.tileIndexAt(px, py, controller.state)
        val dx = px - lastX
        val dy = py - lastY
        when {
            middleDown || rightDown -> renderer.panByPixels(dx, dy)
            leftDown && uiConsumed -> ui.dragTo(px, py)
            // A held bellows follows the pointer, so gas is laid along the drag.
            leftDown && (controller.tool == Tool.Inject || controller.tool == Tool.InjectWater) -> controller.injectTile = hovered
            // Deleting drags like building does — a run of track comes up in one gesture.
            leftDown && controller.tool == Tool.Delete -> if (hovered != TileIndex.NONE && hovered != lastPainted) {
                controller.removeAt(hovered)
                lastPainted = hovered
            }
            // Painting a run of machines is a Build-tool gesture; a wire drag would just thrash
            // the selection, so dragging does nothing while wiring.
            // Painting a run of machines is a Build-tool gesture. For conduit it is more than that:
            // the drag is what *connects* the tiles, since track no longer joins by touching, so the
            // gesture is handed to the controller whole rather than replayed as isolated placements.
            // Cutting drags exactly as building does, and through the controller for the same
            // reason: the gesture has to be stepped out tile by tile, or a fast stroke leaves an
            // uncut tile behind and the belt is still joined where it looks severed.
            // Cancelling drags through the controller for the third time and the same reason: the
            // stroke is stepped out tile by tile, so a fast sweep back over a condemned run leaves
            // no tile still marked in the middle of it.
            leftDown && (controller.tool == Tool.Cut || controller.tool == Tool.Cancel) ->
                if (hovered != TileIndex.NONE && hovered != lastPainted) {
                    controller.dragTo(hovered)
                    lastPainted = hovered
                }
            leftDown && controller.tool == Tool.Build -> if (hovered != TileIndex.NONE && hovered != lastPainted) {
                if (controller.brush is Brush.Run) controller.dragTo(hovered) else controller.place(hovered)
                lastPainted = hovered
            }
        }
        lastX = px; lastY = py
    }

    glfwSetScrollCallback(window) { _, _, yoffset ->
        val (px, py) = cursorPixel(window)
        // The wheel scrolls a UI list when it is over one, and zooms the world when it is not. The
        // reference panel is longer than the screen for anything interesting, so without this the
        // one gesture a player would try over it would fly the camera instead.
        val area = ui.scrollAreaAt(px, py)
        if (area != null) {
            ui.scrollBy(area, (-yoffset * WHEEL_SCROLL_PX).toFloat())
            return@glfwSetScrollCallback
        }
        renderer.zoomAtScreen(px, py, 1.12f.pow(yoffset.toFloat().coerceIn(-24f, 24f)))
    }

    glfwSetKeyCallback(window) { _, key, _, action, _ ->
        // WASD pans the camera. Held rather than evented, for the reason [panKeys] gives, so it is
        // read before the early return that everything else here sits behind.
        //
        // ⚠️ This is what took `W` off the tool toggle, which now lives on `Q`. The old comment here
        // said stealing `W` would break a gesture that is in every screenshot — it does, and it is
        // still the right trade: WASD is what a hand rests on, and the toggle is one key either way.
        // The debug engine keeps the arrows.
        // ── Flight mode ──────────────────────────────────────────────────────
        //
        // Taken before everything else, because in flight the pilot's keys belong to the vessel and
        // nothing else may claim them. Only the mode toggle and ESC are held back, so there is always
        // a way out.
        // ⚠️ `T` joins `F` and `ESC` in being held back from the pilot: the autopilot is a control
        // you reach for *while flying*, and a stick that swallowed it would make it unreachable in
        // the only mode it does anything in.
        if (controller.mode == Mode.Flight && key != GLFW_KEY_F && key != GLFW_KEY_T && key != GLFW_KEY_ESCAPE) {
            val bound = when (key) {
                GLFW_KEY_UP, GLFW_KEY_W -> InputKey.Up
                GLFW_KEY_DOWN, GLFW_KEY_S -> InputKey.Down
                GLFW_KEY_LEFT, GLFW_KEY_A -> InputKey.Left
                GLFW_KEY_RIGHT, GLFW_KEY_D -> InputKey.Right
                GLFW_KEY_Q -> InputKey.Q
                GLFW_KEY_E -> InputKey.E
                GLFW_KEY_Z -> InputKey.Z
                GLFW_KEY_X -> InputKey.X
                else -> null
            }
            if (bound != null) {
                // Held, not evented: a button is down for as long as the finger is, and the sim
                // reads that level every tick. See [OutofspaceInput.heldKeys].
                if (action == GLFW_PRESS) controller.heldKeys = controller.heldKeys or bound.bit
                else if (action == GLFW_RELEASE) controller.heldKeys = controller.heldKeys and bound.bit.inv()
            }
            return@glfwSetKeyCallback
        }

        val pan = when (key) {
            GLFW_KEY_W -> 0
            GLFW_KEY_A -> 1
            GLFW_KEY_S -> 2
            GLFW_KEY_D -> 3
            else -> -1
        }
        if (pan >= 0) {
            if (action == GLFW_PRESS) panKeys[pan] = true
            else if (action == GLFW_RELEASE) panKeys[pan] = false
            return@glfwSetKeyCallback
        }

        if (action != GLFW_PRESS) return@glfwSetKeyCallback
        when (key) {
            GLFW_KEY_SPACE -> controller.paused = !controller.paused
            // Build or fly. The keyboard cannot do both — see [Mode].
            GLFW_KEY_F -> {
                controller.mode = controller.mode.next
                // Whatever was being panned or aimed is not being panned or aimed any more.
                panKeys.fill(false)
            }
            // Stability augmentation, on the key every space game puts it on.
            GLFW_KEY_T -> controller.toggleSas()
            GLFW_KEY_R -> controller.rotateBrush()
            GLFW_KEY_H -> controller.overlay = controller.overlay.next

            // ── A key per tool, and the same key aims it ──────────────────────
            //
            // ⛔ **This replaced `Q` stepping through every tool and `E` stepping through whichever
            // sub-target the current one had.** That was one key to learn and a lottery to use:
            // reaching CUT from BUILD was four presses, and the count moved every time a tool was
            // added. Each of these opens its tool, and a second press aims it — see
            // [OutofspaceController.reachFor], which is also where "the opening press does not
            // advance the aim" is argued.
            //
            // ⚠️ **INSPECT deliberately has no key.** ESC reaches it from anywhere, one rung at a
            // time, and that is the way back the whole editor is built around — a second way in
            // would be a second thing to learn for a tool you arrive at by putting others down.
            GLFW_KEY_B -> controller.reachFor(Tool.Build)
            GLFW_KEY_X -> controller.reachFor(Tool.Delete)
            GLFW_KEY_Z -> controller.reachFor(Tool.Cancel)
            GLFW_KEY_Q -> controller.reachFor(Tool.Cut)
            // The material is not a tool's property — it is a standing choice that outlives every
            // one of them — so it gets a key of its own rather than a rung in somebody's cycle.
            GLFW_KEY_E -> controller.cycleMaterial(1)
            // A debug drop, alongside the debug engine, until capture is a thing you fly at in H4.
            GLFW_KEY_F6 -> { val (ix,iy) = renderer.screenToTile(lastX, lastY); controller.dropRock(ix, iy) }
            // ⚠️ **On an F-key with the other debug bindings, and it had to go somewhere.** The
            // bellows and the tap used to be reachable only by cycling `Q` past every real tool;
            // that cycle is gone, and a debug tool nobody can reach from the keyboard is a debug
            // tool that stops being used. Same open-then-cycle rule as the rest: F7 takes the
            // bellows out, F7 again swaps it for the tap.
            GLFW_KEY_F7 -> controller.reachFor(if (controller.tool == Tool.Inject) Tool.InjectWater else Tool.Inject)
            GLFW_KEY_F5 -> { controller.reset(); renderer.centreOn(controller.state) }
            GLFW_KEY_F8 -> controller.fit()
            GLFW_KEY_F9 -> hud.onSave()
            GLFW_KEY_F10 -> hud.onLoad()
            GLFW_KEY_F11 -> {
                val report = profiler.report()
                println("═══ PROFILER REPORT (tick ${controller.tick}) ═══")
                println("  wall: ${(report.tickAvgNanos / 1000).toInt()} us avg, p50=${(report.tickP50Nanos / 1000).toInt()} us, p95=${(report.tickP95Nanos / 1000).toInt()} us, p99=${(report.tickP99Nanos / 1000).toInt()} us")
                println("  ${report.phases.size} phases")
                for (phase in report.phases.sortedByDescending { it.avgNanos }) {
                    println("    ${"%-20s".format(phase.name)}: avg=${(phase.avgNanos / 1000).toInt()} us  max=${(phase.maxNanos / 1000).toInt()} us  share=${"%.1f".format(phase.sharePercent)}%  alloc=${(phase.avgBytes / 1024).toInt()} KB/tick")
                }
                println("════════════════════════════════════════════════════════")
            }
            // Take a copy of whatever the inspector is reading and go build it — see
            // [OutofspaceController.grab]. It replaced C-then-V on the hovered tile, which was two
            // keys for one idea; this is the *copy* half of that pair kept, doing the whole job.
            GLFW_KEY_C -> controller.grab()
            // Through the controller's own ladder, so the keys and the HUD's buttons cannot reach
            // different speeds — see `OutofspaceController.SPEEDS`.
            GLFW_KEY_LEFT_BRACKET -> controller.nudgeSpeed(faster = false)
            GLFW_KEY_RIGHT_BRACKET -> controller.nudgeSpeed(faster = true)
            // One rung out of wherever the player is standing, all the way up to the menu — see
            // [OutofspaceHud.escape]. Through the HUD rather than the controller because the top of
            // the ladder is a sheet, and sheets are the HUD's.
            GLFW_KEY_ESCAPE -> hud.escape(controller)
            // ⚠️ **Through `reachFor` first, so the number row takes the build tool out.** These
            // used to set the brush and leave the tool alone, so pressing `3` while holding DELETE
            // silently changed what you would build if you were building — a decision with no
            // visible effect until much later.
            // ⚠️ **`openTool`, not `reachFor`, and the difference matters here.** These name the
            // brush they want, so the palette step `reachFor` would take on the way in is one the
            // next line immediately undoes. They used to set the brush and leave the tool alone, so
            // pressing `3` while holding DELETE silently changed what you would build if you were
            // building — a decision with no visible effect until much later.
            in GLFW_KEY_1..GLFW_KEY_9 -> Brush.ALL.getOrNull(key - GLFW_KEY_1)?.let {
                controller.openTool(Tool.Build); controller.brush = it
            }
            GLFW_KEY_0 -> Brush.ALL.getOrNull(9)?.let {
                controller.openTool(Tool.Build); controller.brush = it
            }
        }
        // Name entry for save/load dialog.
        if (hud.capturingName) {
            when (key) {
                GLFW_KEY_BACKSPACE -> hud.backspace()
                GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                    if (hud.saveMode) hud.commitSave() else hud.commitLoad()
                }
                GLFW_KEY_ESCAPE -> hud.closeSaveLoadDialog()
            }
            return@glfwSetKeyCallback
        }
    }

    glfwSetCharCallback(window) { _, codepoint ->
        if (codepoint in 32..126 && hud.capturingName) {
            hud.typeChar(codepoint.toChar())
        }
    }

    var lastTime = glfwGetTime()
    var lastFpsTime = lastTime
    var frames = 0
    var fps = 0f

    while (!glfwWindowShouldClose(window)) {
        glfwPollEvents()
        updateResolution(window, ui, renderer)

        val now = glfwGetTime()
        val delta = (now - lastTime).toFloat().coerceIn(0f, 0.25f)
        lastTime = now
        frames++
        if (now - lastFpsTime >= 1.0) { fps = frames.toFloat(); frames = 0; lastFpsTime = now }

        ui.advanceClock(delta)

        // Keyboard pan, integrated against real time so the camera crosses the same distance a
        // second on any machine. Pixels, because that is what [panByPixels] speaks and what keeps
        // the speed constant on screen rather than in tiles as the zoom changes.
        if (panKeys.any { it }) {
            val step = KEY_PAN_PIXELS_PER_SECOND * delta
            val dx = (if (panKeys[1]) step else 0f) - (if (panKeys[3]) step else 0f)
            val dy = (if (panKeys[0]) step else 0f) - (if (panKeys[2]) step else 0f)
            renderer.panByPixels(dx, dy)
        }
        if (leftDown && uiConsumed) {
            val (px, py) = cursorPixel(window)
            ui.updateHold(px, py, delta)
        }

        val state = controller.tick(delta)
        if (frame.advance(state).moved) lastPainted = TileIndex.NONE

        // After the camera has been moved for this frame and before it is used again, because the
        // falloff is measured from where the player is actually looking.
        impactAudio.onFrame(state, renderer.camX, renderer.camY)

        renderer.draw(
            state, controller.inspectTile, controller.inspectLayer, hovered, controller.overlay,
            controller.simTime, controller.mode.camera, controller.planAt(hovered),
        )
        hud.build(ui, controller, fps, hovered)
        ui.draw()

        glfwSwapBuffers(window)
    }

    impactAudio.release()
    renderer.cleanup()
    ui.cleanup()
    glfwDestroyWindow(window)
    glfwTerminate()
}

/**
 * How fast WASD moves the camera, in framebuffer pixels a second.
 *
 * Pixels rather than tiles, so the world slides past at the same rate whatever the zoom — a
 * tiles-per-second pan crawls when zoomed in and is unusable when zoomed out, which is the wrong way
 * round on both counts.
 */
private const val KEY_PAN_PIXELS_PER_SECOND = 900f

/** How far one wheel notch moves a scrollable UI list. Cyto's number, for the same gesture. */
private const val WHEEL_SCROLL_PX = 48f

/**
 * Where a save goes: one well-known file beside wherever the game was started.
 *
 * One slot rather than a file picker, deliberately. The job this does is *handing a world to
 * somebody* — a reproduction of something that misbehaved — and for that, a path you can predict and
 * paste is worth more than a dialog. Slots and naming can come when there is a reason to keep two.
 */
private fun updateResolution(window: Long, ui: Ui, renderer: OutofspaceRenderer) {
    MemoryStack.stackPush().use { st ->
        val w = st.mallocInt(1)
        val h = st.mallocInt(1)
        glfwGetFramebufferSize(window, w, h)
        val fw = max(1, w[0]).toFloat()
        val fh = max(1, h[0]).toFloat()
        renderer.setResolution(fw, fh)
        ui.setResolution(fw, fh)
    }
}

/** Cursor position in framebuffer pixels — the toolkit's coordinate space, HiDPI-correct. */
private fun cursorPixel(window: Long): Pair<Float, Float> {
    MemoryStack.stackPush().use { st ->
        val cx = st.mallocDouble(1)
        val cy = st.mallocDouble(1)
        glfwGetCursorPos(window, cx, cy)
        val winW = st.mallocInt(1); val winH = st.mallocInt(1)
        val fbW = st.mallocInt(1); val fbH = st.mallocInt(1)
        glfwGetWindowSize(window, winW, winH)
        glfwGetFramebufferSize(window, fbW, fbH)
        return Pair(
            cx[0].toFloat() * fbW[0] / max(1, winW[0]),
            cy[0].toFloat() * fbH[0] / max(1, winH[0]),
        )
    }
}
