package org.emerge.demo.cyto.host

import org.emerge.demo.cyto.build.BuildInfo
import org.emerge.demo.cyto.campaign.CampaignMap
import org.emerge.demo.cyto.campaign.Chapter
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoWorldConfig
import org.emerge.demo.cyto.sim.Distribution
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.sim.Gene
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.UiBuilder

/**
 * The front-end **shell** around the running sim: a title screen and its New-game / Custom-tuning /
 * About / Settings sub-screens, drawn with the shared immediate-mode [Ui] toolkit over a dimmed backdrop.
 * Pure UI + navigation state — it owns no sim; the host ([CytoSceneView]) supplies [Callbacks] that
 * actually build/resume a world. While [inGame] the shell draws nothing (the sim owns the screen).
 */
class CytoMenu {
    enum class Page { Title, Campaign, New, Custom, Load, Save, SaveGenome, About, Settings }

    /** Whether this host has a mouse (desktop) vs a touchscreen (android). Gates pointer-only settings like the
     *  right-click-camera toggle, which is meaningless on a touch device (two-finger camera there). */
    var hasMouse = true

    /** The campaign, host-set each frame: the authored chapters and which of them are finished. What is
     *  *unlocked* is derived from those two by [CampaignMap] rather than asked of the host — the map already
     *  has to walk the graph to lay it out, and two places computing reachability is one too many. */
    var campaignChapters: List<Chapter> = emptyList()
    var campaignCompleted: (String) -> Boolean = { false }

    /** True once a world is running and the player has dismissed the menu — the host renders the sim instead. */
    var inGame = false
    var page = Page.Title
        private set

    /** In-progress name on a name-entry screen (typed via the host's char callback). */
    private val nameBuffer = StringBuilder()
    /** True while a name field should receive keyboard input (world save or genome save). */
    val capturingName: Boolean get() = !inGame && (page == Page.Save || page == Page.SaveGenome)

    /** The held cell's genome + swatch colour captured when the Save-Genome screen was opened. */
    private var pendingGenome: List<Gene> = emptyList()
    private var pendingGenomeColor: Long = 0x888888FFL
    fun pendingGenome(): List<Gene> = pendingGenome
    fun pendingGenomeColor(): Long = pendingGenomeColor

    // Editable Custom-scenario fields (seeded from the historical default).
    private var worldSize = CytoWorldConfig.DEFAULT_CELLS_PER_AXIS
    private var dayTicks = CytoScenario.DEFAULT.dayTicks
    private var nightTicks = CytoScenario.DEFAULT.nightTicks
    private var matterLevel = CytoSeed.MATTER_UNIFORM_LEVEL
    private var collectors = 1
    private var muscle = 0
    private var distribution = Distribution.Clustered

    /** Which picker (if any) is expanded this frame — the toolkit needs one-at-a-time open state. */
    private var openPicker = OpenPicker.None
    private enum class OpenPicker { None, Distribution }

    /** Save currently awaiting a delete confirmation on the Load screen (null = none). */
    private var pendingDelete: String? = null

    class Callbacks(
        /** Build + start a brand-new world from [scenario] (rebuilds the sim). */
        val onStart: (CytoScenario) -> Unit,
        /** Resume the world already in memory (the autoloaded/last save). */
        val onContinue: () -> Unit,
        /** Load the named on-disk save into the world. */
        val onLoadNamed: (String) -> Unit,
        /** Open the Save-name screen for the current world (host pre-fills the default name). */
        val onOpenSave: () -> Unit,
        /** Write the current world to a named save. */
        val onSave: (String) -> Unit,
        /** Delete a named save. */
        val onDelete: (String) -> Unit,
        /** Save the held cell's genome to the named genome-library entry (name, colour, genome). */
        val onSaveGenome: (String, Long, List<Gene>) -> Unit,
        /** Start the given campaign chapter (rebuilds the world + activates the director). */
        val onStartChapter: (Chapter) -> Unit,
        val onQuit: () -> Unit,
        /** Current value of the "right button also drives the camera" preference (host-persisted, opt-in). */
        val rightClickCamera: () -> Boolean = { false },
        /** Set the "right button also drives the camera" preference (host persists it). */
        val onSetRightClickCamera: (Boolean) -> Unit = {},
    )

