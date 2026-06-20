package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CellColorMode
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.render.torus.GPU
import org.emerge.render.torus.ui.UiRectRenderer
import org.emerge.render.torus.ui.UiTextRenderer
import kotlin.math.min

/**
 * On-screen control overlay, ported from Cyto's `CellWorldControls`: two collapsible
 * columns at the bottom-left — Cell Type and Touch Mode — each showing the current
 * selection and expanding (tap) into colour-swatch buttons of every option, grouped; plus
 * a Debug button at the bottom-right that toggles the chemical readouts.
 *
 * Owns the current [touchMode] / [cellType] / [showChemicals] selection (the single source
 * of truth the host reads). Cross-platform (desktop mouse + Android touch): the host routes
 * a pointer-down through [hitTest] first; a hit consumes the press so it isn't treated as a
 * world tap.
 *
 * The text glyphs are a simple bitmap font (look isn't the point) — layout, colours, and
 * behaviour follow the original.
 */
class CytoControls {
    var touchMode: TouchMode = TouchMode.Base
        private set
    var cellType: CellType = CellType.Stem
        private set

    /** When true, the cell-type column's selection is the loaded brush genome rather than [cellType] —
     *  the host paints with the brush. Selected via the "Brush" swatch in the type column. */
    var brushSelected: Boolean = false
        private set

    /** Make the brush the active type selection (e.g. right after a genome loads). */
    fun selectBrush() { brushSelected = true; openGroup = null }
    var showChemicals: Boolean = false
        private set

    /** Whether to draw the light-field heatmap (the host reads this and applies it to the renderer). */
    var showLightField: Boolean = true
        private set

    /** Cell display colour mode (the host reads this and applies it to the renderer). Cycled by the
     *  bottom-right "Color" button. */
    var colorMode: CellColorMode = CellColorMode.Bio
        private set

    private fun cycleColorMode() {
        val modes = CellColorMode.entries
        colorMode = modes[(colorMode.ordinal + 1) % modes.size]
    }

    /** Host action for the "Load Genome" button — (re)load the brush genome from its file. File IO
     *  lives in the host (this class is cross-platform), so the host wires this up. */
    var onLoadBrush: () -> Unit = {}

    // ── Mutation-rate control (gated by [showMutation]; host wires the cycle action + label) ────────────
    /** Show the bottom-right "Mut" button that cycles the mutation rate through a ladder. */
    var showMutation: Boolean = false
    var onCycleMutation: () -> Unit = {}
    /** Host-set each frame: the second line of the Mut button (e.g. "1/100k" or "off"). */
    var mutationLabel: String = ""

    // ── Sim-speed control (threaded desktop host only; [showSimSpeed] gates the whole row off by default
    //    so single-threaded web/android hosts are unaffected) ──────────────────────────────────────────
    /** Show the top-left SLOW / PAUSE / FAST buttons + the TPS/FPS readout. */
    var showSimSpeed: Boolean = false
    var onSlower: () -> Unit = {}
    var onFaster: () -> Unit = {}
    var onTogglePause: () -> Unit = {}
    /** Host-set each frame: the readout line (e.g. "256/512 TPS  60 FPS"), pause + behind flags. */
    var simStatus: String = ""
    var simPaused: Boolean = false
    var simBehind: Boolean = false
    private var simStatusX = 0f
    private var simStatusY = 0f
    private var simStatusH = 0f

    private enum class Group { CellType, TouchMode }
    private var openGroup: Group? = null

    private val rectShader = UiRectRenderer()
    private val text = UiTextRenderer()

    private var resW = 1f
    private var resH = 1f

    private class Btn(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val color: Long, val label: String, val action: () -> Unit,
    )

