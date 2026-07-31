package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Belt
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Analyzer
import org.emerge.demo.outofspace.world.Fabricator
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Node
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.massIn
import org.emerge.render.torus.GPU
import org.emerge.render.torus.ui.UiRectRenderer
import kotlin.math.floor
import kotlin.math.max

/**
 * Draws the vessel: tiles, machines, and every packet in transit.
 *
 * Everything is an axis-aligned rectangle, so the whole world goes out in **one instanced draw
 * call** through the engine's [UiRectRenderer] — no custom shader, and no per-tile draw overhead. A
 * tile game earns that simplicity; spending a shader on it before there is art would be premature.
 *
 * The camera lives here and works in tile units: [camX]/[camY] is the tile at the centre of the
 * screen and [tilePx] is the zoom. Screen y is down, matching the grid's +y and the direction
 * gravity will point when Phase 4 arrives.
 */
class OutofspaceRenderer {

    private val rects = UiRectRenderer(maxRects = MAX_RECTS)

    private var resW = 1f
    private var resH = 1f

    var camX = 0f
        private set
    var camY = 0f
        private set
    var tilePx = 34f
        private set

    // One flat batch, refilled each frame.
    private val centers = FloatArray(MAX_RECTS * 2)
    private val halfSizes = FloatArray(MAX_RECTS * 2)
    private val colors = FloatArray(MAX_RECTS * 4)
    private var count = 0

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = max(1f, widthPx)
        resH = max(1f, heightPx)
        GPU.setViewport(0, 0, resW.toInt(), resH.toInt())
    }

    /**
     * Centres the camera on what has actually been built, falling back to the middle of the grid
     * when nothing has. Centring on the grid instead would open the game looking at empty floor
     * beside the vessel, which is a poor first impression of a game about the vessel.
     */
    fun centreOn(state: VesselState) {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (i in state.machines.indices) {
            if (state.machines[i] == null) continue
            val x = state.grid.xOf(i)
            val y = state.grid.yOf(i)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX < minX) {
            camX = state.grid.width / 2f
            camY = state.grid.height / 2f
        } else {
            camX = (minX + maxX + 1) / 2f
            camY = (minY + maxY + 1) / 2f
        }
    }

    fun panByPixels(dxPixels: Float, dyPixels: Float) {
        camX -= dxPixels / tilePx
        camY -= dyPixels / tilePx
    }

    fun zoomAtScreen(px: Float, py: Float, factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val before = screenToTile(px, py)
        tilePx = (tilePx * factor).coerceIn(MIN_TILE_PX, MAX_TILE_PX)
        val after = screenToTile(px, py)
        camX += before[0] - after[0]
        camY += before[1] - after[1]
    }

    /** Framebuffer pixel → fractional tile coordinates. */
    fun screenToTile(px: Float, py: Float): FloatArray = floatArrayOf(
        camX + (px - resW * 0.5f) / tilePx,
        camY + (py - resH * 0.5f) / tilePx,
    )

    /** Framebuffer pixel → tile index, or -1 when the pointer is off the grid. */
    fun tileIndexAt(px: Float, py: Float, state: VesselState): Int {
        val t = screenToTile(px, py)
        val x = floor(t[0]).toInt()
        val y = floor(t[1]).toInt()
        return if (state.grid.inBounds(x, y)) state.grid.index(x, y) else -1
    }

    fun draw(state: VesselState, hoveredIndex: Int = -1) {
        GPU.setClearColor(0.05f, 0.06f, 0.08f, 1f)
        GPU.clearColorBuffer()
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        count = 0

        val grid = state.grid
        // Only the tiles actually on screen. Free at this size; the habit is what matters.
        val halfW = resW / (2f * tilePx)
        val halfH = resH / (2f * tilePx)
        val minX = max(0, floor(camX - halfW).toInt())
        val maxX = minOf(grid.width - 1, floor(camX + halfW).toInt() + 1)
        val minY = max(0, floor(camY - halfH).toInt())
        val maxY = minOf(grid.height - 1, floor(camY + halfH).toInt() + 1)

        // Floor, so the buildable area reads as a place rather than as a void.
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val shade = if ((x + y) and 1 == 0) 0x141A24FFL else 0x111722FFL
                tileRect(x, y, 1f, shade)
            }
        }

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val index = grid.index(x, y)
                drawMachine(state, index, x, y, state.machines[index] ?: continue)
            }
        }

        if (hoveredIndex >= 0) {
            tileRect(grid.xOf(hoveredIndex), grid.yOf(hoveredIndex), 1f, 0xFFFFFF1AL)
        }

        rects.drawInstanced(count, centers, halfSizes, colors)
        GPU.disableBlend()
    }

    fun cleanup() = rects.deleteProgram()

    // ── Machine drawing ───────────────────────────────────────────────────────

    private fun drawMachine(state: VesselState, index: Int, x: Int, y: Int, m: Machine) {
        // A machine with no activation is stopped, and saying so on the tile is the answer to the
        // only question wiring ever raises: why is this not running?
        if (m !is Sensor && m !is Node && m.wiring.activation(Action.Run, state.signals) <= 0) {
            tileRect(x, y, 0.94f, 0x1A1A20FFL)
            tileRect(x, y, 0.34f, 0x8A3030FFL)
            return
        }
        when (m) {
            is Belt -> {
                tileRect(x, y, 0.9f, 0x2A3242FFL)
                // A notch on the output edge: which way this belt runs, readable at a glance.
                edgeMark(x, y, m.facing, 0x6E7C94FFL)
                for (i in m.slots.indices) {
                    val packet = m.slots[i] ?: continue
                    val (ox, oy) = slotOffset(m.facing, i, m.slots.size)
                    // Slots sit 1/SLOTS of a tile apart, so a packet must be drawn narrower than
                    // that or four of them smear into one bar and the jam stops being countable.
                    val fill = (packet.mass.toFloat() / Capacity.PACKET_GRAMS).coerceIn(0.4f, 1f)
                    val scale = (0.78f / m.slots.size) * fill
                    rect(
                        (x + 0.5f + ox) * tilePx, (y + 0.5f + oy) * tilePx,
                        scale * tilePx, scale * tilePx,
                        packetColor(packet.contents.dominant),
                    )
                }
            }
            is Miner -> {
                tileRect(x, y, 0.94f, 0x6B4A2AFFL)
                edgeMark(x, y, m.facing, 0xD9A066FFL)
                fillBar(x, y, m.buffer.mass.toFloat() / Miner.BUFFER_CAP)
            }
            is Processor -> {
                tileRect(x, y, 0.94f, 0x2E5A6BFFL)
                edgeMark(x, y, m.facing, 0x7FD4EEFFL)
                edgeMark(x, y, m.facing.clockwise, 0x6B5A2EFFL)   // where tailings leave
                fillBar(x, y, massIn(m).toFloat() / BUFFER_BAR_FULL)
            }
            is Smelter -> {
                tileRect(x, y, 0.94f, 0x8A3A2AFFL)
                edgeMark(x, y, m.facing, 0xFFB05AFFL)
                edgeMark(x, y, m.facing.clockwise, 0x4A3A32FFL)   // where slag leaves
                fillBar(x, y, massIn(m).toFloat() / BUFFER_BAR_FULL)
            }
            is Fabricator -> {
                tileRect(x, y, 0.94f, 0x6B3A7AFFL)
                edgeMark(x, y, m.facing, 0xD9A0EEFFL)
                fillBar(x, y, massIn(m).toFloat() / (Fabricator.INPUT_CAP * 2))
            }
            is Storage -> {
                tileRect(x, y, 0.94f, 0x3A4A5AFFL)
                edgeMark(x, y, m.facing, 0x8AA0B8FFL)
                // A storage shows its level as a rising fill, not a thin bar: it is a tank.
                val level = (m.contents?.mass ?: 0L).toFloat() / Storage.CAP
                if (level > 0f) {
                    val h = level.coerceIn(0f, 1f) * 0.8f
                    rect(
                        (x + 0.5f) * tilePx, (y + 0.9f - h * 0.5f) * tilePx,
                        0.8f * tilePx, h * tilePx,
                        packetColor(m.contents?.mixture?.dominant),
                    )
                }
            }
            is Analyzer -> {
                tileRect(x, y, 0.92f, 0x2A3242FFL)
                edgeMark(x, y, m.facing, m.channel.color)
                // The last thing it saw, as a bar of that species' colour scaled by its purity —
                // so a glance down a line shows where the ore gets cleaner.
                if (m.lastDominant != null) {
                    val w = (m.lastPurity / 1000f).coerceIn(0.1f, 1f) * 0.7f
                    rect(
                        (x + 0.15f + w * 0.5f) * tilePx, (y + 0.5f) * tilePx,
                        w * tilePx, 0.22f * tilePx,
                        packetColor(m.lastDominant),
                    )
                }
                m.holding?.let { p ->
                    rect((x + 0.5f) * tilePx, (y + 0.78f) * tilePx, 0.2f * tilePx, 0.2f * tilePx, packetColor(p.contents.dominant))
                }
            }
            is Sensor -> {
                tileRect(x, y, 0.94f, 0x24303CFFL)
                // The eye faces what it watches, and wears the colour it broadcasts on.
                edgeMark(x, y, m.facing, m.channel.color)
                tileRect(x, y, 0.3f, m.channel.color)
            }
            is Node -> {
                tileRect(x, y, 0.94f, 0x2E7A4AFFL)
                tileRect(x, y, 0.45f, 0xBFF5D0FFL)
            }
            is Vent -> {
                tileRect(x, y, 0.94f, 0x3A3A44FFL)
                tileRect(x, y, 0.4f, 0x0A0A0CFFL)
            }
        }
    }

    /** Position of belt slot [i] within its tile, in tile units from the centre. Slot 0 is the head. */
    private fun slotOffset(facing: Direction, i: Int, slots: Int): Pair<Float, Float> {
        val along = 0.5f - (i + 0.5f) / slots
        return (facing.dx * along) to (facing.dy * along)
    }

    /** A thin bar on the output edge, showing which way a machine sends things. */
    private fun edgeMark(x: Int, y: Int, dir: Direction, color: Long) {
        val cx = (x + 0.5f + dir.dx * 0.4f) * tilePx
        val cy = (y + 0.5f + dir.dy * 0.4f) * tilePx
        val hw = if (dir.dx != 0) 0.09f else 0.3f
        val hh = if (dir.dy != 0) 0.09f else 0.3f
        rect(cx, cy, hw * tilePx * 2f, hh * tilePx * 2f, color)
    }

    /** How full a machine is, along the bottom of its tile. The at-a-glance "is this backing up?". */
    private fun fillBar(x: Int, y: Int, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return
        val w = f * 0.8f
        rect(
            (x + 0.1f + w * 0.5f) * tilePx,
            (y + 0.86f) * tilePx,
            w * tilePx, 0.1f * tilePx,
            if (f > 0.85f) 0xE05A4AFFL else 0x9AE07AFFL,
        )
    }

    private fun packetColor(dominant: Species?): Long = speciesColor(dominant)

    // ── Primitives ────────────────────────────────────────────────────────────

    private fun tileRect(x: Int, y: Int, scale: Float, color: Long) =
        rect((x + 0.5f) * tilePx, (y + 0.5f) * tilePx, scale * tilePx, scale * tilePx, color)

    /** [wx],[wy] are world pixels (tile units × [tilePx]); converted to NDC here. */
    private fun rect(wx: Float, wy: Float, w: Float, h: Float, color: Long) {
        if (count >= MAX_RECTS) return
        val px = wx - camX * tilePx + resW * 0.5f
        val py = wy - camY * tilePx + resH * 0.5f
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
        private const val MAX_RECTS = 20_000
        private const val MIN_TILE_PX = 6f
        private const val MAX_TILE_PX = 64f

        /** Bar-full reference for machine buffers — a machine holding this much is visibly backed up. */
        private const val BUFFER_BAR_FULL = 4_000f
    }
}