    /** Open the campaign chapter-select page (e.g. after finishing a chapter). */
    fun openCampaign() { inGame = false; page = Page.Campaign; openPicker = OpenPicker.None }

    /** Open the shell back to the title screen (e.g. the in-game Menu button / a fresh boot). */
    fun openTitle() { inGame = false; page = Page.Title; openPicker = OpenPicker.None; pendingDelete = null }

    /** Open the Save-name screen from in-game, pre-filling a default name. */
    fun openSave(default: String) {
        inGame = false; page = Page.Save; openPicker = OpenPicker.None
        nameBuffer.setLength(0); nameBuffer.append(default)
    }

    /** Open the Save-Genome screen from in-game, capturing the held cell's genome + BIO swatch colour. */
    fun openGenomeSave(default: String, genome: List<Gene>, color: Long) {
        inGame = false; page = Page.SaveGenome; openPicker = OpenPicker.None
        pendingGenome = genome; pendingGenomeColor = color
        nameBuffer.setLength(0); nameBuffer.append(default)
    }

    /** Dismiss the shell (the world takes over). */
    fun enterGame() { inGame = true; openPicker = OpenPicker.None }

    // ── Save-name text entry (fed by the host's GLFW char / key callbacks) ──────────────
    fun typeChar(c: Char) { if (capturingName && nameBuffer.length < 40 && c >= ' ') nameBuffer.append(c) }
    fun backspace() { if (capturingName && nameBuffer.isNotEmpty()) nameBuffer.setLength(nameBuffer.length - 1) }
    fun currentName(): String = nameBuffer.toString()

    /** Build the current custom-screen selections into a scenario. */
    private fun customScenario(): CytoScenario {
        val founders = buildList {
            if (collectors > 0) add(FounderSpec(CellType.Collector, collectors))
            if (muscle > 0) add(FounderSpec(CellType.Muscle, muscle))
        }.ifEmpty { listOf(FounderSpec(CellType.Collector, 1)) }
        return CytoScenario(
            name = "Custom",
            worldSize = worldSize,
            dayTicks = dayTicks,
            nightTicks = nightTicks,
            matterLevel = matterLevel,
            founders = founders,
            distribution = distribution,
        )
    }

    /** Rebuild this frame's menu widgets. Call inside `ui.frame { … }`. No-op while [inGame]. [saves] is the
     *  list of on-disk save names (newest first) for the Title/Load screens. */
    fun render(ui: UiBuilder, saves: List<String>, cb: Callbacks) {
        if (inGame) return
        ui.background(0x0A0E14EEL)
        when (page) {
            Page.Title -> title(ui, saves.isNotEmpty(), cb)
            Page.Campaign -> campaign(ui, cb)
            Page.New -> newGame(ui, cb)
            Page.Custom -> custom(ui, cb)
            Page.Load -> load(ui, saves, cb)
            Page.Save -> save(ui, cb)
            Page.SaveGenome -> saveGenome(ui, cb)
            Page.About -> about(ui)
            Page.Settings -> settings(ui, cb)
        }
    }

    private fun title(ui: UiBuilder, hasSave: Boolean, cb: Callbacks) {
        ui.panel(Anchor.Center, padding = 22f, background = 0x141C2CF0, rowHeight = 30f) {
            title("CYTO", 0x6FD6C4FFL)
            gap(14f)
            button("Campaign", MENU_ACCENT) { page = Page.Campaign }
            button("Continue", MENU_BTN) { cb.onContinue() }
            button("Save", MENU_BTN) { cb.onOpenSave() }
            if (hasSave) button("Load", MENU_BTN) { page = Page.Load }
            button("New (Sandbox)", MENU_BTN) { page = Page.New }
            gap(6f)
            button("Quit", MENU_QUIT) { cb.onQuit() }
        }
        // Corner utility buttons.
        ui.panel(Anchor.TopRight, rowHeight = 26f, background = 0x00000000) {
            actionRow(listOf(
                Triple("?", MENU_BTN) { page = Page.About },
                Triple("Settings", MENU_BTN) { page = Page.Settings },
            ))
        }
        // Which build this is. Dim and out of the way, but present on every host — the phone APK has no
        // other way to say what it was built from.
        ui.panel(Anchor.BottomCenter, rowHeight = 16f, background = 0x00000000) {
            text(BuildInfo.LABEL, 0x7A8699FFL)
        }
    }

