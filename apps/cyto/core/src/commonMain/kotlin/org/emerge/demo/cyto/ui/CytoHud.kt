package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CellColorMode
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.render.torus.ui.ActionButton
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.PanelBuilder
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

    /** Draw the bar + any open sheet. [onMenu] opens the host's full-screen menu (title/new/load/save).
     *  [wide] chooses the sheet container: a bottom sheet on a phone, a centred popover on desktop (matching
     *  the gene editor's wide treatment).
     *
     *  Hosts that also draw a bottom-centre panel (the desktop campaign coach) must call [renderBar] and
     *  [renderSheets] instead — see [renderBar] for why. */
    fun render(b: UiBuilder, controls: CytoControls, wide: Boolean, onMenu: () -> Unit) {
        renderBar(b, controls, onMenu = onMenu)
        renderSheets(b, controls, wide)
    }

    /** The **top-left sim-speed cluster** (desktop): `<< · N TPS/PAUSED · >>`. SLOW halves / FAST doubles the
     *  target TPS (bounds + auto-drop live in the driver); the middle button toggles pause and reads the live
     *  target. SLOW/FAST dim when unavailable ([CytoControls.slowEnabled]/[fastEnabled]). Gated by
     *  [CytoControls.showSimSpeed] so single-threaded hosts (which have no TPS target) don't show it. */
    fun renderSpeed(b: UiBuilder, controls: CytoControls) {
        if (!controls.showSimSpeed) return
        val mid = if (controls.simPaused) "PAUSED" else "${controls.simTps} TPS"
        val midColor = if (controls.simBehind) 0xEFB000FFL else 0x2E6E5EFFL
        b.panel(Anchor.TopLeft, margin = 10f, padding = 8f, background = 0x11182AF2L, rowHeight = 30f, textSize = 14f) {
            controlRow(listOf(
                ActionButton("<<", 0x3A6EA5FFL, enabled = controls.slowEnabled) { controls.onSlower() },
                ActionButton(mid, midColor) { controls.onTogglePause() },
                ActionButton(">>", 0x3A6EA5FFL, enabled = controls.fastEnabled) { controls.onFaster() },
            ))
            if (controls.simStatus.isNotEmpty()) row(controls.simStatus, 0x8FA4C8FFL)
        }
    }

    /** Draw just the bottom bar. Call this **before** any other `Anchor.BottomCenter` panel: that anchor's
     *  panels stack in *draw* order (the first to claim it sits on the bottom edge, later ones pile upward),
     *  so drawing the bar late strands it in mid-screen above the coach. Pair with [renderSheets], drawn last
     *  so an open sheet layers over everything rather than under the coach. */
    /** [showPause] draws the play/pause target in the bar (single-threaded hosts, whose speed lives in the
     *  Speed sheet). Desktop passes false — its pause + speed live in the top-left [renderSpeed] cluster. */
    fun renderBar(b: UiBuilder, controls: CytoControls, showPause: Boolean = true, onMenu: () -> Unit) = bar(b, controls, showPause, onMenu)

    /** Draw whichever sheet is open (none by default). Call **last** in the frame — sheets are the topmost
     *  layer, and the wide popover overlaps whatever sits beneath it. */
    fun renderSheets(b: UiBuilder, controls: CytoControls, wide: Boolean) {
        when (open) {
            Sheet.None -> {}
            Sheet.Speed -> speedSheet(b, controls, wide)
            Sheet.Brush -> brushSheet(b, controls, wide)
            Sheet.Layers -> layersSheet(b, controls, wide)
        }
    }

    private fun toggle(s: Sheet) { open = if (open == s) Sheet.None else s }

    /** Chooses the sheet container by width: a bottom sheet on a phone, a centred popover on desktop. */
    private fun sheetHost(b: UiBuilder, id: String, title: String, wide: Boolean, heightFraction: Float, body: PanelBuilder.() -> Unit) {
        if (wide) {
            val w = minOf(460f * b.density, b.screenW * 0.5f)
            val h = minOf(b.screenH * 0.85f, b.screenH * maxOf(heightFraction, 0.35f))
            b.sheet(id, title, onDismiss = ::close, boxX = (b.screenW - w) * 0.5f, boxY = (b.screenH - h) * 0.5f, boxW = w, boxH = h, rowHeight = 34f, textSize = 15f, body = body)
        } else {
            b.sheet(id, title, onDismiss = ::close, heightFraction = heightFraction, rowHeight = 48f, textSize = 16f, body = body)
        }
    }

    private fun bar(b: UiBuilder, controls: CytoControls, showPause: Boolean, onMenu: () -> Unit) {
        val playLabel = if (controls.simPaused) "PLAY" else "PAUSE"
        val playColor = if (controls.simBehind) 0xEFB000FFL else 0x3A6EA5FFL
        b.panel(Anchor.BottomCenter, margin = 10f, padding = 8f, background = 0x11182AF2L, rowHeight = 46f, textSize = 15f) {
            val row = buildList {
                if (showPause) add(Triple(playLabel, playColor) { controls.onTogglePause(); toggle(Sheet.Speed) })
                add(Triple("BRUSH", 0x2E6E5EFFL) { toggle(Sheet.Brush) })
                add(Triple("LAYERS", 0x5A4A8AFFL) { toggle(Sheet.Layers) })
                add(Triple("MENU", 0x2A3550FFL) { open = Sheet.None; onMenu() })
            }
            actionRow(row)
        }
    }

    private fun speedSheet(b: UiBuilder, controls: CytoControls, wide: Boolean) {
        sheetHost(b, "hud-speed", "SPEED", wide, heightFraction = 0.34f) {
            if (controls.simStatus.isNotEmpty()) { row(controls.simStatus, 0x8FE39AFFL); gap(6f) }
            actionRow(listOf(
                Triple("<<  SLOWER", 0x3A6EA5FFL) { controls.onSlower() },
                Triple(if (controls.simPaused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { controls.onTogglePause() },
                Triple("FASTER  >>", 0x3A6EA5FFL) { controls.onFaster() },
            ))
        }
    }

    private fun brushSheet(b: UiBuilder, controls: CytoControls, wide: Boolean) {
        sheetHost(b, "hud-brush", "BRUSH", wide, heightFraction = 0.6f) {
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

    private fun layersSheet(b: UiBuilder, controls: CytoControls, wide: Boolean) {
        sheetHost(b, "hud-layers", "LAYERS", wide, heightFraction = 0.5f) {
            listRow("NIGHT LIGHT:  ${controls.nightLabel}") { controls.cycleNightLevel() }
            listRow("CELL COLOUR:  ${controls.colorMode.label}") { controls.cycleColorMode() }
            if (controls.showMutation) listRow("MUTATION:  ${controls.mutationLabel}") { controls.onCycleMutation() }
            listRow(if (controls.showChemicals) "READOUTS:  ON" else "READOUTS:  OFF") { controls.toggleChemicals() }
        }
    }
}
