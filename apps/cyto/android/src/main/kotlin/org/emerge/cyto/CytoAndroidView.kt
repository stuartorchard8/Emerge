package org.emerge.cyto

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.EditText
import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.campaign.CampaignDirector
import org.emerge.demo.cyto.campaign.CampaignQuery
import org.emerge.demo.cyto.campaign.Control
import org.emerge.demo.cyto.campaign.PlayerAction
import org.emerge.demo.cyto.campaign.WorldRun
import org.emerge.demo.cyto.host.CampaignContent
import org.emerge.demo.cyto.host.CampaignProgress
import org.emerge.demo.cyto.host.CytoGenomes
import org.emerge.demo.cyto.host.CytoMenu
import org.emerge.demo.cyto.host.CytoSaves
import org.emerge.demo.cyto.host.CytoStorage
import org.emerge.demo.cyto.host.GenomeEntry
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.demo.cyto.ui.CytoHud
import org.emerge.demo.cyto.ui.GeneEditor
import org.emerge.render.torus.ui.Ui
import org.emerge.sim.core.EntityId
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Android host for the native Cyto demo. Drives the same front-end shell + in-game UI as the desktop
 * host — the front-end [CytoMenu], the campaign ([CampaignDirector]), named saves ([CytoSaves]), the
 * genome-brush library ([CytoGenomes]), and in-game the progressive-disclosure [Ui]/[CytoHud]/[GeneEditor]
 * stack — always in the narrow (phone) layout. The sim tick + render + UI all run on the GL thread (in
 * [onDrawFrame]); touch events are marshalled onto it via [queueEvent].
 *
 * Two things differ from desktop: the sim ticks inline (no threaded driver — pause/speed are a local
 * flag + multiplier), and the two name-entry screens (save world / save genome) pop a native Android
 * dialog for text, since a phone has no physical keyboard.
 *
 * Touch model: the front-end routes taps to the menu only. In-game, one finger routes through the UI
 * first ([Ui.hitTestDown]/[dragTo]/[hitTestUp], no wheel); a miss grabs the cell under it (drag moves it)
 * or pans empty space (which drops any selection + camera focus). A tap on a cell selects it (info sheet)
 * and gives it camera focus so it eases up into the free area above the sheet. Two fingers pinch-zoom.
 */
internal class CytoAndroidView(context: Context) : GLSurfaceView(context) {
    private val controller = CytoController()
    private val displayDensity = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    // Created on the GL thread (need a current context); only touched there afterwards.
    private var renderer: CytoRenderer? = null
    private var controls: CytoControls? = null
    private var hud: CytoHud? = null
    private var ui: Ui? = null
    private var geneEditor: GeneEditor? = null
    private var menu: CytoMenu? = null
    private var director: CampaignDirector? = null
    private var campaignProgress: CampaignProgress? = null
    private var callbacks: CytoMenu.Callbacks? = null
    private var lastTimeNanos = 0L

    // Brush genome library (drives the palette).
    private var genomes: List<GenomeEntry> = emptyList()
    private var selectedGenome = 0

    // Inline sim speed/pause (no threaded driver on this host).
    private var paused = true   // boot paused behind the menu
    private var speedIdx = DEFAULT_SPEED_IDX

    // Campaign interaction tracking (diffed frame-to-frame) + one-shot signals.
    private var prevHeldId: Int? = null
    private var prevMatterOverlay = false
    private var cameraMovedSignal = false
    private var speedChangedSignal = false

    // A native name dialog is up (guards re-posting while the menu sits on a name page).
    private var nameDialogShown = false