    /**
     * The campaign as a **map** rather than a list: the chapters the player has reached, laid out by depth
     * with the routes between them drawn in, and one layer of unnamed markers showing where it goes next.
     *
     * A list could not say the thing that matters here — the campaign *forks*, and a fork rendered as two
     * consecutive rows is just a longer corridor. So the connectors are load-bearing, not decoration: they
     * are how a branch reads as a branch.
     *
     * Drawn before the surrounding chrome so the panels sit on top of it.
     */
    private fun campaign(ui: UiBuilder, cb: Callbacks) {
        val map = CampaignMap.build(campaignChapters, campaignCompleted)
        drawMap(ui, map, cb)
        ui.panel(Anchor.TopLeft, padding = 14f, background = 0x141C2CF0, rowHeight = 24f) {
            title("Campaign", 0x6FD6C4FFL)
            if (campaignChapters.isEmpty()) text("No chapters yet.", 0x8B96A8FFL)
            else {
                // Two short lines rather than one long one: `row` clips rather than wraps, and a phone at
                // 2.6x density fits about 26 characters across the whole screen.
                text("Where you have been,", 0x8B96A8FFL)
                text("and where it leads.", 0x8B96A8FFL)
            }
        }
        ui.panel(Anchor.BottomLeft, padding = 14f, background = 0x141C2CF0, rowHeight = 26f) {
            button("Back", MENU_QUIT) { page = Page.Title }
        }
    }

    /** Place [map]'s nodes on a depth × lane grid and join them with elbow connectors. */
    private fun drawMap(ui: UiBuilder, map: CampaignMap, cb: Callbacks) {
        if (map.nodes.isEmpty()) return
        ui.canvas {
            val s = density
            // The map is small and bounded by authoring (the campaign is a handful deep and two wide), so it
            // is always shown whole: no scrolling, no panning, the shape readable at a glance. The grid is
            // fitted to the screen but capped, then CENTRED in what's left — a five-node map on a big monitor
            // should sit in the middle of it, not stranded against the top edge.
            val top = 92f * s
            val bottom = 64f * s
            val rowH = ((screenH - top - bottom) / map.depthCount).coerceAtMost(118f * s)
            val nodeH = (rowH * 0.44f).coerceAtMost(44f * s)
            val gridW = (screenW - 80f * s).coerceAtMost(720f * s)
            val gridX = (screenW - gridW) / 2f
            val gridY = top + (screenH - top - bottom - rowH * map.depthCount) / 2f

            fun cx(n: CampaignMap.Node): Float = gridX + gridW * n.x
            fun cy(n: CampaignMap.Node): Float = gridY + rowH * (n.depth + 0.5f)
            // One node width for every named chapter, so the map reads as a spine rather than as boxes of
            // assorted importance — narrowed only where a node's band is too tight to fit it. A ghost is
            // deliberately smaller: it is a marker, not a card.
            fun w(n: CampaignMap.Node): Float {
                val band = gridW * n.span - 16f * s
                return if (n.revealed) band.coerceAtMost(230f * s) else band.coerceAtMost(96f * s)
            }

            // Connectors first, so the nodes cover their ends. Elbow-routed (down, across, down) because the
            // rect renderer draws axis-aligned quads only - and an elbow reads as a route on a grid anyway.
            val line = 2f * s
            for (e in map.edges) {
                val a = map.nodes[e.from]
                val b = map.nodes[e.to]
                val colour = if (b.revealed) MAP_EDGE else MAP_EDGE_DIM
                val ax = cx(a); val ay = cy(a) + nodeH / 2f
                val bx = cx(b); val by = cy(b) - nodeH / 2f
                val mid = (ay + by) / 2f
                rect(ax - line / 2f, ay, line, mid - ay, colour)
                rect(minOf(ax, bx), mid - line / 2f, kotlin.math.abs(bx - ax) + line, line, colour)
                rect(bx - line / 2f, mid, line, by - mid, colour)
            }
            for (n in map.nodes) {
                val bw = w(n)
                val x = cx(n) - bw / 2f
                val y = cy(n) - nodeH / 2f
                val ch = n.chapter
                if (ch == null) {
                    // A ghost says only that something is there. No title, no blurb, no click - the player is
                    // being shown the shape of the road ahead, not its contents.
                    box(x, y, bw, nodeH, MAP_GHOST, "???", MAP_GHOST_TEXT, textHeight = nodeH * 0.34f)
                } else {
                    val done = n.state == CampaignMap.State.Completed
                    box(
                        x, y, bw, nodeH, if (done) MAP_DONE else MAP_OPEN,
                        text = ch.title, textColor = if (done) 0xB9C6D8FFL else 0xFFFFFFFFL,
                        textHeight = nodeH * 0.34f,
                    ) { cb.onStartChapter(ch) }
                    // Offset off the node's centre line, which is where its outgoing connector runs.
                    if (done) label("done", x + bw - 22f * s, y + nodeH + 4f * s, nodeH * 0.24f, 0x6E7A8CFFL)
                }
            }
        }
    }

