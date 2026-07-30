package org.emerge.desktop
import org.emerge.demo.cyto.host.CampaignContent
import org.emerge.demo.cyto.host.CytoMenu

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.CytoSaveCodec
import org.emerge.demo.cyto.fixtureCell
import org.emerge.demo.cyto.loadFixture
import org.emerge.demo.cyto.campaign.CampaignDirector
import org.emerge.demo.cyto.campaign.CampaignQuery
import org.emerge.demo.cyto.campaign.Control
import org.emerge.demo.cyto.campaign.InputHints
import org.emerge.demo.cyto.campaign.PlayerAction
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoFixtures
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.demo.cyto.ui.CytoHud
import org.emerge.demo.cyto.host.CytoSnippets
import org.emerge.demo.cyto.ui.GeneEditor
import org.emerge.demo.cyto.ui.GeneSnippet
import org.emerge.render.torus.ui.Ui
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.TransformComponent
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * **Agent harness** — a headless, script-driven way to drive Cyto without an interactive window, so an
 * automated agent (or a CI check) can run the world, inject pointer input, step the campaign, and
 * capture what a player would actually see. It renders the **real** GL pipeline (the same [CytoRenderer]
 * + [CytoControls] + [Ui] the live host uses) into a *hidden* GLFW window and reads the framebuffer to a
 * PNG (the technique proven by `UIGallerySnapshot`) — so screenshots are faithful, including the real
 * bitmap font and widget layout. Requires a GL context (available on a workstation; a bare headless CI
 * container without one can still use the JSON `state` observation).
 *
 * Run: `./gradlew :apps:cyto:desktop:cytoAgent --args="<script> [outDir]"` (script = a file of commands,
 * or `-` for stdin). Commands (one per line, `#` comments, blank lines ignored):
 *
 * ```
 * scenario <name>            # new sandbox world from a preset (Genesis, Twin Colonies, ...)
 * campaign <chapterId>       # start a campaign chapter (e.g. ch01-first-contact)
 * fixture [name]            # load a named CytoFixtures world state (no name = list them)
 * run <ticks> | runs <sec>   # advance the sim
 * camera reset|zoom <f>|pan <dx> <dy>|follow <cellId>
 * tap <u> <v> | select <u> <v> | spawn <u> <v>   # pointer at normalised screen coords (0..1)
 *                            # `tap` runs the host's press pipeline: UI first, world only if unconsumed
 * clickcell <id> | dragcell <id> <u> <v> <ticks> # target a cell by entity id; drag spans <ticks> ticks
 * cells                      # list cells (id, type, biomass, selected, screen u,v)
 * elements                   # list the visible on-screen buttons (by label)
 * tap-ui <label> [@<n>]      # click a button by (partial, case-insensitive) label; @n picks the n-th
 *                            # match (a genome shows one ALWAYS / USE LIGHT per gene). Open dropdown rows
 *                            # and pick-sheet rows are both reachable; a sheet's scrim hides whatever
 *                            # is behind it from `elements`/`tap-ui`, as it does from a real click.
 * night <0..1>               # night-light dial; 1 flattens daylight out entirely (for reading matter)
 * matterlayer [tok|all]      # which species the matter ground draws; no argument lists what the world holds
 * next                       # click the coach "Next" (advances the chapter if its goal is met)
 * menu campaign [done,ids] # open the front-end shell on the campaign MAP with that progress
 *                            # (`menu title` opens the title screen, `menu off` closes it);
 *                            # tap-ui a chapter to start it
 * dragto <src> >> <dst>      # pick a card up and drop it on a target (long-press on narrow; autoscrolls
 *                            # the list when the target starts off-screen). draghover holds instead.
 * scroll <areaId> <dp>       # scroll a list (e.g. cell-sheet) so off-screen rows become reachable
 * tap-key <key>              # click a gene slot by IDENTITY, not by the word on it (GeneKeys):
 *                            # `tap-key gene:CONVERT:1:cond` is "the condition on the first GROW gene",
 *                            # whether it currently reads ALWAYS or WHEN BIO < 3000. `elements` prints the
 *                            # key beside each label. The campaign coach points at these same keys.
 * expect-ui <label>          # assert a (partial) label is on screen; !<label> asserts it is not
 * expect <field> <value>     # assert a reading (chapter/step/goal/cells/genes/convertChem/
 *                            # growthCap/divideFloor/hasDivide/recyclesExhaust/recycleReserve/
 *                            # bond/fuelConflicts); non-zero exit if any fail
 * shot <name> | state [name] | echo <text>
 * ```
 *
 * **Clock control.** The sim only advances when told to — there is no background stepper and no wall-clock
 * coupling. `run N` / `runs S` advance exactly N (or S*64) ticks. `dragcell` advances the ticks you give it.
 * `tap` and `spawn` advance exactly **1** tick (the minimum to apply their buffered pointer input, as a real
 * click does). Everything else (`select`/`clickcell`/`camera`/`overlay`/`next`/`shot`/`state`/`cells`/
 * `elements`/`echo`) advances **0** ticks — the world is frozen between commands.
 */
object CytoAgentHarness {

    // Render size. Overridable (-Dcyto.agent.w / -Dcyto.agent.h) so the harness can reproduce a phone's
    // framebuffer — the UI kit lays out in raw pixels, so geometry only tells the truth at the real size.
    private val RES_W = System.getProperty("cyto.agent.w")?.toIntOrNull() ?: 1200
    private val RES_H = System.getProperty("cyto.agent.h")?.toIntOrNull() ?: 900
    // dp -> px for the UI kit (-Dcyto.agent.density). 1.0 = desktop; ~2.625 reproduces a 420dpi phone.
    private val DENSITY = System.getProperty("cyto.agent.density")?.toFloatOrNull() ?: 1f
    // Narrow/phone layout: an open gene renders as the full-screen L3 modal (UI_REDESIGN.md §3).
    private val NARROW = System.getProperty("cyto.agent.narrow")?.toBoolean() ?: false
    // Which input phrasing the coach copy renders with. Defaults to MOUSE (emulating the desktop host);
    // -Dcyto.agent.touch verifies the phone wording headlessly.
    private val TOUCH = System.getProperty("cyto.agent.touch")?.toBoolean() ?: false
    // Comfortably past the UI kit's pickup threshold, so a scripted long press is never a near-miss.
    private const val LONG_PRESS_SECONDS = 0.5f
    // Autoscrolling a held card to an off-screen drop target: enough rounds to cross a long genome.
    private const val AUTOSCROLL_ROUNDS = 40
    private const val AUTOSCROLL_STEP_SECONDS = 0.1f

    /** UI animation clock step per built frame. A whole second, so every fade has finished and every pulse is
     *  back at the same phase by the time anything is measured or captured. */
    private const val UI_CLOCK_STEP = 1.0f

    fun run(scriptText: String, outDir: File) {
        outDir.mkdirs()
        val bad = CampaignContent.validateGlyphs()
        if (bad.isNotEmpty()) println("[agent] WARNING: campaign copy has unsupported glyphs (render as '?'): $bad")
        val h = Session(outDir)
        h.init()
        try {
            for (raw in scriptText.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                runCatching { h.exec(line) }.onFailure { println("! error on '$line': ${it.message}") }
            }
        } finally {
            h.cleanup()
        }
        println("[agent] done -> ${outDir.absolutePath}")
        if (h.failures.isNotEmpty()) {
            println("[agent] ${h.failures.size} EXPECT failure(s):")
            for (f in h.failures) println("[agent]   - $f")
            throw IllegalStateException("${h.failures.size} expectation(s) failed")
        }
    }