    private val buttons = ArrayList<Btn>()

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = widthPx.coerceAtLeast(1f)
        resH = heightPx.coerceAtLeast(1f)
    }

    /** Returns true if the pointer-down hit a control (and applied its action). */
    fun hitTest(px: Float, py: Float): Boolean {
        // Topmost first: expanded option buttons are appended after the base buttons.
        for (i in buttons.indices.reversed()) {
            val b = buttons[i]
            if (px >= b.x && px <= b.x + b.w && py >= b.y && py <= b.y + b.h) {
                b.action()
                return true
            }
        }
        return false
    }

    fun draw() {
        layout()
        // Fills.
        val n = buttons.size
        val centers = FloatArray(n * 2)
        val halfSizes = FloatArray(n * 2)
        val colors = FloatArray(n * 4)
        for (i in 0 until n) {
            val b = buttons[i]
            centers[i * 2] = (b.x + b.w * 0.5f) / resW * 2f - 1f
            centers[i * 2 + 1] = 1f - (b.y + b.h * 0.5f) / resH * 2f
            halfSizes[i * 2] = b.w / resW
            halfSizes[i * 2 + 1] = b.h / resH
            packColor(b.color, colors, i * 4, alpha = 0.85f)
        }
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        rectShader.drawInstanced(n, centers, halfSizes, colors)

        // Labels (contrast colour over each swatch).
        val labelHeight = (min(resW, resH) * 0.022f).coerceIn(9f, 18f)
        for (b in buttons) {
            val (tr, tg, tb) = contrastColor(b.color)
            text.drawCentered(
                b.label,
                centerXpx = b.x + b.w * 0.5f,
                centerYpx = b.y + b.h * 0.5f,
                pixelHeight = labelHeight,
                r = tr, g = tg, b = tb,
                resW = resW, resH = resH,
            )
        }
        // Sim-speed readout (left-anchored next to the speed buttons), amber when falling behind.
        if (showSimSpeed && simStatus.isNotEmpty()) {
            val color = when {
                simBehind -> 0xEFB000FFL   // amber: target not being met
                simPaused -> 0x9090A0FFL   // grey: paused
                else -> 0x33DD33FFL        // green: keeping up
            }
            val (r, g, b) = rgb(color)
            val approxW = simStatus.length * simStatusH * 0.62f
            text.drawCentered(simStatus, simStatusX + approxW * 0.5f, simStatusY, simStatusH, r, g, b, resW, resH)
        }
        GPU.disableBlend()
    }

    /** Draws a free text label (e.g. chemical readouts) centred at a screen pixel. */
    fun drawLabel(label: String, centerXpx: Float, centerYpx: Float, pixelHeight: Float, color: Long) {
        val (r, g, b) = rgb(color)
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        text.drawCentered(label, centerXpx, centerYpx, pixelHeight, r, g, b, resW, resH)
        GPU.disableBlend()
    }

    fun cleanup() {
        rectShader.deleteProgram()
        text.cleanup()
    }

    private fun layout() {
        buttons.clear()
        val bs = (min(resW, resH) / 7f).coerceIn(64f, 120f)
        val gap = bs / 4f
        val pad = bs / 3f
        val bottomY = resH - pad - bs

        // ── Sim-speed row (top-left): SLOW · PAUSE/PLAY · FAST, with the TPS/FPS readout to the right ──
        if (showSimSpeed) {
            val sbs = bs * 0.55f
            val sgap = sbs / 4f
            val topY = pad
            var sx = pad
            buttons.add(Btn(sx, topY, sbs, sbs, SIM_COLOR, "SLOW") { onSlower() }); sx += sbs + sgap
            buttons.add(Btn(sx, topY, sbs, sbs, SIM_COLOR, if (simPaused) "PLAY" else "PAUSE") { onTogglePause() }); sx += sbs + sgap
            buttons.add(Btn(sx, topY, sbs, sbs, SIM_COLOR, "FAST") { onFaster() }); sx += sbs + sgap
            simStatusX = sx + sgap
            simStatusY = topY + sbs * 0.5f
            simStatusH = (sbs * 0.32f).coerceIn(9f, 18f)
        }

        // ── Cell Type column (bottom-left) — the legacy type swatches plus a "Brush" swatch that
        // paints with the loaded brush genome (see CytoController.brushGenome). ──
        val typeX = pad
        val headerLabel = if (brushSelected) "Brush\nGenome" else "${cellType.name}\nCell"
        val headerColor = if (brushSelected) GENE_COLOR else cellType.color
        if (openGroup == Group.CellType) {
            buttons.add(Btn(typeX, bottomY, bs, bs, headerColor, headerLabel) { openGroup = null })
            val rows = CellType.entries.groupBy { it.group }.values.toList()
            addOptionRows(
                groups = rows,
                baseY = bottomY, bs = bs, gap = gap, leftX = typeX,
                color = { it.color }, label = { it.name },
            ) { selected -> cellType = selected; brushSelected = false; openGroup = null }
            // "Brush" swatch on the row past the type rows.
            val brushY = bottomY - (rows.size + 1) * (bs + gap)
            buttons.add(Btn(typeX, brushY, bs, bs, GENE_COLOR, "Brush\nGenome") { brushSelected = true; openGroup = null })
        } else {
            buttons.add(Btn(typeX, bottomY, bs, bs, headerColor, headerLabel) { openGroup = Group.CellType })
        }

        // ── Touch Mode column (to the right of the type column) ──
        val modeX = pad + bs + gap
        if (openGroup == Group.TouchMode) {
            buttons.add(Btn(modeX, bottomY, bs, bs, touchMode.color, "${touchMode.name}\nMode") { openGroup = null })
            addOptionRows(
                groups = TouchMode.entries.groupBy { it.group }.values.toList(),
                baseY = bottomY, bs = bs, gap = gap, leftX = modeX,
                color = { it.color }, label = { it.name },
            ) { selected -> touchMode = selected; openGroup = null }
        } else {
            buttons.add(Btn(modeX, bottomY, bs, bs, touchMode.color, "${touchMode.name}\nMode") { openGroup = Group.TouchMode })
        }

        // ── Bottom-right cluster: Load Genome | Light toggle | Debug toggle ──
        val rightX = resW - pad - bs
        buttons.add(
            Btn(rightX, bottomY, bs, bs, DEBUG_COLOR, "Debug\n${if (showChemicals) "ON" else "OFF"}") {
                showChemicals = !showChemicals
            }
        )
        buttons.add(
            Btn(rightX - (bs + gap), bottomY, bs, bs, LIGHT_COLOR, "Light\n${if (showLightField) "ON" else "OFF"}") {
                showLightField = !showLightField
            }
        )
        buttons.add(
            Btn(rightX - 2f * (bs + gap), bottomY, bs, bs, GENE_COLOR, "Load\nGenome") { onLoadBrush() }
        )
        // Mutation-rate cycle (tap to step the ladder); desktop-gated like the sim-speed row.
        if (showMutation) buttons.add(
            Btn(rightX - 3f * (bs + gap), bottomY, bs, bs, SIM_COLOR, "Mut\n$mutationLabel") { onCycleMutation() }
        )
        // Cell colour-mode cycle (BIO ↔ CYT) — sits to the left of the cluster, past the optional Mut button.
        val colorSlot = if (showMutation) 4f else 3f
        buttons.add(
            Btn(rightX - colorSlot * (bs + gap), bottomY, bs, bs, COLOR_MODE_COLOR, "Color\n${colorMode.label}") { cycleColorMode() }
        )
    }

    private fun <T> addOptionRows(
        groups: List<List<T>>,
        baseY: Float, bs: Float, gap: Float, leftX: Float,
        color: (T) -> Long, label: (T) -> String,
        onSelect: (T) -> Unit,
    ) {
        var rowIndex = 1
        for (group in groups) {
            val y = baseY - rowIndex * (bs + gap)
            var x = leftX
            for (option in group) {
                buttons.add(Btn(x, y, bs, bs, color(option), label(option)) { onSelect(option) })
                x += bs + gap
            }
            rowIndex++
        }
    }

    private fun rgb(rgba: Long): Triple<Float, Float, Float> = Triple(
        ((rgba ushr 24) and 0xFF).toFloat() / 255f,
        ((rgba ushr 16) and 0xFF).toFloat() / 255f,
        ((rgba ushr 8) and 0xFF).toFloat() / 255f,
    )

    private fun packColor(rgba: Long, out: FloatArray, base: Int, alpha: Float) {
        out[base] = ((rgba ushr 24) and 0xFF).toFloat() / 255f
        out[base + 1] = ((rgba ushr 16) and 0xFF).toFloat() / 255f
        out[base + 2] = ((rgba ushr 8) and 0xFF).toFloat() / 255f
        out[base + 3] = alpha
    }

    private fun contrastColor(rgba: Long): Triple<Float, Float, Float> {
        val (r, g, b) = rgb(rgba)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance < 0.5f) Triple(1f, 1f, 1f) else Triple(0f, 0f, 0f)
    }

    companion object {
        private const val DEBUG_COLOR = 0x606060FFL
        private const val LIGHT_COLOR = 0xEFD040FFL   // warm — the light field
        private const val GENE_COLOR = 0x44CC55FFL    // green — matches the Collector swatch
        private const val SIM_COLOR = 0x3A6EA5FFL     // blue — the sim-speed controls
        private const val COLOR_MODE_COLOR = 0x8A5BC0FFL // purple — the cell colour-mode cycle
    }
}
