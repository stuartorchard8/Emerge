package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.demo.cyto.ui.GeneEditor
import org.emerge.render.torus.ui.Ui
import org.emerge.sim.core.EntityId
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Desktop host for the native Cyto demo. Drives a [CytoController] + [CytoRenderer] and
 * draws the on-screen [CytoControls] overlay (the faithful Cyto control UI). Pointer-down
 * goes to the UI first; if it misses, a press on a cell grabs it (Sticky/Detach hold-mode
 * effects applied), a press on empty space pans, scroll zooms, and a click spawns/acts per
 * the controls' current mode + cell type. F5/F9 save/load.
 */
object CytoSceneView {
    private val SAVE_PATH: Path = Path.of("cyto-save.bin")
    /** Sidecar recording the saved world's geometry ([org.emerge.demo.cyto.sim.CytoWorldConfig]) so a Load can
     *  resize the torus/day-night to match before restoring the snapshot (the .bin doesn't carry it). */
    private val WORLD_PATH: Path = Path.of("cyto-save.world")

    fun start() {
        Configuration.STACK_SIZE.set(512)
        Thread { runGl() }.start()
    }

    private fun runGl() {
        val controller = CytoController()
        // Sim runs on its own thread (see below); created before the window so its controls can be bound to
        // keys in initWindow.
        val simDriver = CytoSimDriver(controller)

        // GL context must be current (initWindow) before any shader/texture is created.
        val window = initWindow(
            onSave = { saveSnapshot(controller) },
            onLoad = { loadSnapshot(controller) },
            onTogglePause = { simDriver.togglePause() },   // Space
            onSlower = { simDriver.slower() },              // [
            onFaster = { simDriver.faster() },              // ]
            onDeselect = { controller.clearSelection() },   // Esc
        )

        val renderer = CytoRenderer()
        val controls = CytoControls()
        // On-screen buttons drive these (no keyboard-only controls): the Light button owns its toggle
        // state (synced to the renderer each frame), the Load-Genome button loads the brush genome.
        controls.onLoadGenome = { loadGenome(controller, controls.cellType) }
        autoLoadSnapshotAtStartup(controller)

        // Run the sim on its own thread, decoupled from this (vsync-paced) draw loop, with on-screen
        // SLOW/PAUSE/FAST controls + a TPS/FPS readout (also bound to Space / [ / ] — see initWindow).
        controls.showSimSpeed = true
        controls.onSlower = { simDriver.slower() }
        controls.onFaster = { simDriver.faster() }
        controls.onTogglePause = { simDriver.togglePause() }
        // Mutation rate — tap the "Mut" button to cycle off → 1/1M → 1/100k → 1/10k → 1/1k (saved on the world).
        controls.showMutation = true
        controls.onCycleMutation = { controller.cycleMutationRate() }
        simDriver.start()

        // Shared in-game UI toolkit — the last-held-cell info panel + the gene-editor kit.
        val ui = Ui()
        val geneEditor = GeneEditor()

        // Front-end shell (title / new / custom / about). Boot into the menu with the sim paused behind it.
        val menu = CytoMenu()
        simDriver.setPaused(true)
        val menuCallbacks = CytoMenu.Callbacks(
            onStart = { scenario ->
                simDriver.setPaused(true)
                controller.newGame(scenario)
                renderer.resetView()
                menu.enterGame(); simDriver.setPaused(false)
            },
            onContinue = { menu.enterGame(); simDriver.setPaused(false) },
            onLoad = {
                simDriver.setPaused(true)
                loadSnapshot(controller)
                menu.enterGame(); simDriver.setPaused(false)
            },
            onQuit = { glfwSetWindowShouldClose(window, true) },
        )

        val mouse = MouseState()
        installMouseHandlers(window, controller, renderer, controls, ui, geneEditor, menu, mouse)

        var lastTime = glfwGetTime()
        var fps = 0.0
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            updateResolution(window, renderer, controls, ui)

            val now = glfwGetTime()
            val delta = (now - lastTime).toFloat().coerceIn(0f, 0.25f)
            lastTime = now
            if (delta > 0f) fps = fps * 0.9 + (1.0 / delta) * 0.1   // smoothed draw rate

            // Drive hold-to-repeat steppers (threshold +/-) while the primary button is held.
            if (isPrimaryDown(window)) {
                val (cx, cy) = cursorPixel(window)
                ui.updateHold(cx, cy, delta)
            }

            renderer.showLightField = controls.showLightField   // Light button → renderer
            renderer.showMatterField = controls.showMatterField // Matter button → renderer
            renderer.colorMode = controls.colorMode             // Color button → renderer
            renderer.focusedCellId = controller.lastHeldId?.value ?: -1   // full-value highlight on the inspected cell
            // Only follow when a cell is focused but NOT being grabbed.
            if (!controller.isGrabbed) {
                val (fx, fy) = controller.heldCellPosition() ?: (-1f to -1f)
                val id = controller.lastHeldId?.value ?: -1
                renderer.follow(id, fx, fy)
            }
            // The sim advances on its own thread; we render whatever it last published.
            val frame = controller.latestFrame()
            controls.simPaused = simDriver.paused
            controls.simBehind = simDriver.behind()
            controls.simStatus = "${simDriver.status()}   ${fps.toInt()} FPS"
            controls.mutationLabel = formatMutationRate(controller.mutationRateDenom())

            renderer.draw(frame) // renderer fills its own background (also the backdrop behind the menu)
            if (menu.inGame) {
                drawReadouts(controller, renderer, controls)
                controls.draw()
                // Last-held-cell info panel + gene-editor kit + a Menu button (on top of the controls).
                ui.frame {
                    geneEditor.render(this, controller) { exportHeldGenome(controller, controls.cellType) }
                    panel(org.emerge.render.torus.ui.Anchor.TopLeft, background = 0x00000000) {
                        button("Menu", 0x2A3550FFL) { menu.openTitle(); simDriver.setPaused(true) }
                    }
                }
            } else {
                // Front-end shell over the (paused) world.
                ui.frame { menu.render(this, Files.exists(SAVE_PATH), menuCallbacks) }
            }
            ui.draw()

            glfwSwapBuffers(window)
        }