    private fun newGame(ui: UiBuilder, cb: Callbacks) {
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 28f) {
            title("New World", 0x6FD6C4FFL)
            text("Pick a scenario", 0x8B96A8FFL)
            gap(8f)
            for (s in CytoScenario.PRESETS) button(s.name, MENU_BTN) { cb.onStart(s) }
            gap(6f)
            button("Custom...", MENU_ACCENT) { page = Page.Custom }
            gap(6f)
            button("Back", MENU_QUIT) { page = Page.Title }
        }
    }

    private fun custom(ui: UiBuilder, cb: Callbacks) {
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 24f) {
            title("Custom World", 0x6FD6C4FFL)
            gap(6f)
            stepper("World size", "$worldSize") { worldSize = clamp((worldSize + it * 16) / 16 * 16, 16, 256) }
            stepper("Day ticks", "$dayTicks") { dayTicks = clampL(dayTicks + it * 10L, 60L, 20000L) }
            stepper("Night ticks", "$nightTicks") { nightTicks = clampL(nightTicks + it * 10L, 60L, 20000L) }
            stepper("Matter/cell", "$matterLevel") { matterLevel = clamp(matterLevel + it, 1, 100000) }
            gap(6f)
            text("Founders", 0x8B96A8FFL)
            stepper("Autotrophs", "$collectors") { collectors = clamp(collectors + it, 0, 64) }
            stepper("Heterotrophs", "$muscle") { muscle = clamp(muscle + it, 0, 64) }
            picker(
                "Layout", distribution.name, Distribution.entries.map { it.name },
                open = openPicker == OpenPicker.Distribution,
                onToggle = { openPicker = if (openPicker == OpenPicker.Distribution) OpenPicker.None else OpenPicker.Distribution },
                onPick = { distribution = Distribution.entries[it]; openPicker = OpenPicker.None },
            )
            gap(10f)
            button("Start", MENU_ACCENT) { cb.onStart(customScenario()) }
            button("Back", MENU_QUIT) { page = Page.New }
        }
    }

    private fun load(ui: UiBuilder, saves: List<String>, cb: Callbacks) {
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 26f) {
            title("Load World", 0x6FD6C4FFL)
            gap(6f)
            if (saves.isEmpty()) text("No saves yet.", 0x8B96A8FFL)
            for (name in saves.take(12)) {
                if (pendingDelete == name) {
                    actionRow(listOf(
                        Triple("Delete '$name'?", 0x53384AFFL) { },
                        Triple("Yes", MENU_DANGER) { cb.onDelete(name); pendingDelete = null },
                        Triple("No", MENU_BTN) { pendingDelete = null },
                    ))
                } else {
                    actionRow(listOf(
                        Triple(name, MENU_BTN) { cb.onLoadNamed(name) },
                        Triple("Del", MENU_QUIT) { pendingDelete = name },
                    ))
                }
            }
            gap(8f)
            button("Back", MENU_QUIT) { page = Page.Title; pendingDelete = null }
        }
    }

    private fun save(ui: UiBuilder, cb: Callbacks) {
        val name = currentName()
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 26f) {
            title("Save World", 0x6FD6C4FFL)
            text("Type a name (Enter to save):", 0x8B96A8FFL)
            gap(4f)
            // The name field: show the buffer + a caret so it reads as an editable field.
            text("> ${name}_", 0xFFFFFFFFL)
            gap(10f)
            if (name.isNotBlank()) button("Save", MENU_ACCENT) { cb.onSave(name) }
            button("Back", MENU_QUIT) { enterGame() }
        }
    }

    private fun saveGenome(ui: UiBuilder, cb: Callbacks) {
        val name = currentName()
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 26f) {
            title("Save Genome", 0x6FD6C4FFL)
            text("Names the current cell's genome as a reusable", 0x8B96A8FFL)
            text("brush. Same name overwrites.", 0x8B96A8FFL)
            gap(6f)
            // Swatch preview (the exported cell's BIO colour) + the editable name field.
            button("swatch", pendingGenomeColor) { }
            text("> ${name}_", 0xFFFFFFFFL)
            gap(10f)
            if (name.isNotBlank()) button("Save", MENU_ACCENT) { cb.onSaveGenome(name, pendingGenomeColor, pendingGenome) }
            button("Back", MENU_QUIT) { enterGame() }
        }
    }

    private fun about(ui: UiBuilder) {
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 24f) {
            title("About Cyto", 0x6FD6C4FFL)
            gap(6f)
            text("A cell-scale evolution sandbox: autotrophs bond", 0xC8C8C8FFL)
            text("matter under a sweeping daylight band, grow,", 0xC8C8C8FFL)
            text("divide, and evolve. Drag cells, inspect genomes,", 0xC8C8C8FFL)
            text("and watch a colony find its carrying capacity.", 0xC8C8C8FFL)
            gap(8f)
            text("Space pause  •  [ ] speed  •  Esc menu", 0x8B96A8FFL)
            gap(8f)
            button("Back", MENU_QUIT) { page = Page.Title }
        }
    }

    private fun settings(ui: UiBuilder, cb: Callbacks) {
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 24f) {
            title("Settings", 0x6FD6C4FFL)
            gap(6f)
            // Pointer-only controls (a touch host has no mouse buttons — its camera is two-finger).
            if (hasMouse) {
                text("CONTROLS", 0x8FA4C8FFL)
                text("Left-drag empty space pans the camera.", 0xC8C8C8FFL)
                gap(4f)
                val rcc = cb.rightClickCamera()
                // Left-drag always pans; the right button is an opt-in second camera control. The label shows
                // the live state; tapping flips + persists it.
                button(if (rcc) "Right-click camera: ON" else "Right-click camera: OFF", if (rcc) MENU_ACCENT else MENU_BTN) {
                    cb.onSetRightClickCamera(!rcc)
                }
                text(if (rcc) "The RIGHT button also pans/focuses." else "The RIGHT button does nothing (turn on to enable).", 0xC8C8C8FFL)
                gap(8f)
            }
            text("World tuning lives under New > Custom.", 0x8A8A8AFFL)
            gap(8f)
            button("Back", MENU_QUIT) { page = Page.Title }
        }
    }

    companion object {
        private const val MENU_BTN = 0x2A3550FFL
        private const val MENU_ACCENT = 0x2E6E5EFFL
        private const val MENU_QUIT = 0x53384AFFL
        private const val MENU_DANGER = 0xB03A3AFFL

        // Map palette: a completed chapter recedes, an available one is lit, a ghost is barely there.
        private const val MAP_DONE = 0x243049FFL
        private const val MAP_OPEN = 0x2E6E5EFFL
        private const val MAP_GHOST = 0x161C28FFL
        private const val MAP_GHOST_TEXT = 0x4E5768FFL
        private const val MAP_EDGE = 0x35415CFFL
        private const val MAP_EDGE_DIM = 0x212936FFL

        private fun clamp(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
        private fun clampL(v: Long, lo: Long, hi: Long) = if (v < lo) lo else if (v > hi) hi else v
    }
}