/** Palette colour for a machine kind, shared by the renderer and the HUD's brush swatch. */
fun kindColor(kind: MachineKind): Long = when (kind) {
    MachineKind.Belt -> 0x2A3242FFL
    MachineKind.Miner -> 0x6B4A2AFFL
    MachineKind.Processor -> 0x2E5A6BFFL
    MachineKind.Smelter -> 0x8A3A2AFFL
    MachineKind.Fabricator -> 0x6B3A7AFFL
    MachineKind.Storage -> 0x3A4A5AFFL
    MachineKind.Sensor -> 0x24303CFFL
    MachineKind.Analyzer -> 0x2A3242FFL
    MachineKind.Node -> 0x2E7A4AFFL
    MachineKind.Vent -> 0x3A3A44FFL
}

/**
 * The colour a species is drawn in — shared by the renderer's packets and the HUD's readouts, so a
 * lump on a belt and its name in the inspector are unmistakably the same stuff.
 */
fun speciesColor(dominant: Species?): Long = when (dominant) {
    Species.Iron -> 0xB07A5AFFL
    Species.Aluminum -> 0xB8BCC4FFL
    Species.Copper -> 0xE08A3AFFL
    Species.Titanium -> 0xC8CCD4FFL
    Species.Silica -> 0xD8D0A8FFL
    Species.Carbon -> 0x484848FFL
    Species.RareEarth -> 0x6ED09AFFL
    Species.Uranium -> 0xA8E04AFFL
    Species.Oxygen -> 0x7AB8FFFFL
    Species.Nitrogen -> 0x9A9AD0FFL
    Species.CarbonDioxide -> 0x8A8A8AFFL
    Species.Water -> 0x4A8AD0FFL
    null -> 0x707070FFL
}