        simDriver.stop()
        renderer.cleanup()
        controls.cleanup()
        ui.cleanup()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun drawReadouts(
        controller: CytoController,
        renderer: CytoRenderer,
        controls: CytoControls,
    ) {
        // The held cell is shown by the info panel now; the floating readouts are only the
        // Debug "show all" overlay.
        val readouts = controller.readouts(null, controls.showChemicals)
        if (readouts.isEmpty()) return
        for (r in readouts) {
            val screen = renderer.worldToScreen(r.x, r.y)
            controls.drawLabel(r.text, screen[0], screen[1] - 28f, pixelHeight = 12f, color = 0x00FF22FF)
        }
    }

    /** Helper to associate cell types with saved genomes. */
    private fun cellTypeGeneFilename(type: CellType): String = "cyto-${type.name.lowercase()}.gene";
    // Generated from the live AUTOTROPH_GENES so the starter can never drift from the current gene model
    // (the old hand-written weighted-sum text no longer parses under the matter-model GeneCodec).
    private val STARTER_BRUSH: String = buildString {
        appendLine("# cyto brush genome — one gene per line:  ENERGY-SOURCE : CONDITION : ACTION")
        appendLine("#   source:    Light | Break <bond>")
        appendLine("#   condition: <operand> >|< <operand>  (operand = <n> | <species> | Biomass | Touching)")
        appendLine("#              a species token may be any length: r, rg, rgg, …  (e.g.  rgg > 0 : Convert rgg)")
        appendLine("#   action:    Import <s> | FormBond <a> <b> | Convert <s> | Contract | Mitosis | Repair")
        appendLine("#   Blank lines and # comments are ignored. Edit, then click \"Load Genome\" to reload;")
        appendLine("#   pick the 'Brush' type, then Spawn (empty space) / Set to paint.")
        appendLine("# This starter IS the simple autotroph: bond r+g -> rg under light, grow, divide.")
        appendLine(org.emerge.demo.cyto.sim.GeneCodec.serialize(org.emerge.demo.cyto.sim.AUTOTROPH_GENES))
    }

