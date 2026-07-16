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
 * goes to the UI first; if it misses, a left press on a cell grabs it (Sticky/Detach hold-mode
 * effects applied) and a left click spawns/acts per the controls' current mode + cell type.
 * The right button owns the camera: drag pans, and a click that didn't pan deselects (as Esc
 * does). Scroll zooms. Left-dragging empty space is reserved for a future area-select and does
 * nothing today. F5/F9 save/load.
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
        // Layout override: F2 forces the narrow (phone) gene UI on at any window width, so it can be evaluated
        // on a desktop-width/HiDPI screen where the automatic width switch (framebuffer px) never trips.
        val layout = LayoutToggle()
        installMouseHandlers(window, controller, renderer, controls, ui, geneEditor, menu, mouse, signals)
        installKeyHandlers(window, controller, simDriver, menu, geneEditor, menuCallbacks, layout)

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

            // WASD free camera pan. Held keys pan the camera each frame (delta-scaled). Suppressed while a
            // name field is capturing keystrokes so typing W/A/S/D doesn't move the camera.
            if (menu.inGame && !menu.capturingName && !geneEditor.capturingGroupName) {
                var kx = 0f; var ky = 0f
                if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) kx -= 1f   // move camera right
                if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) kx += 1f   // move camera left
                if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) ky += 1f   // move camera up
                if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) ky -= 1f   // move camera down
                if (kx != 0f || ky != 0f) {
                    val step = CAMERA_KEY_PAN_PX_PER_SEC * delta
                    renderer.panByPixels(kx * step, ky * step)
                    controller.clearSelection()
                    signals.cameraMoved = true
                }
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
            controls.showTouchModes = mask.allows(Control.Brush)
            // Ch8 "tap to add a cell": permit empty-space spawns of the chapter's genome without the full
            // brush palette. Only inside an active chapter - when idle the mask is ALL (which includes Spawn),
            // and applying it would clobber the sandbox's own brush genome with a null chapter spawnGenome.
            controls.worldSpawnEnabled = director.active && mask.allows(Control.Spawn)
            if (controls.worldSpawnEnabled) {
                val chapter = director.activeChapter
                // "Last-modified brush" (Ch9): tap out a live copy of the selected cell's genome, so the
                // player's just-made muscle edits carry into the next cell. Falls back to the fixed spawn genome.
                controller.brushGenome =
                    if (chapter?.spawnCopiesHeldCell == true) controller.heldGenome() ?: chapter.spawnGenome
                    else chapter?.spawnGenome
            }
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
                // One adaptive UI (UI_REDESIGN.md §8): below NARROW_MAX_PX the gene editor becomes the
                // full-screen L3 modal + L4 sheets; a full-screen modal owns the screen, so the coach and the
                // Menu/Save bar are suppressed behind it.
                val narrow = layout.forceNarrow || ui.resWidth < NARROW_MAX_PX
                val modalUp = narrow && geneEditor.isEditing
                // In narrow mode a held cell fills the lower screen (L2 sheet) or the whole screen (L3 modal),
                // so the coach steps aside until it's dismissed (§6.1 — proper coach docking is later work).
                val cellUp = narrow && controller.lastHeldId != null
                // Last-held-cell info panel + gene-editor kit + a Menu button (on top of the controls).
                ui.frame {
                    if (mask.allows(Control.GeneEditor)) {
                        geneEditor.render(
                            this, controller,
                            grouping = director.activeChapter?.grouping,
                            insertableGroups = director.activeChapter?.insertableGroups ?: emptySet(),
                            narrow = narrow,
                        ) {
                            val g = controller.heldGenome()
                            if (g != null) {
                                val default = genomes.getOrNull(selectedGenome)?.name ?: "genome"
                                menu.openGenomeSave(default, g, controller.heldBioColorRgba() ?: 0x888888FFL)
                                simDriver.setPaused(true)
                            }
                        }
                    }
                    if (!modalUp) {
                        panel(org.emerge.render.torus.ui.Anchor.TopLeft, background = 0x00000000) {
                            actionRow(listOf(
                                Triple("Menu", 0x2A3550FFL) { menu.openTitle(); simDriver.setPaused(true) },
                                Triple("Save", 0x2E6E5EFFL) { menu.openSave(defaultSaveName(controller)); simDriver.setPaused(true) },
                            ))
                        }
                    }
                    // The campaign coach overlay (bottom-centre), on top of the controls.
                    if (!cellUp) director.render(this, controller)
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
        geneEditor: GeneEditor, cb: CytoMenu.Callbacks, layout: LayoutToggle,
    ) {
        glfwSetKeyCallback(window) { _, key, _, action, _ ->
            if (action != GLFW_PRESS && action != GLFW_REPEAT) return@glfwSetKeyCallback
            // In-game group tagging captures a typed name — route edit keys to the editor, not the global
            // shortcuts (Space/[/]) so typing a space doesn't pause the sim (mirrors the menu name field).
            if (geneEditor.capturingGroupName) {
                when (key) {
                    GLFW_KEY_BACKSPACE -> geneEditor.groupBackspace()
                    GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> geneEditor.confirmGroupName()
                    GLFW_KEY_ESCAPE -> geneEditor.cancelGroupName()
                }
                return@glfwSetKeyCallback
            }
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
                // Force the narrow (phone) gene UI on/off regardless of window width.
                GLFW_KEY_F2 -> layout.forceNarrow = !layout.forceNarrow
            }
        }
        glfwSetCharCallback(window) { _, codepoint ->
            if (codepoint in 32..126) {
                if (menu.capturingName) menu.typeChar(codepoint.toChar())
                else if (geneEditor.capturingGroupName) geneEditor.typeGroupChar(codepoint.toChar())
            }
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
            if (button != GLFW_MOUSE_BUTTON_LEFT && button != GLFW_MOUSE_BUTTON_RIGHT)
                return@glfwSetMouseButtonCallback
            val px = cursorPixel(win)
            // While the front-end shell is up, clicks only route to its widgets — no world interaction.
            if (!menu.inGame) {
                if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
                if (action == GLFW_PRESS) {
                    ui.hitTestDown(px.first, px.second)
                }
                else {
                    ui.hitTestUp(px.first, px.second)
                    ui.releaseHold()
                }
                return@glfwSetMouseButtonCallback
            }
            // Right button owns the camera: drag pans, and a click that didn't pan clears the selection
            // (the same thing Esc does). It never touches the UI or the world.
            if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                when (action) {
                    GLFW_PRESS -> {
                        state.panning = true
                        state.panned = false
                        state.panLastX = px.first
                        state.panLastY = px.second
                    }
                    GLFW_RELEASE -> {
                        if (state.panning && !state.panned) controller.clearSelection()
                        state.panning = false
                        state.panned = false
                    }
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
                        // Painting is gated with the brush: while a campaign step masks Brush off,
                        // a world tap must not spawn/act (selection above still works via focus()).
                        // worldSpawnEnabled is the narrow exception: Ch8 permits empty-space spawns of the
                        // chapter genome without the palette (an empty-space tap spawns; a tap on a cell in
                        // the default Base mode is a no-op, so selection is unaffected).
                        if (controls.showBrush || controls.worldSpawnEnabled) {
                            val world = renderer.screenToWorld(px.first, px.second)
                            controller.tap(world[0], world[1], controls.touchMode, controls.cellType)
                        }
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
            val px = cursorPixel(win)
            if (state.panning && isRightDown(win)) {
                val dx = px.first - state.panLastX
                val dy = px.second - state.panLastY
                if (!state.panned && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX))
                    state.panned = true
                renderer.panByPixels(dx, dy)
                signals.cameraMoved = true
                state.panLastX = px.first
                state.panLastY = px.second
            }
            // A press the UI claimed may be a drag *inside a scroll area* (L4 sheet / long genome): route it
            // to the toolkit, which scrolls and cancels the click past its slop.
            if (state.uiConsumed) { ui.dragTo(px.first, px.second); return@glfwSetCursorPosCallback }
            // Left drag only ever moves a grabbed cell; on empty space it does nothing (reserved for a
            // future area-select).
            if (!isPrimaryDown(win)) return@glfwSetCursorPosCallback
            val dx = px.first - state.lastX
            val dy = px.second - state.lastY
            if (!state.dragged && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX))
                state.dragged = true

            val grabId = state.grabId
            if (grabId != null) {
                val world = renderer.screenToWorld(px.first, px.second)
                controller.grab(grabId, world[0], world[1], sticky = controls.touchMode == TouchMode.Sticky)
            }
            state.lastX = px.first
            state.lastY = px.second
        }

        glfwSetScrollCallback(window) { win, _, yoffset ->
            if (!menu.inGame) return@glfwSetScrollCallback
            if (yoffset == 0.0) return@glfwSetScrollCallback
            val px = cursorPixel(win)
            // The wheel scrolls a UI list (L4 sheet / genome) when it's over one; otherwise it zooms the world.
            val area = ui.scrollAreaAt(px.first, px.second)
            if (area != null) { ui.scrollBy(area, (-yoffset * WHEEL_SCROLL_PX).toFloat()); return@glfwSetScrollCallback }
            val steps = yoffset.coerceIn(-24.0, 24.0)
            renderer.zoomAtScreen(px.first, px.second, 1.1.pow(steps).toFloat())
            signals.cameraMoved = true
        }
    }

    private fun isPrimaryDown(win: Long): Boolean =
        glfwGetMouseButton(win, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS

    private fun isRightDown(win: Long): Boolean =
        glfwGetMouseButton(win, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS

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

    /** F2 override to force the narrow gene UI on at any width (glfw callbacks run on the render thread, so a
     *  plain var is safe). */
    private class LayoutToggle { var forceNarrow = false }

    private class MouseState {
        var dragged = false
        var uiConsumed = false
        var lastX = 0f
        var lastY = 0f
        var grabId: EntityId? = null
        // Right button: camera pan. `panned` distinguishes a pan from a click (which deselects).
        var panning = false
        var panned = false
        var panLastX = 0f
        var panLastY = 0f
    }

    // Below this framebuffer width the UI switches to the narrow (phone) layout — the gene editor becomes a
    // full-screen modal + sheets. Resize the window narrow to see it; a phone host sets density instead.
    private const val NARROW_MAX_PX = 600f
    // Framebuffer px scrolled per wheel notch over a UI list.
    private const val WHEEL_SCROLL_PX = 48f

    private const val DRAG_THRESHOLD_PX = 4f
    // WASD camera pan rate, in pixels/second (fed to panByPixels, so it scales with zoom just like a drag).
    // ~one screen-height per second at the default window height.
    private const val CAMERA_KEY_PAN_PX_PER_SEC = 900f
}