    // Touch state, mutated only inside queueEvent (GL thread).
    private var grabId: EntityId? = null
    private var uiConsumed = false
    private var dragged = false
    private var pointerDown = false
    private var lastX = 0f
    private var lastY = 0f
    private var pinchSpan = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                setup(context)
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                val w = width.toFloat()
                val h = height.toFloat()
                renderer?.setResolution(w, h)
                controls?.setResolution(w, h)
                ui?.setResolution(w, h)
            }

            override fun onDrawFrame(gl: GL10?) {
                drawFrame()
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    // ── One-time wiring (GL thread) ──────────────────────────────────────────────

    private fun setup(context: Context) {
        // A phone has no writable working directory — persist under the app's private files dir.
        CytoStorage.baseDir = context.filesDir.toPath()

        val renderer = CytoRenderer().also { renderer = it }
        val controls = CytoControls().also { controls = it }
        hud = CytoHud()
        geneEditor = GeneEditor()
        val ui = Ui().also { it.setDensity(displayDensity); ui = it }
        val menu = CytoMenu().also { menu = it }
        val director = CampaignDirector().also { director = it }
        val progress = CampaignProgress.load().also { campaignProgress = it }

        // Brush palette from the genome library (seeded on first use).
        genomes = CytoGenomes.list()
        controller.brushGenome = genomes.getOrNull(selectedGenome)?.genome
        controls.onSelectGenome = { i ->
            selectedGenome = i
            controller.brushGenome = genomes.getOrNull(i)?.genome
        }
        // The HUD Speed sheet drives these; pause/speed are inline (no threaded driver).
        controls.showSimSpeed = true
        controls.onSlower = { speedIdx = (speedIdx - 1).coerceAtLeast(0); speedChangedSignal = true }
        controls.onFaster = { speedIdx = (speedIdx + 1).coerceAtMost(SPEEDS.lastIndex); speedChangedSignal = true }
        controls.onTogglePause = { paused = !paused; speedChangedSignal = true }
        controls.showMutation = true
        controls.onCycleMutation = { controller.cycleMutationRate() }

        menu.campaignChapters = CampaignContent.CHAPTERS
        menu.campaignUnlocked = { progress.isUnlocked(it, CampaignContent.ORDER) }
        menu.campaignCompleted = { progress.isCompleted(it) }
        director.onChapterComplete = { id ->
            progress.complete(id)
            menu.openCampaign(); paused = true
        }
        director.onStepEnter = { step -> paused = step.world == WorldRun.Frozen }

        callbacks = CytoMenu.Callbacks(
            onStart = { scenario ->
                director.stop()
                controller.newGame(scenario); renderer.resetView()
                menu.enterGame(); paused = false
            },
            onContinue = { menu.enterGame(); paused = false },
            onLoadNamed = { name ->
                director.stop()
                CytoSaves.load(controller, name); renderer.resetView()
                menu.enterGame(); paused = false
            },
            onStartChapter = { ch ->
                controller.newGame(ch.scenario); renderer.resetView()
                director.start(ch, controller)
                menu.enterGame(); paused = false
            },
            onOpenSave = { menu.openSave("world ${controller.tick}") },
            onSave = { name -> CytoSaves.save(controller, name); menu.enterGame(); paused = false },
            onDelete = { name -> CytoSaves.delete(name) },
            onSaveGenome = { name, color, genome ->
                CytoGenomes.save(name, color, genome)
                genomes = CytoGenomes.list()
                selectedGenome = genomes.indexOfFirst { it.name == CytoSaves.sanitize(name) }.coerceAtLeast(0)
                controller.brushGenome = genomes.getOrNull(selectedGenome)?.genome
                menu.enterGame(); paused = false
            },
            onQuit = { mainHandler.post { (context as? Activity)?.finish() } },
        )

        // Resume the most recent save at boot so Continue picks up where you left off.
        CytoSaves.mostRecent()?.let { CytoSaves.load(controller, it) }

        lastTimeNanos = System.nanoTime()
    }

    // ── Frame (GL thread) ────────────────────────────────────────────────────────

    private fun drawFrame() {
        val r = renderer ?: return
        val c = controls ?: return
        val hud = hud ?: return
        val ui = ui ?: return
        val geneEditor = geneEditor ?: return
        val menu = menu ?: return
        val director = director ?: return
        val cb = callbacks ?: return

        val now = System.nanoTime()
        val delta = ((now - lastTimeNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
        lastTimeNanos = now

        // The world is frozen behind the menu; in-game it advances at the inline speed unless paused.
        val simDelta = if (paused || !menu.inGame) 0f else delta * SPEEDS[speedIdx]
        val frame = controller.tick(simDelta)

        r.showLightField = c.showLightField
        r.showMatterField = c.showMatterField
        r.colorMode = c.colorMode

        if (menu.inGame) {
            if (pointerDown) ui.updateHold(lastX, lastY, delta)
            controller.pruneDeadSelection()
            r.focusedCellId = controller.lastHeldId?.value ?: -1   // highlight = the inspected cell (tap)
            if (!controller.isGrabbed) {
                val pos = controller.cameraFocusPosition()
                r.follow(controller.cameraFocusId?.value ?: -1, pos?.first ?: -1f, pos?.second ?: -1f)
            }
            run {
                val cellShown = geneEditor.isEditing || controller.lastHeldId != null
                val (offX, offY) = geneEditor.freeAreaOffsetPx(narrow = true, cellShown, ui.resWidth, ui.resHeight, ui.scale)
                r.setFollowOffsetPx(offX, offY)
            }
        }

        r.draw(frame)   // renderer fills its own background (also the backdrop behind the menu)

        // ── Campaign director: detect this frame's interactions, then advance gates ──
        if (menu.inGame && director.active) {
            val actions = HashSet<PlayerAction>()
            if (cameraMovedSignal) { actions.add(PlayerAction.MovedCamera); cameraMovedSignal = false }
            if (speedChangedSignal) { actions.add(PlayerAction.ChangedSpeed); speedChangedSignal = false }
            val heldNow = controller.lastHeldId?.value
            if (heldNow != null && heldNow != prevHeldId) actions.add(PlayerAction.SelectedCell)
            if (c.showMatterField && !prevMatterOverlay) actions.add(PlayerAction.ToggledMatterOverlay)
            director.update(
                CampaignQuery(
                    controller.worldStats(),
                    matterOverlayOn = c.showMatterField,
                    paused = paused,
                    selectedGenome = genomes.getOrNull(selectedGenome)?.name,
                ),
                actions,
            )
            prevHeldId = heldNow
            prevMatterOverlay = c.showMatterField
        }

        // Control masking: an active chapter restricts the toolbar to what the current step allows.
        val mask = director.controlMask
        c.showBrush = mask.allows(Control.Brush)
        c.showTouchModes = mask.allows(Control.Brush)
        c.worldSpawnEnabled = director.active && mask.allows(Control.Spawn)
        if (c.worldSpawnEnabled) {
            val chapter = director.activeChapter
            controller.brushGenome =
                if (chapter?.spawnCopiesHeldCell == true) controller.heldGenome() ?: chapter.spawnGenome
                else chapter?.spawnGenome
        }
        c.showSimSpeed = mask.allows(Control.Speed)
        c.showMutation = mask.allows(Control.Mutation)
        c.simPaused = paused
        c.simStatus = if (paused) "PAUSED" else "${SPEEDS[speedIdx]}x"
        c.mutationLabel = formatMutationRate(controller.mutationRateDenom())
        c.genomePalette = genomes.map { it.name to it.color }
        c.selectedGenome = selectedGenome

        ui.frame {
            if (menu.inGame) {
                val cellUp = geneEditor.isEditing || controller.lastHeldId != null
                val modalUp = geneEditor.isEditing
                val showHud = !geneEditor.isEditing && controller.lastHeldId == null
                if (!showHud) hud.close()
                // Coach first so the (expanded) cell sheet draws over it; the short peek never reaches it.
                if (!modalUp) director.render(this, controller, collapsed = cellUp, narrow = true)
                if (mask.allows(Control.GeneEditor)) {
                    geneEditor.render(
                        this, controller,
                        grouping = director.activeChapter?.grouping,
                        insertableGroups = director.activeChapter?.insertableGroups ?: emptySet(),
                        narrow = true,
                    ) {
                        val g = controller.heldGenome()
                        if (g != null) {
                            val default = genomes.getOrNull(selectedGenome)?.name ?: "genome"
                            menu.openGenomeSave(default, g, controller.heldBioColorRgba() ?: 0x888888FFL)
                        }
                    }
                }
                if (showHud) hud.render(this, c, wide = false) { menu.openTitle(); paused = true }
            } else {
                menu.render(this, CytoSaves.list(), cb)
            }
        }
        ui.draw()

        // A name-entry page pops a native dialog (the phone has no physical keyboard).
        if (menu.capturingName && !nameDialogShown) {
            nameDialogShown = true
            val forGenome = menu.page == CytoMenu.Page.SaveGenome
            val default = menu.currentName()
            mainHandler.post { showNameDialog(forGenome, default) }
        }
    }

    // ── Native name dialog (main thread) ─────────────────────────────────────────

    private fun showNameDialog(forGenome: Boolean, default: String) {
        val input = EditText(context).apply { setText(default); setSelection(text.length) }
        AlertDialog.Builder(context)
            .setTitle(if (forGenome) "Save Genome" else "Save World")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                queueEvent {
                    val menu = menu ?: return@queueEvent
                    val cb = callbacks ?: return@queueEvent
                    if (name.isNotBlank()) {
                        if (forGenome) cb.onSaveGenome(name, menu.pendingGenomeColor(), menu.pendingGenome())
                        else cb.onSave(name)
                    } else menu.enterGame().also { paused = false }
                    nameDialogShown = false
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                queueEvent { menu?.enterGame(); paused = false; nameDialogShown = false }
            }
            .setOnCancelListener {
                queueEvent { menu?.enterGame(); paused = false; nameDialogShown = false }
            }
            .show()
    }

    /** Compact label for the Mut button: "off", "1/1M", "1/100k", "1/1k", … */
    private fun formatMutationRate(denom: Int): String = when {
        denom <= 0 -> "off"
        denom % 1_000_000 == 0 -> "1/${denom / 1_000_000}M"
        denom % 1_000 == 0 -> "1/${denom / 1_000}k"
        else -> "1/$denom"
    }

    // ── Touch (marshalled onto the GL thread) ────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            handlePinch(event)
            return true
        }
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> queueEvent { onDown(x, y) }
            MotionEvent.ACTION_MOVE -> queueEvent { onMove(x, y) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> queueEvent { onUp(x, y) }
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        val r = renderer ?: return
        val c = controls ?: return
        val ui = ui ?: return
        val geneEditor = geneEditor ?: return
        dragged = false
        pointerDown = true
        lastX = x
        lastY = y
        pinchSpan = 0f
        // Front-end shell: taps route to the menu only, no world interaction.
        if (menu?.inGame != true) {
            uiConsumed = true
            ui.hitTestDown(x, y)
            return
        }
        // UI first: a hit (info-panel/HUD buttons, scroll areas) consumes the press.
        if (ui.hitTestDown(x, y)) {
            uiConsumed = true
            grabId = null
            return
        }
        uiConsumed = false
        geneEditor.closeDropdown()   // a press outside the UI dismisses any open picker
        val world = r.screenToWorld(x, y)
        val hit = controller.cellAt(world[0], world[1])
        grabId = hit
        if (hit != null && c.touchMode == TouchMode.Detach) controller.detach(hit)
    }

    private fun onMove(x: Float, y: Float) {
        val r = renderer ?: return
        val c = controls ?: return
        val ui = ui ?: return
        // A press the UI claimed (or any press while the menu is up) may be a drag inside a scroll area.
        if (uiConsumed) {
            ui.dragTo(x, y)
            lastX = x
            lastY = y
            return
        }
        val dx = x - lastX
        val dy = y - lastY
        val held = grabId
        if (!dragged && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
            dragged = true
            // An empty-space drag (no cell grabbed) fully deselects — a phone has one pointer, so this is the
            // natural "put the cell down" gesture (desktop keeps the selection because it has a right-click camera).
            if (held == null) { controller.clearSelection(); controller.clearCameraFocus() }
        }
        if (held != null) {
            val world = r.screenToWorld(x, y)
            controller.grab(held, world[0], world[1], sticky = c.touchMode == TouchMode.Sticky)
        } else {
            r.panByPixels(dx, dy)
            cameraMovedSignal = true
        }
        lastX = x
        lastY = y
    }

    private fun onUp(x: Float, y: Float) {
        val r = renderer
        val c = controls
        val ui = ui
        pointerDown = false
        if (uiConsumed) {
            ui?.hitTestUp(x, y)
            ui?.releaseHold()
            uiConsumed = false
            grabId = null
            dragged = false
            return
        }
        ui?.releaseHold()
        if (!dragged && r != null && c != null) {
            val hit = grabId
            if (hit != null) {
                controller.focus(hit)         // select → info sheet
                controller.cameraFocus(hit)   // and follow it up into the free area
            }
            if (c.showBrush || c.worldSpawnEnabled) {
                val world = r.screenToWorld(x, y)
                controller.tap(world[0], world[1], c.touchMode, c.cellType)
            }
        }
        controller.releaseGrab()
        grabId = null
        dragged = false
        uiConsumed = false
    }

    private fun handlePinch(event: MotionEvent) {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        val span = hypot(dx, dy)
        val midX = (event.getX(0) + event.getX(1)) * 0.5f
        val midY = (event.getY(0) + event.getY(1)) * 0.5f
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> queueEvent {
                pinchSpan = span; grabId = null; uiConsumed = true; pointerDown = false
            }
            MotionEvent.ACTION_MOVE -> queueEvent {
                if (menu?.inGame == true && pinchSpan > 0f && span > 0f) {
                    renderer?.zoomAtScreen(midX, midY, span / pinchSpan)
                    cameraMovedSignal = true
                }
                pinchSpan = span
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                queueEvent { pinchSpan = 0f; uiConsumed = false }
        }
    }

    companion object {
        private const val DRAG_THRESHOLD_PX = 12f
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
        private const val DEFAULT_SPEED_IDX = 2   // 1x
    }
}