    private class Session(val outDir: File) {
        val controller = CytoController()
        val director = CampaignDirector().apply { inputHints = if (TOUCH) InputHints.TOUCH else InputHints.MOUSE }

        private var window: Long = NULL
        private lateinit var renderer: CytoRenderer
        private lateinit var controls: CytoControls
        private lateinit var ui: Ui
        private lateinit var geneEditor: GeneEditor
        private val hud = CytoHud()
        private val pendingActions = HashSet<PlayerAction>()

        /**
         * The front-end shell, when a script has opened it (`menu campaign`) — null the rest of the time,
         * which is the in-game case every other command assumes.
         *
         * Worth having because the campaign MAP lives here: it is the one screen whose whole content is
         * derived from progress, so it cannot be reviewed by playing (you would have to own the right save)
         * and could not be seen headlessly at all. [menuCompleted] stands in for the on-disk progress file so
         * a script can state the progress it wants to look at.
         */
        private var menu: CytoMenu? = null
        private val menuCompleted = HashSet<String>()

        /** Failed `expect`s, reported (and exited on) at the end of the run. */
        val failures = ArrayList<String>()

        fun init() {
            director.onStepEnter = {}
            // Continuous campaign: `next` past a chapter's last step segues into the next chapter in-world,
            // and `reset` reloads the current chapter — same as the real hosts.
            director.chapters = CampaignContent.PLAYABLE_CHAPTERS   // match the real hosts: scratch chapters segue too
            director.onWorldReset = { ch -> controller.newGame(ch.scenario); renderer.resetView() }
            // Deliberately NO onRestoreEntryState here: the harness never writes entry states (so scripted
            // runs stay reproducible), and reading the real game's would make a run depend on how Stu last
            // played. "Restart" falls back to the clean reset, which is what this tool wants anyway.
            // ...then put the player's OWN lineage back, under the middle of the camera. Only fires for a
            // chapter that seeds no founders of its own (see CampaignDirector.resetChapter).
            director.onReseedLineage = { ch, genome ->
                val c = renderer.cameraCentreWorld()
                controller.reseedLineage(genome, c[0], c[1], ch.spawnBiomass, ch.spawnCytoplasm)
            }
            director.onCampaignComplete = { println("[agent] campaign complete") }
            if (!glfwInit()) error("GLFW init failed")
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)
            window = glfwCreateWindow(RES_W, RES_H, "cyto-agent", NULL, NULL)
            if (window == NULL) error("failed to create hidden GLFW window (no GL context available?)")
            glfwMakeContextCurrent(window)
            org.lwjgl.opengl.GL.createCapabilities()

            renderer = CytoRenderer()
            controls = CytoControls()
            controls.onListMatterSpecies = { controller.matterLayers() }
            ui = Ui()
            geneEditor = GeneEditor()
            renderer.setResolution(RES_W.toFloat(), RES_H.toFloat())
            controls.setResolution(RES_W.toFloat(), RES_H.toFloat())
            ui.setResolution(RES_W.toFloat(), RES_H.toFloat())
            ui.setDensity(DENSITY)
            controls.showSimSpeed = true
            controls.showMutation = true
            // Speed buttons have no sim-driver here, but raise the campaign signal so `tap-ui FAST` drives
            // a ChangedSpeed gate faithfully.
            controls.onSlower = { pendingActions.add(PlayerAction.ChangedSpeed) }
            controls.onFaster = { pendingActions.add(PlayerAction.ChangedSpeed) }
            controls.onTogglePause = { pendingActions.add(PlayerAction.ChangedSpeed) }
            // No sim-driver here, so seed the speed-cluster display so it renders for screenshots; the
            // `simspeed` command overrides these to exercise the disabled/paused visuals.
            controls.simTps = 256
            controls.simStatus = "256/256 TPS   60 FPS"
        }

        fun cleanup() {
            if (window != NULL) {
                runCatching { renderer.cleanup(); controls.cleanup(); ui.cleanup() }
                glfwDestroyWindow(window)
            }
            glfwTerminate()
        }

