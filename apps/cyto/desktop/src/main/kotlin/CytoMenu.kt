package org.emerge.desktop

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoWorldConfig
import org.emerge.demo.cyto.sim.Distribution
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.UiBuilder

/**
 * The front-end **shell** around the running sim: a title screen and its New-game / Custom-tuning /
 * About / Settings sub-screens, drawn with the shared immediate-mode [Ui] toolkit over a dimmed backdrop.
 * Pure UI + navigation state — it owns no sim; the host ([CytoSceneView]) supplies [Callbacks] that
 * actually build/resume a world. While [inGame] the shell draws nothing (the sim owns the screen).
 */
class CytoMenu {
    enum class Page { Title, New, Custom, Load, Save, About, Settings }

    /** True once a world is running and the player has dismissed the menu — the host renders the sim instead. */
    var inGame = false
    var page = Page.Title
        private set

    /** In-progress name on the [Page.Save] screen (typed via the host's char callback). */
    private val nameBuffer = StringBuilder()
    /** True while the Save name field should receive keyboard input. */
    val capturingName: Boolean get() = !inGame && page == Page.Save

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
        /** Write the current world to a named save. */
        val onSave: (String) -> Unit,
        /** Delete a named save. */
        val onDelete: (String) -> Unit,
        val onQuit: () -> Unit,
    )

    /** Open the shell back to the title screen (e.g. the in-game Menu button / a fresh boot). */
    fun openTitle() { inGame = false; page = Page.Title; openPicker = OpenPicker.None; pendingDelete = null }

    /** Open the Save-name screen from in-game, pre-filling a default name. */
    fun openSave(default: String) {
        inGame = false; page = Page.Save; openPicker = OpenPicker.None
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
            Page.New -> newGame(ui, cb)
            Page.Custom -> custom(ui, cb)
            Page.Load -> load(ui, saves, cb)
            Page.Save -> save(ui, cb)
            Page.About -> about(ui)
            Page.Settings -> settings(ui)
        }
    }

    private fun title(ui: UiBuilder, hasSave: Boolean, cb: Callbacks) {
        ui.panel(Anchor.Center, padding = 22f, background = 0x141C2CF0, rowHeight = 30f) {
            title("CYTO", 0x6FD6C4FFL)
            gap(14f)
            button("Continue", MENU_BTN) { cb.onContinue() }
            if (hasSave) button("Load", MENU_BTN) { page = Page.Load }
            button("New", MENU_ACCENT) { page = Page.New }
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
    }

    private fun newGame(ui: UiBuilder, cb: Callbacks) {
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 28f) {
            title("New World", 0x6FD6C4FFL)
            row("Pick a scenario", 0x8B96A8FFL)
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
            row("Founders", 0x8B96A8FFL)
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
            if (saves.isEmpty()) row("No saves yet.", 0x8B96A8FFL)
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
            row("Type a name (Enter to save):", 0x8B96A8FFL)
            gap(4f)
            // The name field: show the buffer + a caret so it reads as an editable field.
            row("> ${name}_", 0xFFFFFFFFL)
            gap(10f)
            if (name.isNotBlank()) button("Save", MENU_ACCENT) { cb.onSave(name) }
            button("Back", MENU_QUIT) { enterGame() }
        }
    }

    private fun about(ui: UiBuilder) {
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 24f) {
            title("About Cyto", 0x6FD6C4FFL)
            gap(6f)
            row("A cell-scale evolution sandbox: autotrophs bond", 0xC8C8C8FFL)
            row("matter under a sweeping daylight band, grow,", 0xC8C8C8FFL)
            row("divide, and evolve. Drag cells, inspect genomes,", 0xC8C8C8FFL)
            row("and watch a colony find its carrying capacity.", 0xC8C8C8FFL)
            gap(8f)
            row("Space pause  •  [ ] speed  •  F5/F9 save/load", 0x8B96A8FFL)
            gap(8f)
            button("Back", MENU_QUIT) { page = Page.Title }
        }
    }

    private fun settings(ui: UiBuilder) {
        ui.panel(Anchor.Center, padding = 20f, background = 0x141C2CF0, rowHeight = 24f) {
            title("Settings", 0x6FD6C4FFL)
            gap(6f)
            row("No global settings yet — world tuning lives", 0xC8C8C8FFL)
            row("under New > Custom.", 0xC8C8C8FFL)
            gap(8f)
            button("Back", MENU_QUIT) { page = Page.Title }
        }
    }

    companion object {
        private const val MENU_BTN = 0x2A3550FFL
        private const val MENU_ACCENT = 0x2E6E5EFFL
        private const val MENU_QUIT = 0x53384AFFL
        private const val MENU_DANGER = 0xB03A3AFFL

        private fun clamp(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
        private fun clampL(v: Long, lo: Long, hi: Long) = if (v < lo) lo else if (v > hi) hi else v
    }
}
