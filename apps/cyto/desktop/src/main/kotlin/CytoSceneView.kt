package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.campaign.CampaignDirector
import org.emerge.demo.cyto.campaign.CampaignQuery
import org.emerge.demo.cyto.campaign.Control
import org.emerge.demo.cyto.campaign.PlayerAction
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.demo.cyto.ui.GeneEditor
import org.emerge.render.torus.ui.Ui
import org.emerge.sim.core.EntityId
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
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

    fun start() {
        Configuration.STACK_SIZE.set(512)
        Thread { runGl() }.start()
    }

    private fun runGl() {
        val controller = CytoController()
        // Sim runs on its own thread (see below); created before the window so its controls can be bound to
        // keys in initWindow.
        val simDriver = CytoSimDriver(controller)
        // One-shot interaction signals the campaign director consumes each frame (set by input callbacks).
        val signals = CampaignSignals()

        // GL context must be current (initWindow) before any shader/texture is created. Input callbacks are
        // installed below, once the menu + sim controls they route to exist.
        val window = initWindow()

        val renderer = CytoRenderer()
        val controls = CytoControls()
        // The bottom-left brush palette is driven by the genome library (cyto-genomes/, seeded on first use).
        // Selecting a swatch sets the brush genome; `genomes`/`selectedGenome` are refreshed on save/delete.
        var genomes = CytoGenomes.list()
        var selectedGenome = 0
        controller.brushGenome = genomes.getOrNull(selectedGenome)?.genome
        controls.onSelectGenome = { i ->
            selectedGenome = i
            controller.brushGenome = genomes.getOrNull(i)?.genome
        }
        // Resume the most recent named save at boot so Continue picks up where you left off.
        CytoSaves.mostRecent()?.let { CytoSaves.load(controller, it) }

        // Run the sim on its own thread, decoupled from this (vsync-paced) draw loop, with on-screen
        // SLOW/PAUSE/FAST controls + a TPS/FPS readout (also bound to Space / [ / ] — see initWindow).
        controls.showSimSpeed = true
        controls.onSlower = { simDriver.slower(); signals.speedChanged = true }
        controls.onFaster = { simDriver.faster(); signals.speedChanged = true }
        controls.onTogglePause = { simDriver.togglePause(); signals.speedChanged = true }
        // Mutation rate — tap the "Mut" button to cycle off → 1/1M → 1/100k → 1/10k → 1/1k (saved on the world).
        controls.showMutation = true
        controls.onCycleMutation = { controller.cycleMutationRate() }
        simDriver.start()

        // Shared in-game UI toolkit — the last-held-cell info panel + the gene-editor kit.
        val ui = Ui()
        val geneEditor = GeneEditor()

        // Campaign / story mode: the director drives the coach overlay; progress persists across runs.
        val director = CampaignDirector()
        val campaignProgress = CampaignProgress.load()

        // Front-end shell (title / new / custom / about). Boot into the menu with the sim paused behind it.
        val menu = CytoMenu()
        menu.campaignChapters = CampaignContent.CHAPTERS
        menu.campaignUnlocked = { campaignProgress.isUnlocked(it, CampaignContent.ORDER) }
        menu.campaignCompleted = { campaignProgress.isCompleted(it) }
        director.onChapterComplete = { id ->
            campaignProgress.complete(id)
            menu.openCampaign(); simDriver.setPaused(true)
        }
        // Each step chooses whether the world runs or holds still (so a slow reader isn't overtaken by a
        // later concept). Applied on step entry; the player keeps manual pause/speed control within a step.
        director.onStepEnter = { step -> simDriver.setPaused(step.world == org.emerge.demo.cyto.campaign.WorldRun.Frozen) }
        simDriver.setPaused(true)
        val menuCallbacks = CytoMenu.Callbacks(
            onStart = { scenario ->
                simDriver.setPaused(true)
                director.stop()   // leaving the campaign for free play
                controller.newGame(scenario)
                renderer.resetView()
                menu.enterGame(); simDriver.setPaused(false)
            },
            onContinue = { menu.enterGame(); simDriver.setPaused(false) },
            onLoadNamed = { name ->
                simDriver.setPaused(true)
                director.stop()
                CytoSaves.load(controller, name)
                renderer.resetView()
                menu.enterGame(); simDriver.setPaused(false)
            },
            onStartChapter = { ch ->
                simDriver.setPaused(true)
                controller.newGame(ch.scenario)
                renderer.resetView()
                director.start(ch, controller)
                menu.enterGame(); simDriver.setPaused(false)
            },
            onSave = { name ->
                CytoSaves.save(controller, name)
                menu.enterGame(); simDriver.setPaused(false)
            },
            onDelete = { name -> CytoSaves.delete(name) },   // stays on the Load page; list refreshes next frame
            onSaveGenome = { name, color, genome ->
                CytoGenomes.save(name, color, genome)
                genomes = CytoGenomes.list()
                selectedGenome = genomes.indexOfFirst { it.name == CytoSaves.sanitize(name) }.coerceAtLeast(0)
                controller.brushGenome = genomes.getOrNull(selectedGenome)?.genome
                menu.enterGame(); simDriver.setPaused(false)
            },
            onQuit = { glfwSetWindowShouldClose(window, true) },
        )

        val mouse = MouseState()
        installMouseHandlers(window, controller, renderer, controls, ui, geneEditor, menu, mouse, signals)
        installKeyHandlers(window, controller, simDriver, menu, menuCallbacks)

        var lastTime = glfwGetTime()
        var fps = 0.0
        // Campaign interaction tracking (diffed frame-to-frame to raise PlayerActions).
        var prevHeldId: Int? = null
        var prevMatterOverlay = controls.showMatterField
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

            // ── Campaign director: detect this frame's interactions, then advance gates ──────────────
            if (director.active) {
                val actions = HashSet<PlayerAction>()
                if (signals.consumeCameraMoved()) actions.add(PlayerAction.MovedCamera)
                if (signals.consumeSpeedChanged()) actions.add(PlayerAction.ChangedSpeed)
                val heldNow = controller.lastHeldId?.value
                if (heldNow != null && heldNow != prevHeldId) actions.add(PlayerAction.SelectedCell)
                if (controls.showMatterField && !prevMatterOverlay) actions.add(PlayerAction.ToggledMatterOverlay)
                val query = CampaignQuery(
                    controller.worldStats(),
                    matterOverlayOn = controls.showMatterField,
                    paused = simDriver.paused,
                    selectedGenome = genomes.getOrNull(selectedGenome)?.name,
                )
                director.update(query, actions)
                prevHeldId = heldNow
                prevMatterOverlay = controls.showMatterField
            }
            // Control masking: an active chapter restricts the toolbar to what the current step allows.
            val mask = director.controlMask
            controls.showBrush = mask.allows(Control.Brush)
            controls.showSimSpeed = mask.allows(Control.Speed)
            controls.showMutation = mask.allows(Control.Mutation)
            controls.simPaused = simDriver.paused
            controls.simBehind = simDriver.behind()
            controls.simStatus = "${simDriver.status()}   ${fps.toInt()} FPS"
            controls.mutationLabel = formatMutationRate(controller.mutationRateDenom())

            // Feed the genome library into the brush palette (reflects any just-saved genome).
            controls.genomePalette = genomes.map { it.name to it.color }
            controls.selectedGenome = selectedGenome

            renderer.draw(frame) // renderer fills its own background (also the backdrop behind the menu)
            if (menu.inGame) {
                drawReadouts(controller, renderer, controls)
                controls.draw()
                // Last-held-cell info panel + gene-editor kit + a Menu button (on top of the controls).
                ui.frame {
                    if (mask.allows(Control.GeneEditor)) {
                        geneEditor.render(
                            this, controller,
                            grouping = director.activeChapter?.grouping,
                            allowGroupInsert = director.activeChapter?.allowGroupInsert == true,
                        ) {
                            val g = controller.heldGenome()
                            if (g != null) {
                                val default = genomes.getOrNull(selectedGenome)?.name ?: "genome"
                                menu.openGenomeSave(default, g, controller.heldBioColorRgba() ?: 0x888888FFL)
                                simDriver.setPaused(true)
                            }
                        }
                    }
                    panel(org.emerge.render.torus.ui.Anchor.TopLeft, background = 0x00000000) {
                        actionRow(listOf(
                            Triple("Menu", 0x2A3550FFL) { menu.openTitle(); simDriver.setPaused(true) },
                            Triple("Save", 0x2E6E5EFFL) { menu.openSave(defaultSaveName(controller)); simDriver.setPaused(true) },
                        ))
                    }
                    // The campaign coach overlay (bottom-centre), on top of the controls.
                    director.render(this, controller)
                }
            } else {
                // Front-end shell over the (paused) world.
                ui.frame { menu.render(this, CytoSaves.list(), menuCallbacks) }
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

    /** Compact label for the Mut button: "off", "1/1M", "1/100k", "1/1k", … */
    private fun formatMutationRate(denom: Int): String = when {
        denom <= 0 -> "off"
        denom % 1_000_000 == 0 -> "1/${denom / 1_000_000}M"
        denom % 1_000 == 0 -> "1/${denom / 1_000}k"
        else -> "1/$denom"
    }

    private fun initWindow(): Long {
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

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)
        org.lwjgl.opengl.GL.createCapabilities()
        return window
    }

    /** Keyboard: while the Save-name field is up, keys type into it (Backspace/Enter/Esc); otherwise the
     *  in-game keys (Esc deselect, F5 quick-save, Space pause, [ ] speed) apply, plus a char callback that
     *  feeds printable characters into the name field. */
    private fun installKeyHandlers(
        window: Long, controller: CytoController, simDriver: CytoSimDriver, menu: CytoMenu,
        cb: CytoMenu.Callbacks,
    ) {
        glfwSetKeyCallback(window) { _, key, _, action, _ ->
            if (action != GLFW_PRESS && action != GLFW_REPEAT) return@glfwSetKeyCallback
            if (menu.capturingName) {
                when (key) {
                    GLFW_KEY_BACKSPACE -> menu.backspace()
                    GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> if (menu.currentName().isNotBlank()) {
                        // Enter commits the active name field to its page's save action.
                        if (menu.page == CytoMenu.Page.SaveGenome)
                            cb.onSaveGenome(menu.currentName(), menu.pendingGenomeColor(), menu.pendingGenome())
                        else cb.onSave(menu.currentName())
                    }
                    GLFW_KEY_ESCAPE -> { menu.enterGame(); simDriver.setPaused(false) }
                }
                return@glfwSetKeyCallback
            }
            if (action != GLFW_PRESS) return@glfwSetKeyCallback
            when (key) {
                // Esc clears the current cell selection; with nothing selected it opens the front-end menu.
                GLFW_KEY_ESCAPE ->
                    if (controller.lastHeldId != null) controller.clearSelection()
                    else { menu.openTitle(); simDriver.setPaused(true) }
                GLFW_KEY_SPACE -> simDriver.togglePause()
                GLFW_KEY_LEFT_BRACKET -> simDriver.slower()
                GLFW_KEY_RIGHT_BRACKET -> simDriver.faster()
            }
        }
        glfwSetCharCallback(window) { _, codepoint ->
            if (menu.capturingName && codepoint in 32..126) menu.typeChar(codepoint.toChar())
        }
    }

    /** A default save name for the in-game Save button: "world @ <tick>". */
    private fun defaultSaveName(controller: CytoController): String = "world ${controller.tick}"

    private fun installMouseHandlers(
        window: Long,
        controller: CytoController,
        renderer: CytoRenderer,
        controls: CytoControls,
        ui: Ui,
        geneEditor: GeneEditor,
        menu: CytoMenu,
        state: MouseState,
        signals: CampaignSignals,
    ) {
        glfwSetMouseButtonCallback(window) { win, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            val px = cursorPixel(win)
            // While the front-end shell is up, clicks only route to its widgets — no world interaction.
            if (!menu.inGame) {
                if (action == GLFW_PRESS) {
                    ui.hitTestDown(px.first, px.second)
                }
                else {
                    ui.hitTestUp(px.first, px.second)
                    ui.releaseHold()
                }
                return@glfwSetMouseButtonCallback
            }
            when (action) {
                GLFW_PRESS -> {
                    state.dragged = false
                    state.lastX = px.first
                    state.lastY = px.second
                    // UI first: a hit (info-panel buttons, then the controls) consumes the press.
                    if (ui.hitTestDown(px.first, px.second) || controls.hitTest(px.first, px.second)) {
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
                    // UI first: a hit (info-panel buttons, then the controls) consumes the press.
                    if (ui.hitTestUp(px.first, px.second)) {
                        state.uiConsumed = true
                        state.grabId = null
                        return@glfwSetMouseButtonCallback
                    }
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
                signals.cameraMoved = true
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
            signals.cameraMoved = true
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

    /** One-shot interaction flags raised by input callbacks, consumed once per frame by the campaign
     *  director. `consume*` reads-and-clears so each interaction fires exactly one [PlayerAction]. */
    private class CampaignSignals {
        var cameraMoved = false
        var speedChanged = false
        fun consumeCameraMoved(): Boolean = cameraMoved.also { cameraMoved = false }
        fun consumeSpeedChanged(): Boolean = speedChanged.also { speedChanged = false }
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