        fun exec(line: String) {
            val t = line.split(Regex("\\s+"))
            when (t[0]) {
                "scenario" -> {
                    // Preset names can be multi-word ("Twin Colonies"); take the rest of the line, minus
                    // any surrounding quotes, rather than just the first whitespace token.
                    val name = line.removePrefix("scenario").trim().trim('"').ifEmpty { "Genesis" }
                    director.stop(); controller.newGame(preset(name)); renderer.resetView()
                }
                "load" -> {
                    val path = line.removePrefix("load").trim().trim('"')
                    val bytes = File(path).readBytes()
                    controller.restoreSnapshot(bytes)
                    renderer.resetView()
                    println("[agent] loaded $path, tick ${controller.tick}, cells ${controller.worldStats().cellCount}")
                }
                // Load a named world state from CytoFixtures — the same states the tests assert against, so
                // "why does the test say that?" is answerable by looking at the thing. `fixture` with no
                // name lists what's available. The named cell is selected, so the panel is already on it.
                "fixture" -> {
                    val name = t.getOrNull(1)
                    if (name == null) {
                        println("[agent] fixtures: ${CytoFixtures.BY_NAME.keys.joinToString(", ")}")
                    } else {
                        val make = CytoFixtures.BY_NAME[name]
                            ?: error("unknown fixture '$name' (have ${CytoFixtures.BY_NAME.keys})")
                        director.stop()
                        val f = make()
                        controller.loadFixture(f)
                        f.names.firstOrNull()?.let { controller.focus(controller.fixtureCell(f, it)) }
                        controller.publish(); renderer.resetView()
                        println("[agent] fixture '$name' loaded: cells ${f.names.joinToString(", ")}")
                    }
                }
                "campaign" -> {
                    val ch = (CampaignContent.CHAPTERS + CampaignContent.SCRATCH_CHAPTERS).firstOrNull { it.id == t.getOrNull(1) }
                        ?: error("unknown chapter '${t.getOrNull(1)}' (have ${CampaignContent.ORDER} + ${CampaignContent.SCRATCH_CHAPTERS.map { it.id }})")
                    controller.newGame(ch.scenario); director.start(ch, controller); renderer.resetView(); applyTouchModeDefault()
                }
                // Author a genome ONTO the selected cell, the way the player's gene editor does — through
                // CytoController.addHeldGenes, so it counts as an edit and updates `lastAuthoredGenome`.
                // The harness cannot drive the editor's pick sheets (synthetic taps don't reach popovers),
                // so this is how a campaign gate that keys on what the player BUILT gets exercised headlessly.
                // Delete the cell under a normalised screen point — the brush's Delete mode, which the agent
                // has no other way to reach. Used to drive a lineage to extinction on purpose.
                "kill" -> {
                    val (x, y) = world(t[1].toFloat(), t[2].toFloat())
                    controller.tap(x, y, TouchMode.Delete, CellType.Stem); advance(1)
                }
                "authorgenome" -> {
                    val path = line.removePrefix("authorgenome").trim().trim('"')
                    val genes = GeneCodec.parse(File(path).readText())
                    val held = controller.heldGenome()
                    if (held == null) println("[agent] authorgenome: no cell selected") else {
                        repeat(held.size) { controller.deleteHeldGene(0); controller.publish() }
                        controller.addHeldGenes(genes)
                        controller.publish()
                        println("[agent] authored ${genes.size} genes onto the selected cell")
                    }
                }
                "genome" -> {
                    // Spawn a single founder from a .gene file — for testing hand-authored / campaign-stage
                    // genomes (viability, differentiation, locomotion). Push it with `dragcell` to bootstrap.
                    val path = line.removePrefix("genome").trim().trim('"')
                    val text = File(path).readText()
                    val genes = GeneCodec.parse(text)
                    // Carry the file's `# alias` headers into the scenario too, so a probe genome displays
                    // its chemical names the way the curated campaign genomes do.
                    val scn = CytoScenario.DEFAULT.copy(
                        name = "Probe",
                        founders = listOf(FounderSpec(CellType.Collector, 1, genome = genes)),
                        aliases = GeneCodec.parseAliases(text),
                    )
                    director.stop(); controller.newGame(scn); renderer.resetView()
                    println("[agent] genome loaded: ${genes.size} genes, ${scn.aliases.size} aliases from $path")
                }
                "run" -> advance(t[1].toInt())
                "runs" -> advance((t[1].toFloat() * 64f).toInt())
                "camera" -> {
                    when (t[1]) {
                        "reset" -> renderer.resetView()
                        "zoom" -> renderer.zoomAtScreen(RES_W / 2f, RES_H / 2f, t[2].toFloat())
                        "pan" -> renderer.panByPixels(t[2].toFloat(), t[3].toFloat())
                        "follow" -> controller.agentCells().firstOrNull { it.id == t[2].toInt() }
                            ?.let { renderer.follow(it.id, it.x, it.y) }
                        else -> error("camera reset|zoom|pan|follow")
                    }
                    pendingActions.add(PlayerAction.MovedCamera)
                }
                // Route the world through the period render target and tile it (torus repeats when zoomed out).
                "tile" -> {
                    renderer.tileWorld = t[1].equals("on", ignoreCase = true)
                    println("[agent] tile -> ${if (renderer.tileWorld) "on" else "off"}")
                }
                "tap" -> tapAt(t[1].toFloat(), t[2].toFloat())
                "select" -> {
                    val (x, y) = world(t[1].toFloat(), t[2].toFloat())
                    controller.cellAt(x, y)?.let { controller.focus(it); pendingActions.add(PlayerAction.SelectedCell) }
                }
                // Right-click equivalent: give the cell under (u,v) camera focus (or release it on empty space).
                "camerafocus" -> {
                    val (x, y) = world(t[1].toFloat(), t[2].toFloat())
                    val hit = controller.cellAt(x, y)
                    if (hit != null) controller.cameraFocus(hit) else controller.clearCameraFocus()
                    pendingActions.add(PlayerAction.MovedCamera)
                }
                "spawn" -> {
                    val (x, y) = world(t[1].toFloat(), t[2].toFloat())
                    controller.spawn(x, y, CellType.Collector); advance(1); pendingActions.add(PlayerAction.PaintedCell)
                }
                "clickcell" -> { controller.focus(EntityId(t[1].toInt())); pendingActions.add(PlayerAction.SelectedCell); sync() }
                "dragcell", "stickycell" -> {
                    val sticky = t[0] == "stickycell"
                    val id = EntityId(t[1].toInt()); val (x, y) = world(t[2].toFloat(), t[3].toFloat())
                    val ticks = t.getOrNull(4)?.toIntOrNull() ?: error("${t[0]} <id> <u> <v> <ticks> (explicit duration)")
                    repeat(ticks.coerceAtLeast(1)) { controller.grab(id, x, y, sticky); controller.stepOnce() }
                    controller.releaseGrab(); pendingActions.add(PlayerAction.MovedCell); controller.publish(); sync()
                }
                "save" -> {
                    val path = line.removePrefix("save").trim().trim('"')
                    controller.publish()
                    File(path).writeBytes(CytoSaveCodec.encode(controller.latestFrame().state))
                    println("[agent] saved -> $path (${controller.worldStats().cellCount} cells, tick ${controller.tick})")
                }
                "cells" -> listCells()
                "com" -> {
                    // Mass-weighted centre of mass (world logical coords) + count — for measuring locomotion
                    // drift of a welded body over a run (sample `com`, diff the x/y across ticks).
                    val cs = controller.agentCells()
                    if (cs.isEmpty()) { println("[agent] com: no cells") } else {
                        var mt = 0.0; var cx = 0.0; var cy = 0.0
                        for (c in cs) { val m = c.biomass.toDouble(); mt += m; cx += m * c.x; cy += m * c.y }
                        println("[agent] com tick=${controller.tick} cells=${cs.size} x=${"%.4f".format(cx / mt)} y=${"%.4f".format(cy / mt)}")
                    }
                }
                "elements" -> listElements()
                // Scroll a list by dp. A phone's cell sheet holds about two gene cards, so most of a genome
                // is off-screen — and a row outside its viewport emits nothing, so it is unreachable by
                // `tap-ui`/`expect-ui` exactly as it is by a finger until the list is moved.
                "scroll" -> {
                    buildOverlay()
                    ui.scrollBy(t[1], t[2].toFloat() * DENSITY)
                    println("[agent] scroll '${t[1]}' by ${t[2]}dp")
                }
                // Type into whichever gene-editor field currently has keyboard focus, routed exactly as
                // CytoSceneView routes the real char-callback — so a script exercises the same path a player
                // does. Without this, keyboard-driven UI is the one thing the harness can't reach.
                "type" -> {
                    val text = line.removePrefix("type").trim()
                    for (c in text) when {
                        geneEditor.capturingSpeciesOperand -> geneEditor.typeSpeciesChar(c)
                        geneEditor.capturingGroupName -> geneEditor.typeGroupChar(c)
                        geneEditor.capturingConstantValue -> geneEditor.typeConstantChar(c)
                    }
                    println("[agent] typed '$text' (species=${geneEditor.capturingSpeciesOperand})")
                }
                // Editing keys for the focused field: `key backspace` / `key enter` / `key esc`.
                "key" -> {
                    when (t.getOrNull(1)?.lowercase()) {
                        "backspace" -> when {
                            geneEditor.capturingSpeciesOperand -> geneEditor.speciesBackspace()
                            geneEditor.capturingGroupName -> geneEditor.groupBackspace()
                            geneEditor.capturingConstantValue -> geneEditor.constantBackspace()
                        }
                        "enter" -> when {
                            geneEditor.capturingSpeciesOperand -> geneEditor.blurSpeciesOperand()
                            geneEditor.capturingGroupName -> geneEditor.confirmGroupName()
                            geneEditor.capturingConstantValue -> geneEditor.confirmConstantValue()
                        }
                        "esc" -> when {
                            geneEditor.capturingSpeciesOperand -> geneEditor.blurSpeciesOperand()
                            geneEditor.capturingGroupName -> geneEditor.cancelGroupName()
                            geneEditor.capturingConstantValue -> geneEditor.cancelConstantValue()
                        }
                        else -> println("[agent] unknown key: ${t.getOrNull(1)}")
                    }
                    println("[agent] key ${t.getOrNull(1)}")
                }
                "tap-ui" -> tapUi(line.removePrefix("tap-ui").trim())
                // Drive a gene slot by IDENTITY rather than by the word on it — the same key the campaign
                // coach points at (GeneKeys), so "what the coach rings" and "what a script taps" stay the
                // same thing now that the coach no longer matches on displayed text.
                "tap-key" -> tapKey(line.removePrefix("tap-key").trim())
                "hover-ui" -> hoverUi(line.removePrefix("hover-ui").trim())
                "hover-clear" -> { ui.clearHover(); println("[agent] hover cleared") }
                "drag-ui" -> dragUi(t[1], t[2].toFloat())
                "dragto" -> dragToUi(line.removePrefix("dragto").trim())
                "draghover" -> dragHoverUi(line.removePrefix("draghover").trim())
                // The matter ground and the daylight multiply are both always on now; what's left to vary is
                // how dark night gets. `night 1` flattens the light out entirely (handy for reading matter).
                // `matterlayer <token|all>` — the LAYERS sheet's MATTER rows, reachable without driving the
                // sheet. With no argument, lists what the world actually holds (id, name, total).
                // Print the selected cell's genome as `.gene` text. The readback for group inserts: an
                // adapted subsystem is only correct if it names the chemistry the PLAYER runs on, and that is
                // invisible in every other observation the harness offers.
                "genes" -> {
                    val g = controller.heldGenome()
                    if (g == null) println("[agent] genes: no cell selected")
                    else println("[agent] genome (${g.size}):\n${GeneCodec.serialize(g).trim()}")
                }
                "matterlayer" -> {
                    val arg = line.removePrefix("matterlayer").trim()
                    val layers = controller.matterLayers()
                    when {
                        arg.isEmpty() ->
                            println("[agent] matter layers: ${layers.joinToString(", ") { (id, n, t) -> "$id '$n' $t" }}")
                        arg.equals("all", ignoreCase = true) -> {
                            controls.setMatterSpecies(CytoControls.ALL_MATTER)
                            println("[agent] matter layer -> ALL")
                        }
                        else -> {
                            val id = SpeciesRegistry.id(arg)
                            require(id >= 0) { "unknown species '$arg' (world holds ${layers.map { it.second }})" }
                            controls.setMatterSpecies(id)
                            println("[agent] matter layer -> '$arg' (id $id)")
                        }
                    }
                }
                "night" -> {
                    renderer.nightLevel = t[1].toFloat()
                    println("[agent] night level -> ${renderer.nightLevel}")
                }
                // Set the speed-cluster display for screenshots: `simspeed <tps> <slow0|1> <fast0|1> <paused0|1>`.
                "simspeed" -> {
                    controls.simTps = t.getOrElse(1) { "256" }.toInt()
                    controls.slowEnabled = t.getOrElse(2) { "1" } == "1"
                    controls.fastEnabled = t.getOrElse(3) { "1" } == "1"
                    controls.simPaused = t.getOrElse(4) { "0" } == "1"
                    controls.simStatus = "${controls.simTps}/${controls.simTps} TPS   60 FPS"
                    println("[agent] simspeed -> ${controls.simTps} slow=${controls.slowEnabled} fast=${controls.fastEnabled} paused=${controls.simPaused}")
                }
                "did" -> { pendingActions.add(PlayerAction.valueOf(t[1])); sync() }
                "next" -> { sync(); println("[agent] next -> ${if (director.tryAdvance(controller)) "advanced" else "blocked (goal not met)"}") }
                "reset" -> { director.resetChapter(controller); sync(); println("[agent] reset -> ${director.snapshot()?.chapterId} step ${(director.snapshot()?.stepIndex ?: 0) + 1}") }
                // `menu campaign [done-id,done-id...]` opens the front-end shell on the campaign map, with the
                // named chapters marked complete; `menu off` closes it. While it is open the shell owns the
                // screen, exactly as in the real hosts, so `shot`/`tap-ui`/`elements` all see the map.
                "menu" -> openMenu(t.getOrElse(1) { "campaign" }, t.getOrElse(2) { "" })
                "shot" -> { sync(); shot(t.getOrElse(1) { "shot" }) }
                "state" -> { sync(); dumpState(t.getOrElse(1) { "state" }) }
                // sync() first, as `shot` and `state` do: the director re-evaluates its gate there, so an
                // `expect` straight after a step transition would otherwise read the gate state of the step
                // BEFORE it — reporting a goal as unmet purely because nothing had asked yet.
                "expect" -> { sync(); expect(line.removePrefix("expect").trim()) }
                "expect-ui" -> { sync(); expectUi(line.removePrefix("expect-ui").trim()) }
                "dumpraw" -> dumpRaw()
                "echo" -> println("[agent] ${line.removePrefix("echo").trim()}")
                else -> error("unknown command '${t[0]}'")
            }
        }

