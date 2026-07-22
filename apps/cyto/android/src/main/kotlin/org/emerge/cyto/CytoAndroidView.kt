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
import org.emerge.demo.cyto.campaign.InputHints
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
    // Name of the save last loaded or written this session — pre-fills the Save dialog (see onOpenSave).
    private var lastSaveName: String? = null

    // Inline sim speed/pause (no threaded driver on this host).
    private var paused = true   // boot paused behind the menu
    private var speedIdx = DEFAULT_SPEED_IDX

    // Campaign interaction tracking (diffed frame-to-frame) + one-shot signals.
    private var prevHeldId: Int? = null
    private var cameraMovedSignal = false
    private var speedChangedSignal = false
    private var cellMovedSignal = false

    // A native name dialog is up (guards re-posting while the menu sits on a name page).
    private var nameDialogShown = false
    // A native text dialog for an in-editor field (group name / constant value) is up — same guard role as
    // nameDialogShown, for the GeneEditor capture states that route to a physical keyboard on desktop.
    private var editorDialogShown = false

    // Touch state, mutated only inside queueEvent (GL thread).
    private var grabId: EntityId? = null
    private var uiConsumed = false
    private var dragged = false
    private var pointerDown = false
    private var lastX = 0f
    private var lastY = 0f
    // Two-finger transform gesture (port of the original cyto anchor model): the pinch pans by the
    // midpoint's movement and zooms by the ratio of the two fingers' separation, applied about the
    // previous midpoint. With no camera rotation that pins both fingers to their world points exactly.
    private var twoFinger = false
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var prevSpan = 0f

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
        val menu = CytoMenu().also { menu = it; it.hasMouse = false }   // touch host: hide pointer-only settings
        val director = CampaignDirector().also { it.inputHints = InputHints.TOUCH; director = it }   // phone = touch controls
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

        menu.campaignChapters = CampaignContent.PLAYABLE_CHAPTERS   // includes the WIP scratch chapters
        menu.campaignUnlocked = { progress.isUnlocked(it, CampaignContent.ORDER) }
        menu.campaignCompleted = { progress.isCompleted(it) }
        // One continuous world: the director segues between chapters itself (see the desktop host).
        director.chapters = CampaignContent.PLAYABLE_CHAPTERS   // scratch chapters segue into the main flow
        director.onChapterCompleted = { id -> progress.complete(id) }
        director.onWorldReset = { ch -> controller.newGame(ch.scenario); renderer.resetView() }
        director.onCampaignComplete = { menu.openCampaign(); paused = true }
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
                CytoSaves.load(controller, name); lastSaveName = name; renderer.resetView()
                menu.enterGame(); paused = false
            },
            onStartChapter = { ch ->
                controller.newGame(ch.scenario); renderer.resetView()
                director.start(ch, controller)
                menu.enterGame(); paused = false
            },
            onOpenSave = { menu.openSave(lastSaveName ?: "world ${controller.tick}") },
            onSave = { name -> lastSaveName = CytoSaves.save(controller, name); menu.enterGame(); paused = false },
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
        CytoSaves.mostRecent()?.let { CytoSaves.load(controller, it); lastSaveName = it }

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

        r.nightLevel = c.nightLevel
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
                val (offX, offY) = geneEditor.freeAreaOffsetPx(narrow = true, cellShown, ui.resWidth, ui.resHeight, ui.scale, topObscuredPx = director.coachTopInsetPx)
                r.setFollowOffsetPx(offX, offY)
            }
        }

        r.draw(frame)   // renderer fills its own background (also the backdrop behind the menu)

        // ── Campaign director: detect this frame's interactions, then advance gates ──
        if (menu.inGame && director.active) {
            val actions = HashSet<PlayerAction>()
            if (cameraMovedSignal) { actions.add(PlayerAction.MovedCamera); cameraMovedSignal = false }
            if (speedChangedSignal) { actions.add(PlayerAction.ChangedSpeed); speedChangedSignal = false }
            if (cellMovedSignal) { actions.add(PlayerAction.MovedCell); cellMovedSignal = false }
            val heldNow = controller.lastHeldId?.value
            if (heldNow != null && heldNow != prevHeldId) actions.add(PlayerAction.SelectedCell)
            director.update(
                CampaignQuery(
                    controller.worldStats(),
                    paused = paused,
                    selectedGenome = genomes.getOrNull(selectedGenome)?.name,
                ),
                actions,
            )
            prevHeldId = heldNow
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
            controller.spawnBiomass = chapter?.spawnBiomass
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
                val modalUp = geneEditor.isEditing
                val showHud = !geneEditor.isEditing && controller.lastHeldId == null
                if (!showHud) hud.close()
                // Coach first so the (expanded) cell sheet draws over it; the short peek never reaches it.
                if (!modalUp) director.render(this, controller, narrow = true)
                if (mask.allows(Control.GeneEditor)) {
                    geneEditor.render(
                        this, controller,
                        grouping = director.activeChapter?.grouping,
                        insertableGroups = director.activeChapter?.insertableGroups ?: emptySet(),
                        narrow = true,
                        onExport = {
                            val g = controller.heldGenome()
                            if (g != null) {
                                val default = genomes.getOrNull(selectedGenome)?.name ?: "genome"
                                menu.openGenomeSave(default, g, controller.heldBioColorRgba() ?: 0x888888FFL)
                            }
                        },
                    )
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

        // The in-editor text fields (gene group name, numeric constant) route keystrokes to a physical
        // keyboard on desktop; on a phone each pops the same native dialog. Species operands don't need one —
        // their atom buttons are the whole touch input path (GeneEditor.speciesBuilder).
        if (!editorDialogShown) {
            if (geneEditor.capturingGroupName) {
                editorDialogShown = true
                val default = geneEditor.capturedGroupName
                mainHandler.post {
                    showEditorTextDialog("Group Name", default, numeric = false,
                        onSubmit = { s -> queueEvent { this.geneEditor?.submitGroupName(s); editorDialogShown = false } },
                        onCancel = { queueEvent { this.geneEditor?.cancelGroupName(); editorDialogShown = false } })
                }
            } else if (geneEditor.capturingConstantValue) {
                editorDialogShown = true
                val default = geneEditor.capturedConstantValue
                mainHandler.post {
                    showEditorTextDialog("Value", default, numeric = true,
                        onSubmit = { s -> queueEvent { this.geneEditor?.submitConstantValue(s); editorDialogShown = false } },
                        onCancel = { queueEvent { this.geneEditor?.cancelConstantValue(); editorDialogShown = false } })
                }
            }
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

    /** Native text dialog for an in-editor field (gene group name, numeric constant) — the soft-keyboard
     *  stand-in for desktop's physical-keyboard capture. [onSubmit]/[onCancel] marshal back onto the GL
     *  thread themselves (they call into the editor's capture API). */
    private fun showEditorTextDialog(
        title: String, default: String, numeric: Boolean,
        onSubmit: (String) -> Unit, onCancel: () -> Unit,
    ) {
        val input = EditText(context).apply {
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(default); setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onSubmit(input.text.toString().trim()) }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
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
        // Read pointer coords now — the MotionEvent is recycled before the queued GL-thread work runs.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.getX(0); val y = event.getY(0)
                queueEvent { onDown(x, y) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                val (mx, my, sp) = midSpan(event)
                queueEvent { beginTwoFinger(mx, my, sp) }
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                val (mx, my, sp) = midSpan(event)
                queueEvent { onTwoFingerMove(mx, my, sp) }
            } else {
                val x = event.getX(0); val y = event.getY(0)
                queueEvent { onMove(x, y) }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // A finger lifted while ≥2 were down; hand the gesture back to the finger that remains.
                val remain = if (event.actionIndex == 0) 1 else 0
                val x = event.getX(remain); val y = event.getY(remain)
                queueEvent { endTwoFinger(x, y) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val x = event.getX(0); val y = event.getY(0)
                queueEvent { onUp(x, y) }
            }
        }
        return true
    }

    /** Midpoint + separation of the first two pointers (screen px). */
    private fun midSpan(event: MotionEvent): Triple<Float, Float, Float> {
        val mx = (event.getX(0) + event.getX(1)) * 0.5f
        val my = (event.getY(0) + event.getY(1)) * 0.5f
        val sp = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
        return Triple(mx, my, sp)
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
            if (dragged) cellMovedSignal = true
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

    // ── Two-finger transform (port of the original cyto anchor model) ────────────

    /** Second finger down: start the gesture and cancel any single-finger interaction so it neither taps
     *  nor grabs a cell. */
    private fun beginTwoFinger(mx: Float, my: Float, sp: Float) {
        twoFinger = true
        prevMidX = mx; prevMidY = my; prevSpan = sp
        ui?.releaseHold()
        controller.releaseGrab()
        grabId = null
        uiConsumed = false
        dragged = true       // suppress a tap when the fingers eventually lift
        pointerDown = false  // no hold-to-repeat mid-pinch
    }

    /** Both fingers moved: zoom by the change in their separation about the previous midpoint, then pan by
     *  the midpoint's movement. With no camera rotation this pins both fingers to their world points (the
     *  original rotated too; emerge's toroidal camera can't, so that DOF is dropped).
     *
     *  While a cell is camera-focused the pan is dropped and the zoom anchors on the cell instead, so pinching
     *  can't shake it loose — matching the desktop host, where the wheel zooms without clearing the focus and
     *  only a manual pan releases it. One-finger empty-space drag is still the way to let a cell go. */
    private fun onTwoFingerMove(mx: Float, my: Float, sp: Float) {
        val r = renderer
        if (!twoFinger || menu?.inGame != true || r == null) { prevMidX = mx; prevMidY = my; prevSpan = sp; return }
        val scale = if (prevSpan > 0f && sp > 0f) sp / prevSpan else 1f
        val focusPos = controller.cameraFocusPosition()
        if (focusPos != null) {
            // A cell is camera-focused: keep it focused — zoom about the cell (the follow re-centres it each
            // frame, so panning would just fight it) and leave the focus + selection untouched.
            val s = r.worldToScreen(focusPos.first, focusPos.second)
            r.zoomAtScreen(s[0], s[1], scale)
        } else {
            // Free camera: zoom about the midpoint and pan with it (pins both fingers to their world points).
            r.zoomAtScreen(prevMidX, prevMidY, scale)
            r.panByPixels(mx - prevMidX, my - prevMidY)
        }
        cameraMovedSignal = true
        prevMidX = mx; prevMidY = my; prevSpan = sp
    }

    /** A finger lifted: end the gesture and re-anchor single-finger panning at the finger that remains, so
     *  dropping to one finger doesn't jump the camera. */
    private fun endTwoFinger(x: Float, y: Float) {
        twoFinger = false
        prevSpan = 0f
        lastX = x; lastY = y
        dragged = true       // the remaining finger continues a pan, never a tap
        grabId = null
        uiConsumed = false
    }

    companion object {
        private const val DRAG_THRESHOLD_PX = 12f
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
        private const val DEFAULT_SPEED_IDX = 2   // 1x
    }
}
