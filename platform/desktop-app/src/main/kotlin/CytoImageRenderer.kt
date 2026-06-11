package org.emerge.desktop

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Headless PNG view of the Cyto world — the **light field as a heatmap** plus the cells on top of it,
 * so the energy landscape is visible (and verifiable without the GPU host). Two panels: the whole
 * torus (the four light sources + where colonies sit) and a zoom on the seed colony at source 0.
 * `--args="<outPng> <ticks>"` (defaults: build/cyto-field.png, 400).
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrElse(0) { "build/cyto-field.png" })
    val ticks = args.getOrNull(1)?.toIntOrNull() ?: 400

    val cfg = CytoConfig()
    val reducer = CytoReducer()
    val input = mapOf(PlayerId(0) to CytoInput.EMPTY)
    var state = createCytoInitialState()
    repeat(ticks) { state = reducer.reduce(cfg, state, input) }

    val panel = 520
    val img = BufferedImage(panel * 2 + 12, panel, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(20, 18, 26); g.fillRect(0, 0, img.width, img.height)

    // Full torus: logical [-HALF, HALF) → [0, panel).
    drawView(img, g, state, 0, panel, centreX = 0f, centreY = 0f, halfWindow = CytoLightField.HALF, label = "torus (4 light sources)")
    // Zoom on source 0 (the seed colony).
    val (sx, sy) = CytoLightField.SOURCES.first()
    drawView(img, g, state, panel + 12, panel, centreX = sx, centreY = sy, halfWindow = 70f, label = "zoom @ source 0")

    g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out)
    val cells = state.components.getTable<CytoCellComponent>().asMap().size
    println("wrote ${out.absolutePath}  (ticks=$ticks, cells=$cells)")
}

/** One square panel at x-offset [ox]: the light-field heatmap over [-halfWindow,halfWindow] around
 *  ([centreX],[centreY]) in logical units, with the cells drawn on top. */
private fun drawView(
    img: BufferedImage, g: java.awt.Graphics2D, state: SimState, ox: Int, size: Int,
    centreX: Float, centreY: Float, halfWindow: Float, label: String,
) {
    val field = CytoLightField.default()
    val span = 2f * halfWindow
    val hi = CytoLightField.STRENGTH.toFloat()
    fun pxToLogical(p: Int) = (p.toFloat() / size) * span - halfWindow
    // Heatmap (sample per pixel; a one-off render, so cost is irrelevant).
    for (py in 0 until size) {
        val ly = centreY + pxToLogical(py)
        for (px in 0 until size) {
            val lx = centreX + pxToLogical(px)
            val t = (field.sampleAt(lx, ly).toFloat() / hi).coerceIn(0f, 1f)
            img.setRGB(ox + px, py, heat(t).rgb)
        }
    }
    // Cells.
    val transforms = state.components.getTable<TransformComponent>()
    val pxPerLogical = size / span
    for ((id, cell) in state.components.getTable<CytoCellComponent>().asMap()) {
        val pos = transforms[id]?.pos ?: continue
        val lx = CytoUnits.toLogical(pos.x); val ly = CytoUnits.toLogical(pos.y)
        val sxp = ox + ((lx - centreX + halfWindow) * pxPerLogical)
        val syp = (ly - centreY + halfWindow) * pxPerLogical
        if (sxp < ox - 4 || sxp > ox + size + 4 || syp < -4 || syp > size + 4) continue
        val r = (cell.logicalRadius.toFloat() * pxPerLogical).coerceAtLeast(2f)
        g.color = awt(cell.type.color)
        g.fillOval((sxp - r).toInt(), (syp - r).toInt(), (2 * r).toInt(), (2 * r).toInt())
        g.color = Color(0, 0, 0, 90)
        g.drawOval((sxp - r).toInt(), (syp - r).toInt(), (2 * r).toInt(), (2 * r).toInt())
    }
    g.color = Color(235, 232, 224); g.font = Font("SansSerif", Font.BOLD, 13)
    g.drawString(label, ox + 8, 18)
}

/** Dark → warm heat ramp for a normalized light level. */
private fun heat(t: Float): Color {
    val r = (16 + t * 239).toInt().coerceIn(0, 255)
    val gg = (12 + t * 218).toInt().coerceIn(0, 255)
    val b = (26 + t * (110 - 26)).toInt().coerceIn(0, 255)
    return Color(r, gg, b)
}

private fun awt(argb: Long): Color =
    Color(((argb ushr 24) and 0xFF).toInt(), ((argb ushr 16) and 0xFF).toInt(), ((argb ushr 8) and 0xFF).toInt())