        /** `menu <campaign|off> [completed,ids]` — see the dispatch comment. */
        private fun openMenu(page: String, completed: String) {
            if (page == "off") { menu = null; println("[agent] menu closed"); return }
            menuCompleted.clear()
            menuCompleted.addAll(completed.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            menu = CytoMenu().apply {
                campaignChapters = CampaignContent.PLAYABLE_CHAPTERS
                campaignCompleted = { it in menuCompleted }
                if (page == "title") openTitle() else openCampaign()
            }
            println("[agent] menu -> $page, completed=${menuCompleted.sorted()}")
        }

        /** The front-end shell's callbacks. Starting a chapter closes the menu and hands over to the
         *  director, so a script can walk in through the map the way a player does. */
        private fun menuCallbacks() = CytoMenu.Callbacks(
            onStart = {}, onContinue = { menu = null }, onLoadNamed = {}, onOpenSave = {},
            onSave = {}, onDelete = {}, onSaveGenome = { _, _, _ -> },
            onStartChapter = { ch ->
                menu = null
                controller.newGame(ch.scenario); director.start(ch, controller); renderer.resetView(); applyTouchModeDefault()
                println("[agent] menu started chapter ${ch.id}")
            },
            onQuit = {},
        )

        private fun advance(ticks: Int) {
            repeat(ticks.coerceAtLeast(0)) { controller.stepOnce() }
            controller.publish(); sync()
        }

        /** Mirror the host: entering a chapter picks the brush action once (CampaignDirector). Without this
         *  a scripted `tap` would use the sandbox default and never exercise what a campaign player does. */
        private fun applyTouchModeDefault() {
            director.consumeDefaultTouchMode()?.let { controls.setTouchMode(it) }
            controls.worldTapsEnabled = director.active
        }

        private fun sync() {
            applyTouchModeDefault()
            if (!director.active) { pendingActions.clear(); return }
            applyChapterSpawn()
            val q = CampaignQuery(
                controller.worldStats(), paused = false, selectedGenome = null,
                chemistryOpen = geneEditor.chemistryOpen,
            )
            director.update(q, pendingActions.toSet()); pendingActions.clear()
        }

        /** Mirror the host: inside a chapter the brush + biomass follow it on EVERY step, so a `tap`/`spawn`
         *  drops (or re-genomes with) the chapter's authored cell — e.g. the gene-less 2000-r/g/b starter.
         *  Called in [sync] and right before a spawn action, since a `next` advances the step after its own
         *  sync. */
        private fun applyChapterSpawn() {
            val ch = director.activeChapter ?: return
            controller.brushGenome = director.brushGenome(controller)
            controller.spawnBiomass = ch.spawnBiomass
            controller.spawnCytoplasm = ch.spawnCytoplasm
        }

        /**
         * A press at normalised screen point ([u], [v]) — **the whole host press pipeline**, UI first.
         *
         * `CytoSceneView` consults `ui.hitTestDown` and then `controls.hitTest` before it touches the world,
         * and a hit consumes the press. This did neither: it converted straight to world coordinates and
         * called `controller.tap`, so a coordinate tap on ANY widget fell through and acted on the world
         * behind it — tapping the action menu's CONVERT row spawned a cell instead of picking an action,
         * which read as "synthetic taps can't reach popovers" when the popover was never consulted.
         */
        private fun tapAt(u: Float, v: Float) {
            val px = u * RES_W
            val py = v * RES_H
            buildOverlay()   // the regions have to exist (and be current) before they can be hit
            if (ui.hitTestDown(px, py) || controls.hitTest(px, py)) {
                ui.hitTestUp(px, py)   // a click is down+up; steppers and toggles fire on one or the other
                buildOverlay()
                sync()
                println("[agent] tap ($u, $v) -> consumed by the UI")
                return
            }
            // Past the UI: a press outside it dismisses any open picker, exactly as the host does.
            geneEditor.closeDropdown()
            val (x, y) = world(u, v)
            tapWorld(x, y)
        }

        private fun tapWorld(x: Float, y: Float) {
            // Brush FIRST, then focus. The host computes the brush once a frame, so a press resolves against
            // the selection as it stood *before* the tap; focusing first would let a `spawnCopiesHeldCell`
            // chapter re-point the brush at the very cell being tapped, which then adopts its own genome.
            applyChapterSpawn()   // the current step may spawn the chapter's authored cell (brush + biomass)
            controller.cellAt(x, y)?.let { controller.focus(it); pendingActions.add(PlayerAction.SelectedCell) }
            // The host taps with whatever brush action is selected, so the harness must too: on empty space
            // every mode spawns alike, but on a CELL the mode is the whole behaviour (Base selects and does
            // nothing; Set re-genomes it from the brush). Gated as the hosts gate it — outside a chapter the
            // brush palette permits painting, inside one every step does — so a script is under the same rule
            // a player is. Selection above happens either way, exactly as in the host.
            if (controls.showBrush || controls.worldTapsEnabled) {
                controller.tap(x, y, controls.touchMode, CellType.Stem)
            }
            advance(1)
        }

        // ── coordinate mapping via the REAL renderer camera (so it matches the game) ──────────────
        private fun world(u: Float, v: Float): Pair<Float, Float> {
            val w = renderer.screenToWorld(u * RES_W, v * RES_H); return w[0] to w[1]
        }

        // ── observation ──────────────────────────────────────────────────────────────
        private fun listCells() {
            val sb = StringBuilder("[\n")
            for (c in controller.agentCells().sortedBy { it.id }) {
                val s = renderer.worldToScreen(c.x, c.y)
                sb.append("  {\"id\": ${c.id}, \"type\": \"${c.type.name}\", \"biomass\": ${c.biomass}, ")
                sb.append("\"radius\": ${"%.3f".format(c.radius)}, ")
                sb.append("\"selected\": ${c.selected}, \"u\": ${"%.3f".format(s[0] / RES_W)}, \"v\": ${"%.3f".format(s[1] / RES_H)}}\n")
            }
            sb.append("]")
            println(sb); File(outDir, "cells.json").writeText(sb.toString())
        }

        private fun listElements() {
            buildOverlay()
            val uiBtns = ui.elements().map { it.label }
            controls.rebuild()
            val ctlBtns = controls.elements()
            println("[agent] render size: ${RES_W}x$RES_H  density: $DENSITY")
            println("[agent] coach/panel buttons: $uiBtns")
            println("[agent] control buttons: $ctlBtns")
            // A live drag changes what the panel offers (drop zones replace the create buttons), so say when
            // one is in flight — otherwise a mid-drag listing looks identical to a dropped one.
            ui.draggingId?.let { println("[agent] dragging: '$it' over drop target '${ui.hoveredDropTarget()}'") }
            // Geometry, so a touch-target audit can see how big these actually are at this render size.
            for (e in ui.elements())
                println("[agent]   ui '${e.label}'${e.key?.let { " key=$it" } ?: ""} x=${e.x.toInt()} y=${e.y.toInt()} w=${e.w.toInt()} h=${e.h.toInt()}")
            // Keyed readouts only: every line a panel prints is a readout, so listing them all buries the
            // listing in coach prose. The keyed ones are the ones anything can point at (a chemistry number).
            for (r in ui.readouts().filter { it.key != null })
                println("[agent]   readout '${r.label}' key=${r.key} x=${r.x.toInt()} y=${r.y.toInt()} w=${r.w.toInt()} h=${r.h.toInt()}")
        }

        private fun dumpRaw() {
            val frame = controller.latestFrame().state
            val cells = frame.components.getTable<CytoCellComponent>().asMap()
            val xforms = frame.components.getTable<TransformComponent>()
            val sb = StringBuilder("[\n")
            for ((id, cell) in cells) {
                val t = xforms[id]
                sb.append("  {\n")
                sb.append("    \"id\": ${id.value}, \"type\": \"${cell.type.name}\", ")
                sb.append("\"biomass\": ${totalBiomass(cell.biomass)}, ")
                sb.append("\"pos\": ${t?.let { "${"%.3f".format(CytoUnits.toLogical(t.pos.x))},${"%.3f".format(CytoUnits.toLogical(t.pos.y))}" } ?: "null"},\n")
                sb.append("    \"cytoplasm\": {")
                sb.append(cell.cytoplasm.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" })
                sb.append("},\n")
                sb.append("    \"genes\": [\n")
                for ((idx, gene) in cell.genome.withIndex()) {
                    sb.append("      {\"idx\": $idx, \"src\": \"${gene.source}\", \"cond\": \"${gene.condition}\", \"act\": \"${gene.action}\", \"eff\": ${gene.efficiency}}")
                    if (idx < cell.genome.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("    ]\n")
                sb.append("  }")
                if (id != cells.keys.last()) sb.append(",")
                sb.append("\n")
            }
            sb.append("]")
            println(sb)
            File(outDir, "dumpraw.json").writeText(sb.toString())
        }

        private fun totalBiomass(biomass: Map<String, Int>): Int = biomass.values.sum()

        /** Simulate a vertical drag on a labelled region ([dyPx] > 0 down): press at its centre, feed
         *  incremental moves through the toolkit's real drag path, then release (which snaps a sheet detent). */
        private fun dragUi(label: String, dyPx: Float) {
            buildOverlay()
            val el = ui.elements().firstOrNull { it.label.contains(label, ignoreCase = true) }
            if (el == null) { println("[agent] drag-ui '$label' -> no match"); return }
            val cx = el.x + el.w * 0.5f; val cy = el.y + el.h * 0.5f
            ui.hitTestDown(cx, cy)
            val steps = 10
            for (i in 1..steps) ui.dragTo(cx, cy + dyPx * i / steps)
            ui.hitTestUp(cx, cy + dyPx)
            println("[agent] drag-ui '$label' dy=$dyPx -> done")
            sync()
        }

        /** Simulate a **drag-and-drop**: `dragto <src> >> <dst>`. Press at the source label's centre, commit
         *  the drag (moving past the toolkit's slop), then rebuild the overlay so drag-only drop targets (the
         *  "+ NEW GROUP" placeholder) exist, locate the destination label, move onto it, and release — the
         *  real toolkit path a mouse drag would drive. The source can be any label inside the dragged card
         *  (e.g. a token like "MITOSIS"), since the whole card is the drag source. */
        private fun dragToUi(arg: String) {
            val parts = arg.split(">>").map { it.trim() }
            if (parts.size != 2) { println("[agent] usage: dragto <src> >> <dst>"); return }
            buildOverlay()
            val src = ui.elements().firstOrNull { it.label.contains(parts[0], ignoreCase = true) }
            if (src == null) { println("[agent] dragto src '${parts[0]}' -> no match"); return }
            val sx = src.x + src.w * 0.5f; val sy = src.y + src.h * 0.5f
            pickUp(sx, sy)
            val dst = reachDropTarget(parts[1])
            if (dst == null) { println("[agent] dragto dst '${parts[1]}' -> no match"); ui.hitTestUp(sx, sy); return }
            val dx = dst.x + dst.w * 0.5f; val dy = dst.y + dst.h * 0.5f
            ui.dragTo(dx, dy)
            ui.hitTestUp(dx, dy)
            // The drop set an inline edit; that flushes to a queued world edit inside render(), so rebuild once
            // to run the flush, then publish to apply it (same ordering as tapUi). See pendingWorldEdits.
            buildOverlay()
            controller.publish()
            println("[agent] dragto '${parts[0]}' >> '${parts[1]}' -> dropped")
            sync()
        }

        /**
         * Locate a drop target by (partial) label while a drag is live, **scrolling the list to it** if it
         * starts off-screen.
         *
         * Rows outside their viewport are culled before they emit anything, so on a phone-sized screen half
         * the genome's targets simply do not exist to look at — the same bind a player is in, and the reason
         * the toolkit autoscrolls a list while a card is held over its edge. Driving that here (hold near the
         * bottom edge, rebuild, look again) is what makes those targets reachable in a script at all.
         */
        private fun reachDropTarget(label: String): Ui.UiElement? {
            // Rebuild first: activeDrag persists across frames, so this pass sees draggingId set and registers
            // the drag-only targets (the new-group placeholder + highlightable group headers).
            buildOverlay()
            fun find() = (ui.elements() + ui.dropTargetElements()).firstOrNull { it.label.contains(label, ignoreCase = true) }
            find()?.let { return it }
            val px = RES_W * 0.5f
            val py = RES_H - 2f      // inside the sheet, hard against its bottom edge, where autoscroll is fastest
            repeat(AUTOSCROLL_ROUNDS) {
                ui.dragTo(px, py)
                ui.updateHold(px, py, AUTOSCROLL_STEP_SECONDS)
                buildOverlay()
                find()?.let { return it }
            }
            return null
        }

        /**
         * Press at [sx],[sy] and commit a card drag there — the gesture both drag commands start with.
         *
         * Which gesture that is depends on the layout, exactly as it does for a player: on a wide screen a
         * single move past the slop takes the card, while the narrow sheet requires a **long press** (a
         * scroll-vs-drag disambiguation it needs because the list is made of draggable cards). Getting this
         * wrong is silent — a narrow drag driven the wide way just scrolls the genome and reports nothing.
         */
        private fun pickUp(sx: Float, sy: Float) {
            ui.hitTestDown(sx, sy)
            if (ui.longPressDrag) ui.updateHold(sx, sy, LONG_PRESS_SECONDS) else ui.dragTo(sx, sy + 30f)
            // A pickup that didn't take is the failure mode worth naming: every later step still "succeeds"
            // against a card that was never in flight, and the script passes while testing nothing.
            if (ui.draggingId == null) println("[agent] WARNING: nothing picked up at ($sx, $sy) — not a drag source?")
        }

        /** Like [dragToUi] but **holds** the drag over the destination without releasing, so a following
         *  `shot` captures the mid-drag visuals (the floating ghost + the highlighted drop target). Leaves the
         *  drag live — intended as the last gesture before a shot in a throwaway script. */
        private fun dragHoverUi(arg: String) {
            val parts = arg.split(">>").map { it.trim() }
            if (parts.size != 2) { println("[agent] usage: draghover <src> >> <dst>"); return }
            buildOverlay()
            val src = ui.elements().firstOrNull { it.label.contains(parts[0], ignoreCase = true) }
            if (src == null) { println("[agent] draghover src '${parts[0]}' -> no match"); return }
            pickUp(src.x + src.w * 0.5f, src.y + src.h * 0.5f)
            val dst = reachDropTarget(parts[1])
            if (dst == null) { println("[agent] draghover dst '${parts[1]}' -> no match"); return }
            ui.dragTo(dst.x + dst.w * 0.5f, dst.y + dst.h * 0.5f)
            println("[agent] draghover '${parts[0]}' >> '${parts[1]}' -> holding (shot to capture)")
        }

        /** Park the persistent hover cursor over a labelled region's centre so a following `shot` captures
         *  hover-revealed affordances (clause +/X, the gene card overflow button). */
        private fun hoverUi(label: String) {
            buildOverlay()
            val el = ui.elements().firstOrNull { it.label.contains(label, ignoreCase = true) }
            if (el == null) { println("[agent] hover-ui '$label' -> no match"); return }
            ui.hover(el.x + el.w * 0.5f, el.y + el.h * 0.5f)
            println("[agent] hover-ui '$label' -> (${(el.x + el.w * 0.5f).toInt()}, ${(el.y + el.h * 0.5f).toInt()})")
        }

        /**
         * `tap-ui <label>`, or `tap-ui <label> @<n>` for the n-th match — a genome shows one `ALWAYS` and one
         * `USE LIGHT` per gene, so the second gene's source is reachable only by index.
         *
         * `@`, not `#`: scripts strip `#` to end-of-line as a comment, so `tap-ui USE LIGHT #2` silently
         * became `tap-ui USE LIGHT` and edited the FIRST gene — a wrong edit that reported success.
         */
        private fun tapKey(key: String) {
            buildOverlay()
            val hit = ui.tapKey(key)
            println("[agent] tap-key '$key' -> ${if (hit) "clicked" else "no match"}")
            buildOverlay()
            sync()
        }

        private fun tapUi(arg: String) {
            val m = Regex("^(.*?)\\s*@(\\d+)$").find(arg)
            val label = (m?.groupValues?.get(1) ?: arg).trim()
            val nth = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
            buildOverlay()
            val hit = ui.tapLabel(label, nth) || (nth == 1 && run { controls.rebuild(); controls.tap(label) })
            println("[agent] tap-ui '$label'${if (nth > 1) " @$nth" else ""} -> ${if (hit) "clicked" else "no match"}")
            // A tap may have queued a world edit (a gene edit, the mutation ladder) OR merely parked a draft
            // in the gene editor, which needs another render to flush. The harness runs no sim thread, so
            // nothing else would drive either: settle here, or the next command observes a pre-edit world.
            // See CytoController.pendingWorldEdits and [buildOverlay].
            buildOverlay()
            sync()
        }

        /** Build the overlay widget tree headlessly (no draw) so [ui]/[controls] regions exist for
         *  enumeration + tap-by-label. */
        /**
         * Build the widget tree and then **settle it** — run the render → flush → publish cycle until the
         * world stops changing.
         *
         * The inline gene editor is live but not immediate: a token's `onClick` only parks a `draft`, which
         * `GeneEditor.render` flushes to `CytoController.setHeldGene` at the END of the next render, and
         * that queues through `pendingWorldEdits` for the next `publish`. The live host renders every frame,
         * so the round trip is one frame (~16ms) and invisible. The harness renders **once per command**, so
         * without settling an edit lands two commands late: `tap-ui ALWAYS` looked like a dead click, and a
         * following `tap-ui` acted on a stale card. That is what made the gene editor look unreachable
         * headlessly — the taps were arriving all along.
         *
         * Two rounds is the normal cost (one to flush and apply, one to observe no further change); the cap
         * only guards a hypothetical edit that re-triggers itself.
         */
        private fun buildOverlay(maxRounds: Int = 4) {
            repeat(maxRounds) {
                renderUiOnce()
                val before = controller.heldGenome()
                controller.publish()
                if (controller.heldGenome() == before) return
            }
        }

        private fun renderUiOnce() {
            ui.advanceClock(UI_CLOCK_STEP)
            val mask = director.controlMask
            // Wide always keeps the HUD: nothing on this width claims the bottom bar — the cell panel docks
                // right and every sheet is a centred, scrimmed popover — so there is nothing to make room for.
                // (It used to hide on `isEditing`, left over from the retired side-by-side editor column. That
                // also never came back, because on wide `isEditing` means "a draft is parked", which inline
                // editing leaves set indefinitely rather than only while a modal is up.)
                val showHud = if (NARROW) controller.lastHeldId == null else true
            ui.frame {
                // The front-end shell owns the whole screen when it is open, as in the real hosts.
                val shell = menu
                if (shell != null) { shell.render(this, emptyList(), menuCallbacks()); return@frame }
                // Bar before the coach (BottomCenter stacks in draw order); its sheets go last. Both centre on
                // the free world area, off the same value the camera uses.
                val freeDx = geneEditor
                    .freeAreaOffsetPx(NARROW, controller.lastHeldId != null, RES_W.toFloat(), RES_H.toFloat(), ui.scale).first
                if (showHud) {
                    hud.renderBar(this, controls, showPause = NARROW, freeAreaDxPx = freeDx) {}
                    if (!NARROW) hud.renderSpeed(this, controls)
                }
                if (director.active) director.render(this, controller, narrow = NARROW, freeAreaDxPx = freeDx)
                if (mask.allows(Control.GeneEditor)) geneEditor.render(
                    this,
                    controller,
                    grouping = director.grouping,
                    insertableGroups = director.activeChapter?.insertableGroups ?: emptySet(),
                    narrow = NARROW,
                    savedSnippets = CytoSnippets.list().map { GeneSnippet(it.name, it.genes) },
                    onSaveGroup = { name, genes -> CytoSnippets.save(name, genes) },
                )
                if (showHud) hud.renderSheets(this, controls, wide = !NARROW)
                director.renderSpotlight(this)
            }
        }

        /**
         * `expect <field> <value>` — assert a reading, so a script is a **test** and not just a recording.
         *
         * Without this a scripted playthrough can only be checked by a human reading its output, which means
         * it silently stops being checked. Failures are counted and reported at the end of the run (and the
         * process exits non-zero), so a campaign edit that breaks the flow is noticed rather than narrated.
         *
         * Fields are the campaign-meaningful readings — the same ones the chapter gates use.
         */
        private fun expect(arg: String) {
            val field = arg.substringBefore(' ').trim()
            val want = arg.substringAfter(' ', "").trim()
            val c = director.snapshot()
            val w = controller.worldStats()
            val lin = w.lineage
            val got: String? = when (field) {
                "chapter" -> c?.chapterId
                "step" -> c?.let { "${it.stepIndex + 1}/${it.stepCount}" }
                "goal" -> c?.goal
                "gateReady" -> c?.gateReady?.toString()
                "cells" -> w.cellCount.toString()
                "genes" -> lin?.geneCount?.toString()
                "convertChem" -> lin?.convertChem
                "growthCap" -> lin?.convertBiomassCap?.toString()
                "hasDivide" -> lin?.hasDivide?.toString()
                "divideFloor" -> lin?.divideBiomassMinimum?.toString()
                "recyclesExhaust" -> lin?.hasPhotosynthesis?.toString()
                "recycleReserve" -> lin?.recycleReserve?.toString()
                "importsExhaust" -> lin?.importsExhaust?.toString()
                "importCeiling" -> lin?.importCeiling?.toString()
                "importOnChem" -> lin?.importOnChem?.toString()
                "importGear" -> lin?.importGear?.toString()
                "hasRepair" -> lin?.hasRepair?.toString()
                "groupedGenes" -> lin?.groupedGeneCount?.toString()
                "groupNames" -> lin?.groupNames?.joinToString(",")
                "bond" -> lin?.divideProduct
                // The LAYERS matter layer, as the species token (or "all") — the ground's own state, which
                // no world reading can see.
                "matterLayer" -> controls.matterSpeciesId
                    .let { if (it == CytoControls.ALL_MATTER) "all" else SpeciesRegistry.string(it) }
                "fuelConflicts" -> lin?.divideFuelConflicts?.toString()
                else -> { failures.add("expect: unknown field '$field'"); println("[agent] EXPECT ?? unknown field '$field'"); return }
            }
            if (got == want) println("[agent] EXPECT ok   $field = $want")
            else {
                failures.add("$field: wanted '$want', got '$got'")
                println("[agent] EXPECT FAIL $field: wanted '$want', got '$got'")
            }
        }

        /**
         * `expect-ui <label>` / `expect-ui !<label>` — assert a (partial) label is, or is not, on screen.
         *
         * The counterpart to [expect]'s world readings, for what the UI *offers*: that a re-tagged gene's new
         * group header appeared, that an affordance shows up on a phone at all. Those are exactly the things
         * a layout change breaks silently, and nothing in the world state can see them.
         */
        private fun expectUi(arg: String) {
            val want = arg.removePrefix("!").trim()
            val wantAbsent = arg.trim().startsWith("!")
            buildOverlay()
            val present = ui.elements().any { it.label.contains(want, ignoreCase = true) }
            if (present != wantAbsent) println("[agent] EXPECT ok   ui ${if (wantAbsent) "!" else ""}'$want'")
            else {
                failures.add("ui: '$want' ${if (wantAbsent) "present but should not be" else "not on screen"}")
                println("[agent] EXPECT FAIL ui: '$want' ${if (wantAbsent) "is present" else "is not on screen"}")
            }
        }

        private fun dumpState(name: String) {
            val w = controller.worldStats()
            val sb = StringBuilder("{\n")
            sb.append("  \"tick\": ${w.tick},\n  \"cellCount\": ${w.cellCount},\n  \"maxBiomass\": ${w.maxBiomass},\n")
            sb.append("  \"countByType\": {${w.countByType.entries.joinToString(", ") { "\"${it.key.name}\": ${it.value}" }}},\n")
            sb.append("  \"nightLevel\": ${renderer.nightLevel},\n")
            val f = w.focused
            if (f != null) {
                sb.append("  \"focused\": {\"type\": \"${f.type.name}\", \"biomass\": ${f.biomass}, ")
                sb.append("\"cytoplasm\": {${f.cytoplasm.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}}},\n")
            } else sb.append("  \"focused\": null,\n")
            // The genome as the campaign reads it — survives both deselection and extinction, so it is the
            // thing to observe when checking a chapter's gates headlessly.
            val lin = w.lineage
            if (lin != null) {
                sb.append("  \"lineage\": {\"genes\": ${lin.geneCount}, \"convertChem\": ${jsonStr(lin.convertChem)}, ")
                sb.append("\"growthCap\": ${lin.convertBiomassCap}, \"divideFloor\": ${lin.divideBiomassMinimum}, ")
                sb.append("\"hasDivide\": ${lin.hasDivide}, \"recyclesExhaust\": ${lin.hasPhotosynthesis}, \"recycleReserve\": ${lin.recycleReserve}, ")
                sb.append("\"bond\": ${jsonStr(lin.divideProduct)}, \"fuelConflicts\": ${lin.divideFuelConflicts}},\n")
            } else sb.append("  \"lineage\": null,\n")
            val c = director.snapshot()
            if (c != null) {
                sb.append("  \"coach\": {\"chapter\": \"${c.chapterId}\", \"step\": \"${c.stepIndex + 1}/${c.stepCount}\", ")
                sb.append("\"world\": \"${c.world.name}\", \"gateReady\": ${c.gateReady}, ")
                sb.append("\"goal\": ${c.goal?.let { "\"${esc(it)}\"" } ?: "null"}, \"text\": \"${esc(c.text)}\"}\n")
            } else sb.append("  \"coach\": null\n")
            sb.append("}\n")
            println(sb); File(outDir, "$name.json").writeText(sb.toString())
        }