    /** Load the authoring brush genome based on [CellType] (GeneCodec text), driven by switching
     *  cell type. If the file is absent, write a documented starter so there's something to
     *  edit + a working brush. */
    private fun loadGenome(controller: CytoController, type: CellType): Boolean {
        val path: Path = Path.of(cellTypeGeneFilename(type))
        if (!Files.exists(path)) {
            runCatching { Files.writeString(path, STARTER_BRUSH) }
            println("[cyto] wrote a starter ${path.toAbsolutePath()} — edit it and click Load Genome to reload.")
        }
        return runCatching { org.emerge.demo.cyto.sim.GeneCodec.parse(Files.readString(path)) }
            .map { controller.brushGenome = it; println("[cyto] ${type.name} genome: ${it.size} gene(s)"); true }
            .getOrElse { controller.brushGenome = null; println("[cyto] parse failed ($path): ${it.message} — using type presets"); false }
    }

    /** Export the held cell's genome to a GeneCodec `.gene` file (the "EXPORT GENOME" button).
     *  If an existing 'cyto-[CellType.name].gene' file exists, it is archived with a timestamp so it is
     *  never overwritten, and the new genome is saved directly as 'cyto-[CellType.name].gene'. */
    private fun exportHeldGenome(controller: CytoController, type: CellType) {
        val genome = controller.heldGenome()
        if (genome == null) { println("[cyto] export: no cell held"); return }
        val id = controller.lastHeldId?.value ?: -1

        val filename: String = cellTypeGeneFilename(type)
        val path: Path = Path.of(filename)

        // 1. Rename any existing 'cyto-brush.gene' file to include a timestamp
        if (Files.exists(path)) {
            // possible footgun if cellTypePath changes
            val archiveName = "old-${filename.replace(".gene", "-${ System.currentTimeMillis() }.gene")}"
            val archivePath = Path.of(archiveName)
            runCatching {
                Files.move(path, archivePath, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure {
                println("[cyto] warning: failed to archive existing brush file: ${it.message}")
                // Optional: Fall back to standard move if ATOMIC_MOVE fails on this filesystem
                runCatching { Files.move(path, archivePath) }
            }
        }

        // 2. Save the newly exported genome directly to 'cyto-brush.gene'
        val text = buildString {
            appendLine("# cyto genome exported from cell $id")
            appendLine(org.emerge.demo.cyto.sim.GeneCodec.serialize(genome))
        }

        runCatching { Files.writeString(path, text) }
            .onSuccess {
                println("[cyto] exported ${genome.size}-gene genome to ${path.toAbsolutePath()}")
                controller.brushGenome = genome
            }
            .onFailure { println("[cyto] export failed: ${it.message}") }
    }

    /** Compact label for the Mut button: "off", "1/1M", "1/100k", "1/1k", … */
    private fun formatMutationRate(denom: Int): String = when {
        denom <= 0 -> "off"
        denom % 1_000_000 == 0 -> "1/${denom / 1_000_000}M"
        denom % 1_000 == 0 -> "1/${denom / 1_000}k"
        else -> "1/$denom"
    }

    private fun initWindow(
        onSave: () -> Unit, onLoad: () -> Unit,
        onTogglePause: () -> Unit, onSlower: () -> Unit, onFaster: () -> Unit,
        onDeselect: () -> Unit,
    ): Long {
        if (!glfwInit()) error("GLFW init failed")
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

        val window = glfwCreateWindow(720, 720, "Cyto", NULL, NULL)
        if (window == NULL) error("Failed to create GLFW window")

        glfwSetKeyCallback(window) { win, key, _, action, _ ->
            if (action != GLFW_PRESS) return@glfwSetKeyCallback
            when (key) {
                GLFW_KEY_ESCAPE -> onDeselect()   // clear the cell selection (no close-on-escape)
                GLFW_KEY_F5 -> onSave()
                GLFW_KEY_F9 -> onLoad()
                GLFW_KEY_SPACE -> onTogglePause()       // play / pause
                GLFW_KEY_LEFT_BRACKET -> onSlower()      // [  slower
                GLFW_KEY_RIGHT_BRACKET -> onFaster()     // ]  faster
            }
        }

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        org.lwjgl.opengl.GL.createCapabilities()
        return window
    }

    private fun installMouseHandlers(
        window: Long,
        controller: CytoController,
        renderer: CytoRenderer,
        controls: CytoControls,
        ui: Ui,
        geneEditor: GeneEditor,
        menu: CytoMenu,
        state: MouseState,
    ) {
        glfwSetMouseButtonCallback(window) { win, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            val px = cursorPixel(win)
            // While the front-end shell is up, clicks only route to its widgets — no world interaction.
            if (!menu.inGame) {
                if (action == GLFW_PRESS) ui.hitTest(px.first, px.second)
                else ui.releaseHold()
                return@glfwSetMouseButtonCallback
            }
            when (action) {
                GLFW_PRESS -> {
                    state.dragged = false
                    state.lastX = px.first
                    state.lastY = px.second
                    // UI first: a hit (info-panel buttons, then the controls) consumes the press.
                    if (ui.hitTest(px.first, px.second) || controls.hitTest(px.first, px.second)) {
                        state.uiConsumed = true
                        state.grabId = null
                        return@glfwSetMouseButtonCallback
                    }
                    state.uiConsumed = false
                    geneEditor.closeDropdown()   // a press outside the UI dismisses any open picker
                    val world = renderer.screenToWorld(px.first, px.second)
                    val hit = controller.cellAt(world[0], world[1])
                    state.grabId = hit
                    if (hit != null && controls.touchMode == TouchMode.Detach) controller.detach(hit)
                }
                GLFW_RELEASE -> {
                    ui.releaseHold()   // end any in-progress hold-to-repeat
                    if (!state.uiConsumed && !state.dragged) {
                        val hit = state.grabId
                        if (hit != null) {
                            controller.focus(hit)
                        }
                        val world = renderer.screenToWorld(px.first, px.second)
                        controller.tap(world[0], world[1], controls.touchMode, controls.cellType)
                    }
                    controller.releaseGrab()
                    state.grabId = null
                    state.dragged = false
                    state.uiConsumed = false
                }
            }
        }

        glfwSetCursorPosCallback(window) { win, _, _ ->
            if (!menu.inGame) return@glfwSetCursorPosCallback
            if (state.uiConsumed) return@glfwSetCursorPosCallback
            // Only react while the primary button is held (grabId set on a cell, else pan).
            if (!isPrimaryDown(win)) return@glfwSetCursorPosCallback
            val px = cursorPixel(win)
            val dx = px.first - state.lastX
            val dy = px.second - state.lastY
            if (!state.dragged && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                state.dragged = true
                val grabId = state.grabId
                if (grabId == null) {
                    controller.clearSelection()
                }
            }

            val grabId = state.grabId
            if (grabId != null) {
                val world = renderer.screenToWorld(px.first, px.second)
                controller.grab(grabId, world[0], world[1], sticky = controls.touchMode == TouchMode.Sticky)
            } else {
                renderer.panByPixels(dx, dy)
            }
            state.lastX = px.first
            state.lastY = px.second
        }

        glfwSetScrollCallback(window) { win, _, yoffset ->
            if (!menu.inGame) return@glfwSetScrollCallback
            if (yoffset == 0.0) return@glfwSetScrollCallback
            val steps = yoffset.coerceIn(-24.0, 24.0)
            val px = cursorPixel(win)
            renderer.zoomAtScreen(px.first, px.second, 1.1.pow(steps).toFloat())
        }
    }

    private fun isPrimaryDown(win: Long): Boolean =
        glfwGetMouseButton(win, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS

    private fun updateResolution(window: Long, renderer: CytoRenderer, controls: CytoControls, ui: Ui) {
        MemoryStack.stackPush().use { st ->
            val sizeX = st.mallocInt(1)
            val sizeY = st.mallocInt(1)
            glfwGetFramebufferSize(window, sizeX, sizeY)
            val w = max(1f, sizeX[0].toFloat())
            val h = max(1f, sizeY[0].toFloat())
            renderer.setResolution(w, h)
            controls.setResolution(w, h)
            ui.setResolution(w, h)
        }
    }

    private fun cursorPixel(win: Long): Pair<Float, Float> {
        val cursorX = DoubleArray(1)
        val cursorY = DoubleArray(1)
        glfwGetCursorPos(win, cursorX, cursorY)
        val windowW = IntArray(1)
        val windowH = IntArray(1)
        val framebufferW = IntArray(1)
        val framebufferH = IntArray(1)
        glfwGetWindowSize(win, windowW, windowH)
        glfwGetFramebufferSize(win, framebufferW, framebufferH)
        val w = windowW[0].coerceAtLeast(1)
        val h = windowH[0].coerceAtLeast(1)
        return Pair(
            cursorX[0].toFloat() * framebufferW[0].toFloat() / w.toFloat(),
            cursorY[0].toFloat() * framebufferH[0].toFloat() / h.toFloat(),
        )
    }

    private fun saveSnapshot(controller: CytoController) {
        try {
            val bytes = controller.snapshotBytes()
            Files.write(SAVE_PATH, bytes)
            saveWorldGeometry()
            println("Saved Cyto snapshot (${bytes.size} bytes) to ${SAVE_PATH.toAbsolutePath()}")
        } catch (t: Throwable) {
            println("Failed saving Cyto snapshot: ${t.message}")
        }
    }

    private fun loadSnapshot(controller: CytoController) {
        try {
            if (!Files.exists(SAVE_PATH)) {
                println("No Cyto snapshot found at ${SAVE_PATH.toAbsolutePath()}")
                return
            }
            restoreWorldGeometry()   // resize the torus/day-night to the save BEFORE restoring the snapshot
            val bytes = Files.readAllBytes(SAVE_PATH)
            controller.restoreSnapshot(bytes)
            println("Loaded Cyto snapshot (${bytes.size} bytes)")
        } catch (t: Throwable) {
            println("Failed loading Cyto snapshot: ${t.message}")
        }
    }

    private fun autoLoadSnapshotAtStartup(controller: CytoController) {
        if (!Files.exists(SAVE_PATH)) return
        try {
            restoreWorldGeometry()
            controller.restoreSnapshot(Files.readAllBytes(SAVE_PATH))
            println("Auto-loaded Cyto snapshot")
        } catch (t: Throwable) {
            println("Failed auto-loading Cyto snapshot: ${t.message}")
        }
    }

    /** Persist the live world geometry beside the snapshot (`cyto-save.world`): `cellsPerAxis orbitPeriod dayFraction`. */
    private fun saveWorldGeometry() {
        val c = org.emerge.demo.cyto.sim.CytoWorldConfig
        runCatching { Files.writeString(WORLD_PATH, "${c.cellsPerAxis} ${c.orbitPeriod} ${c.dayFraction}") }
    }

    /** Apply a saved geometry sidecar to [org.emerge.demo.cyto.sim.CytoWorldConfig], if present. */
    private fun restoreWorldGeometry() {
        if (!Files.exists(WORLD_PATH)) return
        runCatching {
            val parts = Files.readString(WORLD_PATH).trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                org.emerge.demo.cyto.sim.CytoWorldConfig.applyFrom(parts[0].toInt(), parts[1].toLong(), parts[2].toFloat())
            }
        }.onFailure { println("Failed reading world geometry: ${it.message}") }
    }

    private class MouseState {
        var dragged = false
        var uiConsumed = false
        var lastX = 0f
        var lastY = 0f
        var grabId: EntityId? = null
    }

    private const val DRAG_THRESHOLD_PX = 4f
}
