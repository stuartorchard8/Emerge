package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Belt
import org.emerge.demo.outofspace.world.Debris
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Analyzer
import org.emerge.demo.outofspace.world.Fabricator
import org.emerge.demo.outofspace.world.HeatField
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Miner
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
/** What the world is being looked at *through*. */
enum class Overlay(val label: String) {
    None("PLAIN"),
    Heat("HEAT"),
    Air("AIR"),
    ;

    /** What `H` cycles to next. One key beats three, and the HUD has buttons for direct picks. */
    val next: Overlay get() = entries[(ordinal + 1) % entries.size]
}

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

    fun draw(state: VesselState, hoveredIndex: Int = -1, overlay: Overlay = Overlay.None) {
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

        // Debris under the machines: it lies on the deck, and a conveyor spanning a heap should read
        // as running over it rather than as being buried by it.
        if (!state.debris.isEmpty) {
            for (tile in state.debris.tiles()) {
                val x = grid.xOf(tile)
                val y = grid.yOf(tile)
                if (x !in minX..maxX || y !in minY..maxY) continue
                drawDebris(state, tile, x, y)
            }
        }

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val index = grid.index(x, y)
                drawMachine(state, index, x, y, state.machines[index] ?: continue)
            }
        }

        // The overlay goes over the machines, not under them: the question it answers is "how hot is
        // it *there*", and putting it behind the thing you are asking about answers the wrong one.
        if (overlay != Overlay.None) {
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val index = grid.index(x, y)
                    val tint = when {
                        state.structure.isVacuum(index) -> 0x05070CD0L
                        overlay == Overlay.Heat -> temperatureColor(state.kelvinAt(index))
                        else -> pressureColor(state, index)
                    }
                    tileRect(x, y, 1f, tint)
                }
            }
        }

        if (hoveredIndex >= 0) {
            tileRect(grid.xOf(hoveredIndex), grid.yOf(hoveredIndex), 1f, 0xFFFFFF1AL)
        }

        rects.drawInstanced(count, centers, halfSizes, colors)
        GPU.disableBlend()
    }

    fun cleanup() = rects.deleteProgram()

    /**
     * A heap on the floor, its height set by how much is in it and its colour by what dominates.
     *
     * Drawn rising from the bottom of the tile rather than centred, because the whole point of the
     * thing is that it fell: a pile floating in the middle of a tile would say nothing about gravity.
     * A pile is deliberately never full-height — the deck stays readable underneath it.
     */
    private fun drawDebris(state: VesselState, tile: Int, x: Int, y: Int) {
        val mass = state.debris.massAt(tile)
        if (mass <= 0L) return
        val fill = (mass.toFloat() / Debris.TILE_CAP).coerceIn(0.05f, 1f)
        val h = 0.15f + fill * 0.6f
        rect(
            (x + 0.5f) * tilePx, (y + 1f - h * 0.5f) * tilePx,
            0.94f * tilePx, h * tilePx,
            packetColor(state.debris.mixtureAt(tile).dominant),
        )
        // A dark line along the top, so a heap does not read as a solid block of material.
        rect((x + 0.5f) * tilePx, (y + 1f - h) * tilePx, 0.94f * tilePx, 0.06f * tilePx, 0x00000060L)
    }

    // ── Machine drawing ───────────────────────────────────────────────────────

    private fun drawMachine(state: VesselState, index: Int, x: Int, y: Int, m: Machine) {
        // A machine with no activation is stopped, and saying so on the tile is the answer to the
        // only question wiring ever raises: why is this not running?
        if (m !is Sensor && m.wiring.activation(Action.Run, state.signals) <= 0) {
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
                // An instrument, drawn to look like nothing else on the grid. The first version used
                // the belt's own body colour with a bar across it, so in a line of belts it read as
                // "a belt with something on it" rather than as a separate machine.
                //
                // Frame colour is the channel it reports on — a frame rather than an edge notch,
                // because a notch is already the language for "material leaves this way" and it also
                // moves when the machine is rotated, which made the channel look like a direction.
                frame(x, y, m.channel.color)
                tileRect(x, y, 0.66f, 0x0E141CFFL)
                // The reading: colour is the species, size is its share. A blob rather than a bar so
                // it is orientation-independent, and it sits in the middle where the eye already is.
                if (m.lastDominant != null) {
                    val f = (m.lastPurity / 1000f).coerceIn(0f, 1f)
                    tileRect(x, y, 0.14f + 0.42f * f, speciesColor(m.lastDominant))
                }
                // Which way material leaves, kept thin and neutral so it cannot be read as the channel.
                edgeMark(x, y, m.facing, 0x8A94A4FFL)
                // And what is inside right now, drawn at belt-packet size so flow through it is visible.
                m.holding?.let { p ->
                    rect(
                        (x + 0.5f) * tilePx, (y + 0.5f) * tilePx,
                        0.16f * tilePx, 0.16f * tilePx,
                        packetColor(p.contents.dominant),
                    )
                }
            }
            is Hull -> tileRect(x, y, 1f, 0x4A5464FFL)
            is Sensor -> {
                tileRect(x, y, 0.94f, 0x24303CFFL)
                // The eye faces what it watches, and wears the colour it broadcasts on.
                edgeMark(x, y, m.facing, m.channel.color)
                tileRect(x, y, 0.3f, m.channel.color)
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

    /** A hollow square of [color] around the tile edge — a border, not a fill. */
    private fun frame(x: Int, y: Int, color: Long) {
        val thickness = 0.13f
        val span = 0.94f
        rect((x + 0.5f) * tilePx, (y + 0.5f - (span - thickness) * 0.5f) * tilePx, span * tilePx, thickness * tilePx, color)
        rect((x + 0.5f) * tilePx, (y + 0.5f + (span - thickness) * 0.5f) * tilePx, span * tilePx, thickness * tilePx, color)
        rect((x + 0.5f - (span - thickness) * 0.5f) * tilePx, (y + 0.5f) * tilePx, thickness * tilePx, span * tilePx, color)
        rect((x + 0.5f + (span - thickness) * 0.5f) * tilePx, (y + 0.5f) * tilePx, thickness * tilePx, span * tilePx, color)
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

    /**
     * Cold blue through neutral at room temperature to hot orange, at a fixed alpha so machine shapes
     * stay faintly readable underneath.
     *
     * The ramp is **absolute** — anchored at [HeatField.AMBIENT_KELVIN] rather than normalised to
     * whatever happens to be on screen — so the same colour always means the same temperature; a
     * relative ramp would make a cool vessel look as alarming as a burning one.
     *
     * But it spans only [RAMP_SPAN] kelvin either side of ambient, not the whole range down to space.
     * A working vessel lives within a few tens of degrees of comfortable, and the first attempt at
     * this used a 220K span, across which an 18K spread was a single flat wash of blue: technically
     * honest and completely useless. An absolute ramp still has to be scaled to the question.
     */
    private fun temperatureColor(kelvin: Int): Long {
        val alpha = 0xC8L
        val f = ((kelvin - HeatField.AMBIENT_KELVIN).toFloat() / RAMP_SPAN).coerceIn(-1f, 1f)
        return if (f <= 0f) {
            val c = -f
            rgba((0x50 - 0x30 * c).toInt(), (0xA0 - 0x60 * c).toInt(), (0xC0 - 0x30 * c).toInt(), alpha)
        } else {
            rgba((0x50 + 0xAF * f).toInt(), (0xA0 - 0x50 * f).toInt(), (0xC0 - 0xB0 * f).toInt(), alpha)
        }
    }

    /**
     * Air: hue is whichever gas dominates the tile, brightness is how much of it there is.
     *
     * Two facts in one colour because they are always asked together — a room can be full of the
     * wrong gas or short of the right one, and either is a problem you want to spot from across the
     * vessel rather than by pointing at tiles one at a time.
     */
    private fun pressureColor(state: VesselState, index: Int): Long {
        val pressure = state.air.pressureAt(index)
        if (pressure <= 0L) return 0x120A10D8L   // sealed but empty: a room that has lost its air
        val f = (pressure.toFloat() / AirField.AMBIENT_AIR.total).coerceIn(0.08f, 1.6f)
        val base = speciesColor(state.air.mixtureAt(index).dominant)
        val scale = (f / 1.6f).coerceIn(0.12f, 1f)
        return rgba(
            (((base shr 24) and 0xFF) * scale).toInt(),
            (((base shr 16) and 0xFF) * scale).toInt(),
            (((base shr 8) and 0xFF) * scale).toInt(),
            0xC8L,
        )
    }

    private fun rgba(r: Int, g: Int, b: Int, a: Long): Long =
        (r.coerceIn(0, 255).toLong() shl 24) or (g.coerceIn(0, 255).toLong() shl 16) or
            (b.coerceIn(0, 255).toLong() shl 8) or a

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

        /** Kelvin either side of ambient that saturates the heat ramp. */
        private const val RAMP_SPAN = 60f
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
    MachineKind.Hull -> 0x4A5464FFL
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