        private fun jsonStr(v: String?): String = if (v == null) "null" else "\"${esc(v)}\""


        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        // ── faithful GL render → PNG ──────────────────────────────────────────────────
        private fun shot(name: String) {
            // Settle the coach's spotlight before capturing: a target change fades out, swaps and fades back
            // in, so a single frame would catch it mid-transition. Three whole-second steps land it at rest —
            // and on the same pulse phase every time, which is what makes the shots comparable.
            repeat(3) { renderUiOnce() }
            // ...then capture on the HALF second, the top of the one-second pulse: whole seconds are its
            // dimmest point, and a shot taken there under-sells what the player sees.
            ui.advanceClock(UI_CLOCK_STEP / 2f)
            val mask = director.controlMask
            controls.showBrush = mask.allows(Control.Brush)
            controls.showTouchModes = mask.allows(Control.Brush)
            controls.showSimSpeed = mask.allows(Control.Speed)
            controls.showMutation = mask.allows(Control.Mutation)
            renderer.colorMode = controls.colorMode
            renderer.matterSpeciesId = controls.matterSpeciesId
            renderer.focusedCellId = controller.lastHeldId?.value ?: -1

            // Mirror the host's split: the CAMERA follows cameraFocusId (the `camerafocus` command / right-click),
            // NOT the selection. When it's set, recentre it into the space the panel/sheet leaves free and snap
            // (no damping) so a single captured frame is deterministic.
            controller.pruneDeadSelection()
            val focus = controller.cameraFocusId
            if (focus != null && !controller.isGrabbed) {
                val pos = controller.cameraFocusPosition()
                renderer.follow(focus.value, pos?.first ?: -1f, pos?.second ?: -1f)
                val panelUp = controller.lastHeldId != null
                val (ox, oy) = geneEditor.freeAreaOffsetPx(NARROW, cellShown = panelUp, RES_W.toFloat(), RES_H.toFloat(), ui.scale, topObscuredPx = director.coachTopInsetPx)
                renderer.setFollowOffsetPx(ox, oy)
                renderer.snapFollow()
            } else {
                renderer.setFollowOffsetPx(0f, 0f)
            }

            glViewport(0, 0, RES_W, RES_H)
            renderer.draw(controller.latestFrame())          // scene (fills its own background)
            // Wide always keeps the HUD: nothing on this width claims the bottom bar — the cell panel docks
                // right and every sheet is a centred, scrimmed popover — so there is nothing to make room for.
                // (It used to hide on `isEditing`, left over from the retired side-by-side editor column. That
                // also never came back, because on wide `isEditing` means "a draft is parked", which inline
                // editing leaves set indefinitely rather than only while a modal is up.)
                val showHud = if (NARROW) controller.lastHeldId == null else true
            ui.frame {                                        // info panel + coach overlay + L0 HUD (both widths)
                val shell = menu
                if (shell != null) { shell.render(this, emptyList(), menuCallbacks()); return@frame }
                // Bar before the coach (BottomCenter stacks in draw order); its sheets go last. Both centre on
                // the free world area, off the same value the camera uses.
                val freeDx = geneEditor
                    .freeAreaOffsetPx(NARROW, controller.lastHeldId != null, RES_W.toFloat(), RES_H.toFloat(), ui.scale).first
                if (showHud) {
                    hud.renderBar(this, controls, showPause = NARROW, freeAreaDxPx = freeDx) {}
                    if (!NARROW) hud.renderSpeed(this, controls)
                }
                if (director.active) director.render(this, controller, narrow = NARROW, freeAreaDxPx = freeDx)
                if (mask.allows(Control.GeneEditor)) geneEditor.render(
                    this,
                    controller,
                    grouping = director.grouping,
                    insertableGroups = director.activeChapter?.insertableGroups ?: emptySet(),
                    narrow = NARROW,
                    savedSnippets = CytoSnippets.list().map { GeneSnippet(it.name, it.genes) },
                    onSaveGroup = { name, genes -> CytoSnippets.save(name, genes) },
                )
                if (showHud) hud.renderSheets(this, controls, wide = !NARROW)
                director.renderSpotlight(this)
            }
            ui.draw()
            glFinish()

            val buf = BufferUtils.createByteBuffer(RES_W * RES_H * 4)
            glReadPixels(0, 0, RES_W, RES_H, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            val img = BufferedImage(RES_W, RES_H, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until RES_H) {
                val src = RES_H - 1 - y                       // GL rows are bottom-up
                for (x in 0 until RES_W) {
                    val i = (src * RES_W + x) * 4
                    val r = buf.get(i).toInt() and 0xFF
                    val g = buf.get(i + 1).toInt() and 0xFF
                    val b = buf.get(i + 2).toInt() and 0xFF
                    img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }
            // Put the pulse back on a whole second, so the next shot's half-second offset lands on the peak
            // again rather than alternating bright/dim from one capture to the next.
            ui.advanceClock(UI_CLOCK_STEP / 2f)
            val out = File(outDir, "$name.png")
            ImageIO.write(img, "png", out)
            println("[agent] shot -> ${out.name} (${controller.worldStats().cellCount} cells, tick ${controller.tick})")
        }

        private fun preset(name: String): CytoScenario =
            CytoScenario.PRESETS.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CytoScenario.DEFAULT
    }
}

fun main(args: Array<String>) {
    val scriptArg = args.getOrElse(0) { "-" }
    val outDir = File(args.getOrElse(1) { "agent-out" })
    val script = if (scriptArg == "-") System.`in`.readBytes().decodeToString() else File(scriptArg).readText()
    CytoAgentHarness.run(script, outDir)
}
