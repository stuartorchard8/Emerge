package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CellColorMode
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.UiBuilder

/**
 * The **L0 world chrome** (`apps/cyto/UI_REDESIGN.md` §3), built on the shared [UiBuilder] toolkit — the
 * mobile-first replacement for the scattered [CytoControls] buttons. A four-target bottom bar
 * (`⏸/▶ · Brush · Layers · Menu`) whose middle two open bottom sheets:
 *
 * - **Speed** — pause + the SLOW/FAST ladder + the live TPS/FPS readout.
 * - **Brush** — the genome palette (or legacy cell types) + touch mode.
 * - **Layers** — the light/matter overlay, colour mode, mutation rate, debug readouts.
 *
 * It does **not** own state: it reads and drives the same [CytoControls] model the legacy overlay uses, so
 * the two stay in sync while both exist. Only cross-frame UI state (which sheet is open) lives here.
 * Rendered in the narrow (phone) layout when the world is the top level — i.e. no cell is selected.
 */
class CytoHud {
    private enum class Sheet { None, Speed, Brush, Layers }
    private var open = Sheet.None

    /** Close any open sheet (e.g. when a cell is selected and the L0 view is left). */
    fun close() { open = Sheet.None }

    /** Draw the bar + any open sheet. [onMenu] opens the host's full-screen menu (title/new/load/save). */
    fun render(b: UiBuilder, controls: CytoControls, onMenu: () -> Unit) {
        bar(b, controls, onMenu)
        when (open) {
            Sheet.None -> {}
            Sheet.Speed -> speedSheet(b, controls)
            Sheet.Brush -> brushSheet(b, controls)
            Sheet.Layers -> layersSheet(b, controls)
        }
    }

    private fun toggle(s: Sheet) { open = if (open == s) Sheet.None else s }

    private fun bar(b: UiBuilder, controls: CytoControls, onMenu: () -> Unit) {
        val playLabel = if (controls.simPaused) "PLAY" else "PAUSE"
        val playColor = if (controls.simBehind) 0xEFB000FFL else 0x3A6EA5FFL
        b.panel(Anchor.BottomCenter, margin = 10f, padding = 8f, background = 0x11182AF2L, rowHeight = 46f, textSize = 15f) {
            actionRow(listOf(
                Triple(playLabel, playColor) { controls.onTogglePause(); toggle(Sheet.Speed) },
                Triple("BRUSH", 0x2E6E5EFFL) { toggle(Sheet.Brush) },
                Triple("LAYERS", 0x5A4A8AFFL) { toggle(Sheet.Layers) },
                Triple("MENU", 0x2A3550FFL) { open = Sheet.None; onMenu() },
            ))
        }
    }

    private fun speedSheet(b: UiBuilder, controls: CytoControls) {
        b.sheet("hud-speed", "SPEED", onDismiss = ::close, heightFraction = 0.34f, rowHeight = 48f, textSize = 16f) {
            if (controls.simStatus.isNotEmpty()) { row(controls.simStatus, 0x8FE39AFFL); gap(6f) }
            actionRow(listOf(
                Triple("<<  SLOWER", 0x3A6EA5FFL) { controls.onSlower() },
                Triple(if (controls.simPaused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { controls.onTogglePause() },
                Triple("FASTER  >>", 0x3A6EA5FFL) { controls.onFaster() },
            ))
        }
    }

    private fun brushSheet(b: UiBuilder, controls: CytoControls) {
        b.sheet("hud-brush", "BRUSH", onDismiss = ::close, heightFraction = 0.6f, rowHeight = 44f, textSize = 16f) {
            row("PAINT", 0x7A8699FFL)
            val palette = controls.genomePalette
            if (palette.isNotEmpty()) {
                palette.forEachIndexed { i, (name, color) ->
                    listRow(name.uppercase(), selected = i == controls.selectedGenome) {
                        controls.onSelectGenome(i); close()
                    }
                }
            } else {
                row("no genome brushes", 0x707070FFL)
            }
            gap(8f)
            row("TOUCH MODE", 0x7A8699FFL)
            for (mode in TouchMode.entries) {
                listRow(mode.name.uppercase(), selected = mode == controls.touchMode) { controls.setTouchMode(mode) }
            }
        }
    }

    private fun layersSheet(b: UiBuilder, controls: CytoControls) {
        b.sheet("hud-layers", "LAYERS", onDismiss = ::close, heightFraction = 0.5f, rowHeight = 48f, textSize = 16f) {
            listRow(if (controls.showMatterField) "OVERLAY:  MATTER" else "OVERLAY:  LIGHT") { controls.toggleMatterField() }
            listRow("CELL COLOUR:  ${controls.colorMode.label}") { controls.cycleColorMode() }
            if (controls.showMutation) listRow("MUTATION:  ${controls.mutationLabel}") { controls.onCycleMutation() }
            listRow(if (controls.showChemicals) "READOUTS:  ON" else "READOUTS:  OFF") { controls.toggleChemicals() }
        }
    }
}
