package org.emerge.demo.fluidlab

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.world.AirField
import org.emerge.demo.fluidlab.world.Temperature
import org.emerge.demo.fluidlab.world.fluid.AMBIENT_PRESSURE
import org.emerge.demo.fluidlab.world.fluid.EdgeGrid
import org.emerge.render.torus.GPU
import org.emerge.render.torus.ui.UiRectRenderer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the tile tint means. The lab is mostly *looking* at fields, so this is the main control. */
enum class Overlay {
    /** Air density relative to ambient — the plain "where is the gas" view. */
    Density,

    /** Pressure relative to ambient. Not the same as density: heavy gas is dense but not high-pressure. */
    Pressure,

    /** Kelvin, ramped either side of ambient. */
    Heat,

    /** Speed on the faces, so circulation and jets are visible. */
    Flow,

    /** Which species dominates the tile, in its own colour. */
    Species,
}

/**
 * Draws the grid, and owns the camera.
 *
 * Everything here runs on the GL thread and only on the GL thread — construct it after the context
 * is current, never in a field initialiser that runs earlier.
 *
 * Unlike the template's instanced discs, this is one quad per tile through [UiRectRenderer], which is
 * what Out of Space used and is plenty: a lab grid is thousands of cells, not millions of entities.
 * The camera is in **tile** coordinates rather than world units, so a zoom keeps whole tiles aligned
 * to pixels and the field stays readable rather than shimmering.
 */
class FluidlabRenderer {

    private val rects = UiRectRenderer(maxRects = MAX_RECTS)

    private var resW = 1f
    private var resH = 1f

    var camX = 0f
        private set
    var camY = 0f
        private set
    var tilePx = 22f
        private set

