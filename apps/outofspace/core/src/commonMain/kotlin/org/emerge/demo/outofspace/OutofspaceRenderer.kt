package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Debris
import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.size
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.AirField
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
        GPU.setClearColor(0.05f, 0.06f, 0.08f, 1f) // dark blue-grey void
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
        // Machines are drawn from their centre tile, so one whose centre is just off screen can
        // still have half its body on it. Widen the machine pass by the largest footprint's reach.
        val mMinX = max(0, minX - MAX_REACH)
        val mMaxX = minOf(grid.width - 1, maxX + MAX_REACH)
        val mMinY = max(0, minY - MAX_REACH)
        val mMaxY = minOf(grid.height - 1, maxY + MAX_REACH)

        // Floor, so the buildable area reads as a place rather than as a void.
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val shade = if ((x + y) and 1 == 0) Colors.TILE_LIGHT else Colors.TILE_DARK
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

        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val index = grid.index(x, y)
                drawMachine(state, index, x, y, state.machines[index] ?: continue)
            }
        }

        // Track over the buildings, because it runs on top of the deck rather than being buried by
        // it — and because a run threaded under a smelter is unreadable if the smelter covers it.
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                drawRail(state, grid.index(x, y), x, y)
            }
        }

        // Bridges last of all, because a bridge is the one thing that is genuinely *above* the track.
        //
        // They get their own pass because they live on their own list. `drawMachine` has always had a
        // Bridge branch, but nothing ever reached it: that loop walks `state.machines`, and a bridge
        // is never in it. It was drawing correctly and invisibly.
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val index = grid.index(x, y)
                drawBridge(state, index, state.bridges[index] ?: continue, x, y)
            }
        }

        // The overlay goes over the machines, not under them: the question it answers is "how hot is
        // it *there*", and putting it behind the thing you are asking about answers the wrong one.
        if (overlay != Overlay.None) {
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val index = grid.index(x, y)
                    val tint = when {
                        state.structure.isVacuum(index) -> Colors.OVERLAY_VACUUM
                        overlay == Overlay.Heat -> temperatureColor(state.kelvinAt(index))
                        else -> pressureColor(state, index)
                    }
                    tileRect(x, y, 1f, tint)
                }
            }
        }

        if (hoveredIndex >= 0) {
            tileRect(grid.xOf(hoveredIndex), grid.yOf(hoveredIndex), 1f, Colors.HOVER)
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
        val fill = (mass.toFloat() / Debris.TILE_CAP).coerceIn(Visual.DEBRIS_MIN_HEIGHT, 1f)
        val h = Visual.DEBRIS_BASE_HEIGHT + fill * Visual.DEBRIS_MAX_HEIGHT
        rect(
            (x + 0.5f) * tilePx, (y + 1f - h * 0.5f) * tilePx,
            Visual.DEBRIS_TOP_WIDTH * tilePx, h * tilePx,
            packetColor(state.debris.mixtureAt(tile).dominant),
        )
        // A dark line along the top, so a heap does not read as a solid block of material.
        rect((x + 0.5f) * tilePx, (y + 1f - h) * tilePx, Visual.DEBRIS_TOP_WIDTH * tilePx, Visual.DEBRIS_TOP_HEIGHT * tilePx, Colors.DEBRIS_TOP)
    }

    /**
     * One tile of track, and whatever is riding on it.
     *
     * Drawn as a thin spine rather than a tile-filling block: it has to read as *running over* the
     * building beneath without hiding it, since the whole point of the layer is that both are there.
     * A gauge wears its channel as a collar.
     */
    private fun drawRail(state: VesselState, tile: Int, x: Int, y: Int) {
        val segment = state.rails[tile] ?: return
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        // Only the arms that are actually **joined**, so the picture is the graph. Track is no longer
        // connected by touching, and drawing a full cross on every tile would say the opposite of
        // what the network does — two lines running side by side would look like one grid.
        for (dir in Direction.ALL) {
            if (!segment.linkedTo(dir)) continue
            rect(
                cx + dir.dx * Visual.RAIL_PORTION * tilePx, cy + dir.dy * Visual.RAIL_PORTION * tilePx,
                (if (dir.dx != 0) Visual.RAIL_ARM_LONG else Visual.RAIL_ARM_SHORT) * tilePx,
                (if (dir.dy != 0) Visual.RAIL_ARM_LONG else Visual.RAIL_ARM_SHORT) * tilePx,
                kindColor(MachineKind.Rail),
            )
        }
        // The hub, always drawn
        rect(cx, cy, Visual.RAIL_HUB_SIZE * tilePx, Visual.RAIL_HUB_SIZE * tilePx, kindColor(MachineKind.Rail))
        segment.channel?.let { channel ->
            frame(x, y, channel.color)
        }
        val packet = segment.held ?: return
        // Size tracks how full the lump is, so a line of half-packets looks like one.
        val fill = (packet.mass.toFloat() / Capacity.PACKET_GRAMS).coerceIn(Visual.PACKET_MIN_FILL, 1f)
        val side = Visual.PACKET_FILL * fill
        rect((x + 0.5f) * tilePx, (y + 0.5f) * tilePx, side * tilePx, side * tilePx, packetColor(packet.contents.dominant))
    }

    /**
     * A bridge: an elevated track spanning its three tiles, drawn over any track it crosses.
     *
     * Off-color to signify that it is not part of the lower track except for its ports.
     */
    private fun drawBridge(state: VesselState, index: Int, b: Bridge, x: Int, y: Int) {
        val horizontal = b.facing.dx != 0
        val long = if (horizontal) Visual.BRIDGE_SPAN_X else Visual.BRIDGE_SPAN_Y
        val across = if (horizontal) Visual.BRIDGE_SPAN_Y else Visual.BRIDGE_SPAN_X
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        drawPorts(state, index, b)
        rect(cx, cy, (long - Visual.BRIDGE_INSET) * tilePx, (across - Visual.BRIDGE_INSET) * tilePx, kindColor(MachineKind.Bridge))
        b.held?.let {
            rect(cx, cy, Visual.BRIDGE_PACKET_SIZE * tilePx, Visual.BRIDGE_PACKET_SIZE * tilePx, packetColor(it.contents.dominant))
        }
    }

    // ── Machine drawing ───────────────────────────────────────────────────────

    private fun drawMachine(state: VesselState, index: Int, x: Int, y: Int, m: Machine) {
        val n = m.kind.size
        // A machine with no activation is stopped, and saying so on the tile is the answer to the
        // only question wiring ever raises: why is this not running?
        if (m !is Sensor && m.wiring.activation(Action.Run, state.signals) <= 0) {
            bodyRect(x, y, n, Visual.MACHINE_INSET, Colors.STOPPED_BODY)
            bodyRect(x, y, n, Visual.STOP_INDICATOR_SCALE, Colors.STOPPED_INDICATOR)
            drawPorts(state, index, m)
            return
        }
        when (m) {
            // Never reached: bridges are not on the deck list. They have their own pass.
            is Bridge -> Unit
            is Miner -> {
                bodyRect(x, y, n, Visual.MACHINE_INSET, kindColor(MachineKind.Miner))
                fillBar(x, y, n, m.buffer.mass.toFloat() / Miner.BUFFER_CAP)
            }
            is Processor -> {
                bodyRect(x, y, n, Visual.MACHINE_INSET, kindColor(MachineKind.Processor))
                fillBar(x, y, n, massIn(m).toFloat() / BUFFER_BAR_FULL)
            }
            is Smelter -> {
                bodyRect(x, y, n, Visual.MACHINE_INSET, kindColor(MachineKind.Smelter))
                fillBar(x, y, n, massIn(m).toFloat() / BUFFER_BAR_FULL)
            }
            is Storage -> {
                bodyRect(x, y, n, Visual.MACHINE_INSET, kindColor(MachineKind.Storage))
                // A tank shows its level as a rising fill, not a thin bar, and now it rises through
                // a room-sized body -- which is what makes a nearly-full warehouse legible across
                // the deck rather than a detail you have to hover to read.
                val level = (m.contents?.mass ?: 0L).toFloat() / Storage.CAP
                if (level > 0f) {
                    val h = level.coerceIn(0f, 1f) * (n - Visual.TANK_SPAN_INSET)
                    val bottom = y + n * 0.5f - Visual.TANK_BOTTOM_MARGIN
                    rect(
                        (x + 0.5f) * tilePx, (bottom - h * 0.5f) * tilePx,
                        (n - Visual.TANK_SPAN_INSET) * tilePx, h * tilePx,
                        packetColor(m.contents?.mixture?.dominant),
                    )
                }
            }
            is Hull -> tileRect(x, y, 1f, kindColor(MachineKind.Hull))
            is Sensor -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(MachineKind.Sensor))
                // The eye faces what it watches, and wears the colour it broadcasts on.
                edgeMark(x, y, m.facing, m.channel.color)
                tileRect(x, y, Visual.SENSOR_EYE_SCALE, m.channel.color)
            }
            is Vent -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(MachineKind.Vent))
                tileRect(x, y, Visual.VENT_CORE_SCALE, Colors.VENT_CORE)
            }
        }
        drawPorts(state, index, m)
    }

    /**
     * The body of a machine: a square of [span] tiles centred on its anchor tile, inset a little.
     *
     * Machines anchor at their centre, so this is the same expression for every size — a one-tile
     * conveyor and a five-tile furnace differ only in [span]. Drawing from a corner would need the
     * offset to depend on facing as well, since rotation would move the anchor.
     */
    private fun bodyRect(x: Int, y: Int, span: Int, inset: Float, color: Long) {
        val side = (span - (1f - inset)) * tilePx
        rect((x + 0.5f) * tilePx, (y + 0.5f) * tilePx, side, side, color)
    }

    /**
     * Every port on a machine, in ONI's language: **white in, green out**.
     *
     * Worth stating out loud on the tile, because with footprints "where does this connect" stops
     * being answerable from the machine's facing alone. A five-tile smelter has three ports on three
     * different edges, and a player who cannot see them has to guess.
     *
     * A port is drawn as a stub straddling the machine's boundary, so it reads as a fitting on the
     * wall rather than as cargo sitting inside.
     */
    private fun drawPorts(state: VesselState, index: Int, m: Machine) {
        for (port in portsOf(state.grid, m, index)) {
            val px = state.grid.xOf(port.tile)
            val py = state.grid.yOf(port.tile)
            val color = if (port.kind == PortKind.Input) Colors.PORT_IN else Colors.PORT_OUT
            val cx = (px + 0.5f) * tilePx
            val cy = (py + 0.5f) * tilePx
            val w = Visual.PORT_SIZE
            val h = Visual.PORT_SIZE
            rect(cx, cy, w * tilePx, h * tilePx, color)
        }
    }

    /** A hollow square of [color] around the tile edge — a border, not a fill. */
    private fun frame(x: Int, y: Int, color: Long) {
        val thickness = Visual.FRAME_THICKNESS
        val span = Visual.FRAME_SPAN
        rect((x + 0.5f) * tilePx, (y + 0.5f - (span - thickness) * 0.5f) * tilePx, span * tilePx, thickness * tilePx, color)
        rect((x + 0.5f) * tilePx, (y + 0.5f + (span - thickness) * 0.5f) * tilePx, span * tilePx, thickness * tilePx, color)
        rect((x + 0.5f - (span - thickness) * 0.5f) * tilePx, (y + 0.5f) * tilePx, thickness * tilePx, span * tilePx, color)
        rect((x + 0.5f + (span - thickness) * 0.5f) * tilePx, (y + 0.5f) * tilePx, thickness * tilePx, span * tilePx, color)
    }

    /** A thin bar on the output edge, showing which way a machine sends things. */
    private fun edgeMark(x: Int, y: Int, dir: Direction, color: Long) {
        val cx = (x + 0.5f + dir.dx * Visual.EDGE_MARK_OFFSET) * tilePx
        val cy = (y + 0.5f + dir.dy * Visual.EDGE_MARK_OFFSET) * tilePx
        val hw = if (dir.dx != 0) Visual.EDGE_MARK_HALF_W else Visual.EDGE_MARK_WIDE
        val hh = if (dir.dy != 0) Visual.EDGE_MARK_HALF_H else Visual.EDGE_MARK_WIDE
        rect(cx, cy, hw * tilePx * 2f, hh * tilePx * 2f, color)
    }

    /** How full a machine is, along the bottom of its body. The at-a-glance "is this backing up?". */
    private fun fillBar(x: Int, y: Int, span: Int, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return
        val full = span - Visual.FILL_BAR_SPAN_INSET
        val w = f * full
        val left = x + 0.5f - full * 0.5f
        rect(
            (left + w * 0.5f) * tilePx,
            (y + 0.5f + span * 0.5f - Visual.FILL_BAR_BOTTOM_OFFSET) * tilePx,
            w * tilePx, Visual.FILL_BAR_HEIGHT * tilePx,
            if (f > Visual.FILL_BAR_WARN) Colors.FILL_WARN else Colors.FILL_OK,
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
        val alpha = Colors.HEAT_ALPHA
        val f = ((kelvin - HeatField.AMBIENT_KELVIN).toFloat() / RAMP_SPAN).coerceIn(-1f, 1f)
        return if (f <= 0f) {
            val c = -f
            rgba(
                (ColorBase.COLD_R - Colors.COLD_R_OFFSET * c).toInt(),
                (ColorBase.COLD_G - Colors.COLD_G_OFFSET * c).toInt(),
                (ColorBase.COLD_B - Colors.COLD_B_OFFSET * c).toInt(),
                alpha,
            )
        } else {
            rgba(
                (ColorBase.HOT_R + Colors.HOT_R_OFFSET * f).toInt(),
                (ColorBase.HOT_G - Colors.HOT_G_OFFSET * f).toInt(),
                (ColorBase.HOT_B - Colors.HOT_B_OFFSET * f).toInt(),
                alpha,
            )
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
        if (pressure <= 0L) return Colors.OVERLAY_EMPTY
        val f = (pressure.toFloat() / AirField.AMBIENT_AIR.total).coerceIn(Visual.PRESSURE_MIN_F, Visual.PRESSURE_MAX_F)
        val base = speciesColor(state.air.mixtureAt(index).dominant)
        val scale = (f / Visual.PRESSURE_MAX_F).coerceIn(Visual.PRESSURE_MIN_SCALE, Visual.PRESSURE_MAX_SCALE)
        return rgba(
            (((base shr 24) and 0xFF) * scale).toInt(),
            (((base shr 16) and 0xFF) * scale).toInt(),
            (((base shr 8) and 0xFF) * scale).toInt(),
            Colors.HEAT_ALPHA.toLong(),
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

    /** Base colours for the heat ramp — the same starting point for both cold and hot interpolation. */
    private object ColorBase {
        const val COLD_R = 0x50
        const val COLD_G = 0xA0
        const val COLD_B = 0xC0
        const val HOT_R = 0x50
        const val HOT_G = 0xA0
        const val HOT_B = 0xC0
    }

    companion object {
        private const val MAX_RECTS = 20_000

        /** Reach of the largest footprint, used to widen the machine pass past the screen edge. */
        private const val MAX_REACH = 2

        private const val MIN_TILE_PX = 6f
        private const val MAX_TILE_PX = 64f

        /** Bar-full reference for machine buffers — a machine holding this much is visibly backed up. */
        private const val BUFFER_BAR_FULL = 4_000f

        /** Kelvin either side of ambient that saturates the heat ramp. */
        private const val RAMP_SPAN = 60f
    }

    /** Palette colours for machine kinds, tiles, UI states, and overlays. */
    object Colors {
        // ── Backgrounds ─────────────────────────────────────────────────
        val TILE_LIGHT  = 0x141A24FFL
        val TILE_DARK   = 0x111722FFL
        val HULL        = 0x4A5464FFL
        val HULL_EDGE   = 0x3A4A5AFFL

        // ── Stopped machine states ──────────────────────────────────────
        val STOPPED_BODY    = 0x1A1A20FFL
        val STOPPED_INDICATOR = 0x8A3030FFL

        // ── Overlay colours ─────────────────────────────────────────────
        val OVERLAY_VACUUM  = 0x05070CD0L
        val OVERLAY_EMPTY   = 0x120A10D8L

        // ── UI highlights ───────────────────────────────────────────────
        val HOVER   = 0xFFFFFF1AL
        val SELECT  = 0xE8ECF2FFL

        // ── Component colours ───────────────────────────────────────────
        val VENT_CORE     = 0x0A0A0CFFL
        val DEBRIS_TOP    = 0x00000060L
        val SHADOW        = 0x00000070L
        val RAIL_STUB     = 0x2A3040FFL
        val BRIDGE_GLOW   = 0xD8DEE9FFL

        // ── Port colours ────────────────────────────────────────────────
        val PORT_IN  = 0xE8ECF2FFL
        val PORT_OUT = 0x5ADB7EFFL

        // ── Heat ramp base ──────────────────────────────────────────────
        const val HEAT_ALPHA = 0xC8L

        // ── Temperature interpolation deltas ────────────────────────────
        const val COLD_R_OFFSET = 0x30
        const val COLD_G_OFFSET = 0x60
        const val COLD_B_OFFSET = 0x30
        const val HOT_R_OFFSET = 0xAF
        const val HOT_G_OFFSET = 0x50
        const val HOT_B_OFFSET = 0xB0

        // ── Fill bar colours ────────────────────────────────────────────
        val FILL_WARN = 0xE05A4AFFL
        val FILL_OK = 0x9AE07AFFL

        // ── Pressure scale coefficients ─────────────────────────────────
        const val PRESSURE_MIN_SCALE = 0.12f
        const val PRESSURE_MAX_SCALE = 1f

        // ── Species colours ─────────────────────────────────────────────
        val IRON       = 0xB07A5AFFL
        val ALUMINUM   = 0xB8BCC4FFL
        val COPPER     = 0xE08A3AFFL
        val TITANIUM   = 0xC8CCD4FFL
        val SILICA     = 0xD8D0A8FFL
        val CARBON     = 0x484848FFL
        val RARE_EARTH = 0x6ED09AFFL
        val URANIUM    = 0xA8E04AFFL
        val OXYGEN     = 0x7AB8FFFFL
        val NITROGEN   = 0x9A9AD0FFL
        val CARBON_DIOXIDE = 0x8A8A8AFFL
        val WATER      = 0x4A8AD0FFL
        val EMPTY      = 0x707070FFL
    }

    /** Scales, thresholds, and offsets that drive the renderer's visual layout. */
    object Visual {
        // ── Machine body dimensions ─────────────────────────────────────
        const val MACHINE_INSET = 0.94f
        const val STOP_INDICATOR_SCALE = 0.34f
        const val SENSOR_EYE_SCALE = 0.3f
        const val VENT_CORE_SCALE = 0.4f

        // ── Port dimensions ─────────────────────────────────────────────
        const val PORT_SIZE = 0.44f
        const val PORT_SIDE_OFFSET = 0.46f

        // ── Rail dimensions ─────────────────────────────────────────────
        const val RAIL_HUB_SIZE = 0.30f
        const val RAIL_ARM_SHORT = 0.30f
        const val RAIL_ARM_LONG = 0.55f
        const val RAIL_PORTION = 0.25f

        // ── Bridge dimensions ───────────────────────────────────────────
        const val BRIDGE_SPAN_X = 2.62f
        const val BRIDGE_SPAN_Y = 0.62f
        const val BRIDGE_INSET = 0.26f
        const val BRIDGE_PACKET_SIZE = 0.40f

        // ── Debris dimensions ───────────────────────────────────────────
        const val DEBRIS_TOP_WIDTH = 0.94f
        const val DEBRIS_TOP_HEIGHT = 0.06f

        // ── Frame (channel collar) dimensions ───────────────────────────
        const val FRAME_THICKNESS = 0.13f
        const val FRAME_SPAN = 0.94f

        // ── Fill bar dimensions ─────────────────────────────────────────
        const val FILL_BAR_HEIGHT = 0.1f
        const val FILL_BAR_SPAN_INSET = 0.2f
        const val FILL_BAR_BOTTOM_OFFSET = 0.16f
        const val FILL_BAR_WARN = 0.85f

        // ── Fill bar colours ────────────────────────────────────────────
        const val FILL_OK_ALPHA = 0xFFL
        const val FILL_WARN_ALPHA = 0xFFL

        // ── Tank dimensions ─────────────────────────────────────────────
        const val TANK_SPAN_INSET = 0.2f
        const val TANK_BOTTOM_MARGIN = 0.03f

        // ── Packet dimensions ───────────────────────────────────────────
        const val PACKET_FILL = 0.62f
        const val PACKET_MIN_FILL = 0.35f

        // ── Edge mark dimensions ────────────────────────────────────────
        const val EDGE_MARK_OFFSET = 0.4f
        const val EDGE_MARK_HALF_W = 0.09f
        const val EDGE_MARK_HALF_H = 0.09f
        const val EDGE_MARK_WIDE = 0.3f

        // ── Thresholds ──────────────────────────────────────────────────
        const val DEBRIS_MIN_HEIGHT = 0.05f
        const val DEBRIS_MAX_HEIGHT = 0.6f
        const val DEBRIS_BASE_HEIGHT = 0.15f
        const val PACKET_VISIBILITY_FRACTION = 0.05f
        const val PRESSURE_MIN_F = 0.08f
        const val PRESSURE_MAX_F = 1.6f
        const val PRESSURE_MIN_SCALE = 0.12f
        const val PRESSURE_MAX_SCALE = 1f
    }
}

/** Palette colour for a machine kind, shared by the renderer and the HUD's brush swatch. */
fun kindColor(kind: MachineKind): Long = when (kind) {
    MachineKind.Rail -> 0x39445AFFL
    MachineKind.Gauge -> 0x39445AFFL
    MachineKind.Bridge -> 0x1A2030FFL
    MachineKind.Miner -> 0x6B4A2AFFL
    MachineKind.Processor -> 0x2E5A6BFFL
    MachineKind.Smelter -> 0x8A3A2AFFL
    MachineKind.Storage -> 0x3A4A5AFFL
    MachineKind.Sensor -> 0x24303CFFL
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
