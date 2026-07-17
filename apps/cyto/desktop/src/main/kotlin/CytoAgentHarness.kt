package org.emerge.desktop
import org.emerge.demo.cyto.host.CampaignContent

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.CytoSaveCodec
import org.emerge.demo.cyto.campaign.CampaignDirector
import org.emerge.demo.cyto.campaign.CampaignQuery
import org.emerge.demo.cyto.campaign.Control
import org.emerge.demo.cyto.campaign.PlayerAction
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.ui.CytoControls
import org.emerge.demo.cyto.ui.CytoHud
import org.emerge.demo.cyto.ui.GeneEditor
import org.emerge.render.torus.ui.Ui
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
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
 * run <ticks> | runs <sec>   # advance the sim
 * camera reset|zoom <f>|pan <dx> <dy>|follow <cellId>
 * tap <u> <v> | select <u> <v> | spawn <u> <v>   # pointer at normalised screen coords (0..1)
 * clickcell <id> | dragcell <id> <u> <v> <ticks> # target a cell by entity id; drag spans <ticks> ticks
 * cells                      # list cells (id, type, biomass, selected, screen u,v)
 * elements                   # list the visible on-screen buttons (by label)
 * tap-ui <label>             # click a button by (partial, case-insensitive) label
 * overlay matter|light       # toggle the light/matter overlay
 * next                       # click the coach "Next" (advances the chapter if its goal is met)
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
    }

    private class Session(val outDir: File) {
        val controller = CytoController()
        val director = CampaignDirector()

        private var window: Long = NULL
        private lateinit var renderer: CytoRenderer
        private lateinit var controls: CytoControls
        private lateinit var ui: Ui
        private lateinit var geneEditor: GeneEditor
        private val hud = CytoHud()
        private val pendingActions = HashSet<PlayerAction>()

        fun init() {
            director.onStepEnter = {}
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
                "campaign" -> {
                    val ch = CampaignContent.CHAPTERS.firstOrNull { it.id == t.getOrNull(1) }
                        ?: error("unknown chapter '${t.getOrNull(1)}' (have ${CampaignContent.ORDER})")
                    controller.newGame(ch.scenario); director.start(ch, controller); renderer.resetView()
                }
                "genome" -> {
                    // Spawn a single founder from a .gene file — for testing hand-authored / campaign-stage
                    // genomes (viability, differentiation, locomotion). Push it with `dragcell` to bootstrap.
                    val path = line.removePrefix("genome").trim().trim('"')
                    val genes = GeneCodec.parse(File(path).readText())
                    val scn = CytoScenario.DEFAULT.copy(
                        name = "Probe",
                        founders = listOf(FounderSpec(CellType.Collector, 1, genome = genes)),
                    )
                    director.stop(); controller.newGame(scn); renderer.resetView()
                    println("[agent] genome loaded: ${genes.size} genes from $path")
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
                "tap" -> { val (x, y) = world(t[1].toFloat(), t[2].toFloat()); tapWorld(x, y) }
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
                    controller.releaseGrab(); controller.publish(); sync()
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
                "tap-ui" -> tapUi(line.removePrefix("tap-ui").trim())
                "drag-ui" -> dragUi(t[1], t[2].toFloat())
                "overlay" -> {
                    val matter = t[1] == "matter"
                    controls.rebuild()
                    if (controls.showMatterField != matter) controls.tap("GRID")   // single toggle button
                    if (matter) pendingActions.add(PlayerAction.ToggledMatterOverlay)
                    sync()
                }
                "did" -> { pendingActions.add(PlayerAction.valueOf(t[1])); sync() }
                "next" -> { sync(); println("[agent] next -> ${if (director.tryAdvance(controller)) "advanced" else "blocked (goal not met)"}") }
                "shot" -> { sync(); shot(t.getOrElse(1) { "shot" }) }
                "state" -> { sync(); dumpState(t.getOrElse(1) { "state" }) }
                "dumpraw" -> dumpRaw()
                "echo" -> println("[agent] ${line.removePrefix("echo").trim()}")
                else -> error("unknown command '${t[0]}'")
            }
        }

        private fun advance(ticks: Int) {
            repeat(ticks.coerceAtLeast(0)) { controller.stepOnce() }
            controller.publish(); sync()
        }

        private fun sync() {
            if (!director.active) { pendingActions.clear(); return }
            val q = CampaignQuery(controller.worldStats(), controls.showMatterField, paused = false, selectedGenome = null)
            director.update(q, pendingActions.toSet()); pendingActions.clear()
        }

        private fun tapWorld(x: Float, y: Float) {
            controller.cellAt(x, y)?.let { controller.focus(it); pendingActions.add(PlayerAction.SelectedCell) }
            controller.tap(x, y, TouchMode.Base, CellType.Stem); advance(1)
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
            // Geometry, so a touch-target audit can see how big these actually are at this render size.
            for (e in ui.elements())
                println("[agent]   ui '${e.label}' x=${e.x.toInt()} y=${e.y.toInt()} w=${e.w.toInt()} h=${e.h.toInt()}")
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
                sb.append("\"biomass\": ${totalBiomassBonds(cell.biomass)}, ")
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

        private fun totalBiomassBonds(biomass: Map<String, Int>): Int = biomass.values.sum()

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

        private fun tapUi(label: String) {
            buildOverlay()
            val hit = ui.tapLabel(label) || run { controls.rebuild(); controls.tap(label) }
            println("[agent] tap-ui '$label' -> ${if (hit) "clicked" else "no match"}")
            // A tap may have queued a world edit (a gene edit, the mutation ladder). The harness runs no sim
            // thread, so nothing else would drain it: publish here, or a following `shot`/`elements` with no
            // `run` between would observe the pre-edit world. See CytoController.pendingWorldEdits.
            controller.publish()
            sync()
        }

        /** Build the overlay widget tree headlessly (no draw) so [ui]/[controls] regions exist for
         *  enumeration + tap-by-label. */
        private fun buildOverlay() {
            val mask = director.controlMask
            val showHud = if (NARROW) (!geneEditor.isEditing && controller.lastHeldId == null) else !geneEditor.isEditing
            ui.frame {
                // Bar before the coach (BottomCenter stacks in draw order); its sheets go last.
                if (showHud) hud.renderBar(this, controls) {}
                if (director.active) {
                    val modalUp = NARROW && geneEditor.isEditing
                    val cellUp = geneEditor.isEditing || (NARROW && controller.lastHeldId != null)
                    if (!modalUp) director.render(this, controller, collapsed = cellUp, narrow = NARROW)
                }
                if (mask.allows(Control.GeneEditor)) geneEditor.render(this, controller, grouping = director.activeChapter?.grouping, insertableGroups = director.activeChapter?.insertableGroups ?: emptySet(), narrow = NARROW) {}
                if (showHud) hud.renderSheets(this, controls, wide = !NARROW)
            }
        }

        private fun dumpState(name: String) {
            val w = controller.worldStats()
            val sb = StringBuilder("{\n")
            sb.append("  \"tick\": ${w.tick},\n  \"cellCount\": ${w.cellCount},\n  \"maxBiomass\": ${w.maxBiomass},\n")
            sb.append("  \"countByType\": {${w.countByType.entries.joinToString(", ") { "\"${it.key.name}\": ${it.value}" }}},\n")
            sb.append("  \"overlayMatter\": ${controls.showMatterField},\n")
            val f = w.focused
            if (f != null) {
                sb.append("  \"focused\": {\"type\": \"${f.type.name}\", \"biomass\": ${f.biomass}, \"genes\": ${f.geneCount}, ")
                sb.append("\"cytoplasm\": {${f.cytoplasm.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}}},\n")
            } else sb.append("  \"focused\": null,\n")
            val c = director.snapshot()
            if (c != null) {
                sb.append("  \"coach\": {\"chapter\": \"${c.chapterId}\", \"step\": \"${c.stepIndex + 1}/${c.stepCount}\", ")
                sb.append("\"world\": \"${c.world.name}\", \"gateReady\": ${c.gateReady}, ")
                sb.append("\"goal\": ${c.goal?.let { "\"${esc(it)}\"" } ?: "null"}, \"text\": \"${esc(c.text)}\"}\n")
            } else sb.append("  \"coach\": null\n")
            sb.append("}\n")
            println(sb); File(outDir, "$name.json").writeText(sb.toString())
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        // ── faithful GL render → PNG ──────────────────────────────────────────────────
        private fun shot(name: String) {
            val mask = director.controlMask
            controls.showBrush = mask.allows(Control.Brush)
            controls.showTouchModes = mask.allows(Control.Brush)
            controls.showSimSpeed = mask.allows(Control.Speed)
            controls.showMutation = mask.allows(Control.Mutation)
            renderer.showLightField = controls.showLightField
            renderer.showMatterField = controls.showMatterField
            renderer.colorMode = controls.colorMode
            renderer.focusedCellId = controller.lastHeldId?.value ?: -1

            // Mirror the host's split: the CAMERA follows cameraFocusId (the `camerafocus` command / right-click),
            // NOT the selection. When it's set, recentre it into the space the panel/sheet leaves free and snap
            // (no damping) so a single captured frame is deterministic.
            controller.pruneDeadSelection()
            val focus = controller.cameraFocusId
            if (focus != null && !controller.isGrabbed) {
                val pos = controller.cameraFocusPosition()
                renderer.follow(focus.value, pos?.first ?: -1f, pos?.second ?: -1f)
                val panelUp = geneEditor.isEditing || controller.lastHeldId != null
                val (ox, oy) = geneEditor.freeAreaOffsetPx(NARROW, cellShown = panelUp, RES_W.toFloat(), RES_H.toFloat(), ui.scale, topObscuredPx = director.coachTopInsetPx)
                renderer.setFollowOffsetPx(ox, oy)
                renderer.snapFollow()
            } else {
                renderer.setFollowOffsetPx(0f, 0f)
            }

            glViewport(0, 0, RES_W, RES_H)
            renderer.draw(controller.latestFrame())          // scene (fills its own background)
            val showHud = if (NARROW) (!geneEditor.isEditing && controller.lastHeldId == null) else !geneEditor.isEditing
            ui.frame {                                        // info panel + coach overlay + L0 HUD (both widths)
                // Bar before the coach (BottomCenter stacks in draw order); its sheets go last.
                if (showHud) hud.renderBar(this, controls) {}
                if (director.active) {
                    val modalUp = NARROW && geneEditor.isEditing
                    val cellUp = geneEditor.isEditing || (NARROW && controller.lastHeldId != null)
                    if (!modalUp) director.render(this, controller, collapsed = cellUp, narrow = NARROW)
                }
                if (mask.allows(Control.GeneEditor)) geneEditor.render(this, controller, grouping = director.activeChapter?.grouping, insertableGroups = director.activeChapter?.insertableGroups ?: emptySet(), narrow = NARROW) {}
                if (showHud) hud.renderSheets(this, controls, wide = !NARROW)
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