    private val centers = FloatArray(MAX_RECTS * 2)
    private val halfSizes = FloatArray(MAX_RECTS * 2)
    private val colors = FloatArray(MAX_RECTS * 4)
    private var count = 0

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = max(1f, widthPx)
        resH = max(1f, heightPx)
        GPU.setViewport(0, 0, resW.toInt(), resH.toInt())
    }

    /** Centres the camera on the grid and picks a zoom that fits it — the "I just want to see it" call. */
    fun fitTo(state: FluidlabState) {
        camX = state.grid.width * 0.5f
        camY = state.grid.height * 0.5f
        val fit = min(resW / state.grid.width, resH / state.grid.height)
        tilePx = fit.coerceIn(MIN_TILE_PX, MAX_TILE_PX)
    }

    fun panByPixels(dxPixels: Float, dyPixels: Float) {
        camX -= dxPixels / tilePx
        camY -= dyPixels / tilePx
    }

    fun zoomBy(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        tilePx = (tilePx * factor).coerceIn(MIN_TILE_PX, MAX_TILE_PX)
    }

    /** Framebuffer pixel → tile index, or -1 if that is off the grid. */
    fun tileAt(state: FluidlabState, px: Float, py: Float): Int {
        val x = ((px - resW * 0.5f) / tilePx + camX).toInt()
        val y = ((py - resH * 0.5f) / tilePx + camY).toInt()
        return if (state.grid.inBounds(x, y)) state.grid.index(x, y) else -1
    }

    fun draw(state: FluidlabState, overlay: Overlay = Overlay.Density, hoveredTile: Int = -1) {
        count = 0
        GPU.enableBlend()

        val grid = state.grid
        val structure = state.structure()

        // Backdrop, so vacuum outside the hull reads as empty rather than as whatever was last drawn.
        for (tile in 0 until grid.size) {
            val x = grid.xOf(tile)
            val y = grid.yOf(tile)
            if (state.walls[tile] != null) {
                tileRect(x, y, 1f, WALL)
                continue
            }
            tileRect(x, y, 1f, if (structure.isContained(tile)) INTERIOR else VACUUM)
            val tint = when (overlay) {
                Overlay.Density -> ramp(state.air.densityAt(tile).toFloat() / AirField.AMBIENT_AIR.total)
                Overlay.Pressure -> ramp(state.air.pressureAt(tile).toFloat() / AMBIENT_PRESSURE)
                Overlay.Heat -> heatColor(state.air.kelvinAt(tile))
                Overlay.Flow -> 0L
                Overlay.Species -> speciesTint(state.air, tile)
            }
            if (tint != 0L) tileRect(x, y, 1f, tint)
        }

        if (overlay == Overlay.Flow) drawFlow(state)

        if (hoveredTile >= 0) tileRect(grid.xOf(hoveredTile), grid.yOf(hoveredTile), 1f, HOVER)

        rects.drawInstanced(count, centers, halfSizes, colors)
        GPU.disableBlend()
    }

    /**
     * Face momentum as a streak from each tile centre.
     *
     * Scaled to the fastest face on screen rather than to a fixed constant, because the interesting
     * range spans orders of magnitude — a breach jet and ordinary convection cannot share a scale, and
     * a fixed one renders whichever it was not tuned for as either blank or saturated.
     */
    private fun drawFlow(state: FluidlabState) {
        val grid = state.grid
        val edges = EdgeGrid(grid)
        var peak = 0f
        val speeds = FloatArray(grid.size)
        for (tile in 0 until grid.size) {
            if (state.walls[tile] != null) continue
            val x = grid.xOf(tile)
            val y = grid.yOf(tile)
            // Tile-centred velocity is the mean of its two opposing faces — the staggered grid stores
            // them on the faces precisely so this averaging is the only place centring happens.
            val vx = (state.momentumX[edges.xEdge(x, y)] + state.momentumX[edges.xEdge(x + 1, y)]) * 0.5f
            val vy = (state.momentumY[edges.yEdge(x, y)] + state.momentumY[edges.yEdge(x, y + 1)]) * 0.5f
            val s = sqrt(vx * vx + vy * vy)
            speeds[tile] = s
            if (s > peak) peak = s
        }
        if (peak <= 0f) return
        for (tile in 0 until grid.size) {
            val s = speeds[tile]
            if (s <= 0f) continue
            val f = (s / peak).coerceIn(0f, 1f)
            tileRect(grid.xOf(tile), grid.yOf(tile), 0.2f + 0.6f * f, flowColor(f))
        }
    }

    fun cleanup() = rects.deleteProgram()

    // ── Colour ───────────────────────────────────────────────────────────────────

    /** Diverging ramp about 1.0 = ambient: blue below, red above. */
    private fun ramp(f: Float): Long {
        if (f <= 0f) return 0L
        val d = ((f - 1f) / SPAN).coerceIn(-1f, 1f)
        return if (d <= 0f) {
            val c = -d
            rgba((90 * (1f - c) + 20 * c).toInt(), (120 * (1f - c) + 60 * c).toInt(), (200).toInt(), ALPHA)
        } else {
            rgba((90 + 165 * d).toInt(), (120 * (1f - d) + 40 * d).toInt(), (200 * (1f - d) + 40 * d).toInt(), ALPHA)
        }
    }

    private fun heatColor(kelvin: Int): Long {
        val d = ((kelvin - Temperature.AMBIENT_KELVIN).toFloat() / RAMP_SPAN).coerceIn(-1f, 1f)
        return if (d <= 0f) {
            rgba((60 * (1f + d)).toInt() + 20, (90 * (1f + d)).toInt() + 30, 220, ALPHA)
        } else {
            rgba(220, (140 * (1f - d)).toInt() + 40, (60 * (1f - d)).toInt() + 20, ALPHA)
        }
    }

    private fun flowColor(f: Float): Long = rgba(220, (200 * (1f - f)).toInt() + 40, 60, (120 + 120 * f).toInt())

    private fun speciesTint(air: AirField, tile: Int): Long {
        var best: Species? = null
        var bestGrams = 0L
        for (s in Species.ALL) {
            val g = air.gramsOf(tile, s)
            if (g > bestGrams) { bestGrams = g; best = s }
        }
        if (best == null) return 0L
        val c = org.emerge.demo.fluidlab.chem.speciesColor(best)
        // Palette colours are opaque; the overlay sits on top of the backdrop, so re-alpha it.
        return (c and 0xFFFFFF00L) or ALPHA.toLong()
    }

    private fun rgba(r: Int, g: Int, b: Int, a: Int): Long =
        ((r.coerceIn(0, 255).toLong() shl 24) or (g.coerceIn(0, 255).toLong() shl 16) or
            (b.coerceIn(0, 255).toLong() shl 8) or a.coerceIn(0, 255).toLong())

    // ── Batching ─────────────────────────────────────────────────────────────────

    private fun tileRect(x: Int, y: Int, scale: Float, color: Long) =
        rect((x + 0.5f) * tilePx, (y + 0.5f) * tilePx, scale * tilePx, scale * tilePx, color)

    /** [wx],[wy] are world pixels (tile units × [tilePx]); converted to NDC here. */
    private fun rect(wx: Float, wy: Float, w: Float, h: Float, color: Long) {
        if (count >= MAX_RECTS) return
        val px = wx - camX * tilePx + resW * 0.5f
        val py = wy - camY * tilePx + resH * 0.5f
        // Cheap off-screen reject: a lab grid is small, but a zoomed-in view still culls most of it.
        if (px + w < 0f || px - w > resW || py + h < 0f || py - h > resH) return
        centers[count * 2] = px / resW * 2f - 1f
        centers[count * 2 + 1] = 1f - py / resH * 2f
        halfSizes[count * 2] = w / resW
        halfSizes[count * 2 + 1] = h / resH
        colors[count * 4] = ((color shr 24) and 0xFF).toFloat() / 255f
        colors[count * 4 + 1] = ((color shr 16) and 0xFF).toFloat() / 255f
        colors[count * 4 + 2] = ((color shr 8) and 0xFF).toFloat() / 255f
        colors[count * 4 + 3] = (color and 0xFF).toFloat() / 255f
        count++
    }

    companion object {
        private const val MAX_RECTS = 32_000
        private const val MIN_TILE_PX = 4f
        private const val MAX_TILE_PX = 64f

        /** Fraction either side of ambient that saturates the density/pressure ramp. */
        private const val SPAN = 1.0f

        /** Kelvin either side of ambient that saturates the heat ramp. */
        private const val RAMP_SPAN = 60f

        private const val ALPHA = 190

        private const val WALL = 0x5A6472FFL
        private const val INTERIOR = 0x10141AFFL
        private const val VACUUM = 0x05070AFFL
        private const val HOVER = 0xFFFFFF30L
    }
}
