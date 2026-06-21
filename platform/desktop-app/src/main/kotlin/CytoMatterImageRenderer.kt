package org.emerge.desktop

import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor

/**
 * Headless PNG view of the **matter quad-tree** — each leaf a 2px-bordered square coloured by its a/b/c
 * atom mix at low value (exactly the rule [org.emerge.demo.cyto.CytoRenderer] uses for the in-app overlay),
 * with the cells on top. This is a CPU/AWT mirror of the GPU overlay so the leaf geometry + colouring can
 * be eyeballed without the GPU host. `--args="<outPng> <ticks>"` (defaults: build/cyto-matter.png, 1200).
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrElse(0) { "build/cyto-matter.png" })
    val ticks = args.getOrNull(1)?.toIntOrNull() ?: 1200

    val sim = CytoSoaSim(CytoConfig(), createCytoInitialState())
    var state = sim.state()
    repeat(ticks) { state = sim.step() }

    val panel = 640
    val img = BufferedImage(panel * 2 + 12, panel, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    g.color = Color(0, 0, 0); g.fillRect(0, 0, img.width, img.height)

    // Full torus, then a zoom on the seed colony at the origin (where cells refine the tree).
    drawMatter(img, g, state, 0, panel, centreX = 0f, centreY = 0f, halfWindow = org.emerge.demo.cyto.sim.CytoLightField.HALF, label = "matter quad-tree (full torus)")
    drawMatter(img, g, state, panel + 12, panel, centreX = 0f, centreY = 0f, halfWindow = 18f, label = "zoom @ origin (refined leaves)")

    g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out)
    val grid = state.components.getTable<CytoMatterGridComponent>().asMap()[GRID_SINGLETON]?.grid
    var leaves = 0; grid?.forEachLeaf { _, _, _, _ -> leaves++ }
    val cells = state.components.getTable<CytoCellComponent>().asMap().size
    println("wrote ${out.absolutePath}  (ticks=$ticks, cells=$cells, leaves=$leaves)")
}

/** One panel: the matter leaves as bordered squares, cells on top, over [-halfWindow,halfWindow]. */
private fun drawMatter(
    img: BufferedImage, g: java.awt.Graphics2D, state: SimState, ox: Int, size: Int,
    centreX: Float, centreY: Float, halfWindow: Float, label: String,
) {
    g.color = Color(0, 0, 0); g.fillRect(ox, 0, size, size)
    val grid = state.components.getTable<CytoMatterGridComponent>().asMap()[GRID_SINGLETON]?.grid ?: return
    val span = 2f * halfWindow
    val pxPerLogical = size / span
    fun sx(lx: Float) = ox + (lx - centreX + halfWindow) * pxPerLogical
    fun sy(ly: Float) = (ly - centreY + halfWindow) * pxPerLogical

    val finestSize = org.emerge.demo.cyto.sim.CytoMatterField.TILE / (1 shl org.emerge.demo.cyto.sim.CytoMatterField.MAX_DEPTH)
    val refDensity = org.emerge.demo.cyto.sim.CytoSeed.MATTER_UNIFORM_LEVEL.toDouble()
    grid.forEachLeaf { x, y, leafSize, store ->
        val x0 = sx(x); val y0 = sy(y); val wpx = leafSize * pxPerLogical
        if (x0 + wpx < ox || x0 > ox + size || y0 + wpx < 0 || y0 > size) return@forEachLeaf
        // Fill = per-area a/b/c atom density as raw RGB, normalised so a full base-density leaf is white.
        var r = 0L; var gg = 0L; var b = 0L
        for (i in 0 until store.size) {
            val cnt = store.countAt(i)
            for (ch in SpeciesRegistry.string(store.idAt(i))) when (ch) {
                'a' -> r += cnt; 'b' -> gg += cnt; 'c' -> b += cnt
            }
        }
        val across = (leafSize / finestSize).toDouble(); val denom = across * across * refDensity
        g.color = Color((r / denom).coerceIn(0.0, 1.0).toFloat(), (gg / denom).coerceIn(0.0, 1.0).toFloat(), (b / denom).coerceIn(0.0, 1.0).toFloat())
        g.fillRect(x0.toInt(), y0.toInt(), wpx.toInt().coerceAtLeast(1), wpx.toInt().coerceAtLeast(1))
        // 2px grey border.
        g.color = Color(102, 102, 102)
        g.stroke = BasicStroke(2f)
        g.drawRect(x0.toInt(), y0.toInt(), wpx.toInt().coerceAtLeast(1), wpx.toInt().coerceAtLeast(1))
    }

    // Cells on top — atom-mix hue at full value so they stand out against the dim overlay.
    val transforms = state.components.getTable<TransformComponent>()
    for ((id, cell) in state.components.getTable<CytoCellComponent>().asMap()) {
        val pos = transforms[id]?.pos ?: continue
        val lx = CytoUnits.toLogical(pos.x); val ly = CytoUnits.toLogical(pos.y)
        val cxp = sx(lx); val cyp = sy(ly)
        if (cxp < ox - 4 || cxp > ox + size + 4 || cyp < -4 || cyp > size + 4) continue
        var r = 0L; var gg = 0L; var b = 0L
        for ((species, count) in cell.biomass) for (ch in species) when (ch) {
            'a' -> r += count; 'b' -> gg += count; 'c' -> b += count
        }
        val rad = (cell.logicalRadius.toFloat() * pxPerLogical).coerceAtLeast(2f)
        g.color = hsv(hue(r.toFloat(), gg.toFloat(), b.toFloat()), if (r + gg + b > 0) 1f else 0f, 0.95f)
        g.fillOval((cxp - rad).toInt(), (cyp - rad).toInt(), (2 * rad).toInt(), (2 * rad).toInt())
    }

    g.color = Color(40, 40, 40); g.font = Font("SansSerif", Font.BOLD, 13)   // dark: the base fill is white
    g.drawString(label, ox + 8, 18)
}

/** Hue (0..1) of an (r,g,b) atom-count mix; 0 when colourless. Mirrors CytoRenderer.hueOf. */
private fun hue(r: Float, g: Float, b: Float): Float {
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
    if (d <= 0f) return 0f
    val h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return h / 6f
}

/** HSV → AWT Color. Mirrors CytoRenderer.hsvToRgb. */
private fun hsv(h: Float, s: Float, v: Float): Color {
    if (s <= 0f) return Color(v, v, v)
    val hh = (h - floor(h)) * 6f
    val i = hh.toInt(); val f = hh - i
    val p = v * (1f - s); val q = v * (1f - s * f); val t = v * (1f - s * (1f - f))
    return when (i) {
        0 -> Color(v, t, p); 1 -> Color(q, v, p); 2 -> Color(p, v, t)
        3 -> Color(p, q, v); 4 -> Color(t, p, v); else -> Color(v, p, q)
    }
}
