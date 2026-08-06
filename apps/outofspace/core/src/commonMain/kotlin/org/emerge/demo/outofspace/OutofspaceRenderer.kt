package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Debris
import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.size
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Extractor
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Pump
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Rock
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.massIn
import org.emerge.demo.outofspace.world.fluid.AMBIENT_PRESSURE
import org.emerge.demo.outofspace.world.fluid.MomentumField
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
 * The camera lives here and works in tile units: [OutofspaceRenderer.camX]/[OutofspaceRenderer.camY] is the tile at the centre of the
 * screen and [OutofspaceRenderer.tilePx] is the zoom. Screen y is down, matching the grid's +y and the direction
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

    // Flat batch (refilled each frame).
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
     * The camera is in tile coordinates, so it moves with the origin when the grid grows on a near
     * edge — otherwise building off the left of the ship jumps the view sideways by the amount it
     * grew. A far-side growth reports zero and the camera correctly stays put.
     *
     * Lazily created because the first state is not known until the first frame.
     */
    private var frame: FrameShift? = null

    private fun followFrame(state: VesselState) {
        val f = frame ?: FrameShift(state).also { frame = it }
        val move = f.advance(state)
        camX += move.dx
        camY += move.dy
    }

    /** Centre on built area (fallback: grid centre). */
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

    /** Focus on a tile at a stated zoom (scripted panning). */
    fun focusOn(tileX: Float, tileY: Float, pixelsPerTile: Float = tilePx) {
        camX = tileX
        camY = tileY
        tilePx = pixelsPerTile.coerceIn(MIN_TILE_PX, MAX_TILE_PX)
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

    /** Tick progress 0–1 (see [OutofspaceController.tickAlpha]). Defaults to 1 (tests). */
    private var alpha: Float = 1f

    fun draw(
        state: VesselState,
        hoveredIndex: Int = -1,
        overlay: Overlay = Overlay.None,
        tickAlpha: Float = 1f,
    ) {
        followFrame(state)
        alpha = tickAlpha.coerceIn(0f, 1f)
        GPU.setClearColor(0.05f, 0.06f, 0.08f, 1f) // dark blue-grey void
        GPU.clearColorBuffer()
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        count = 0

        val grid = state.grid
        // On-screen tiles only.
        val halfW = resW / (2f * tilePx)
        val halfH = resH / (2f * tilePx)
        val minX = max(0, floor(camX - halfW).toInt())
        val maxX = minOf(grid.width - 1, floor(camX + halfW).toInt() + 1)
        val minY = max(0, floor(camY - halfH).toInt())
        val maxY = minOf(grid.height - 1, floor(camY + halfH).toInt() + 1)
        // Machines drawn from centre; widen pass by MAX_REACH.
        val mMinX = max(0, minX - MAX_REACH)
        val mMaxX = minOf(grid.width - 1, maxX + MAX_REACH)
        val mMinY = max(0, minY - MAX_REACH)
        val mMaxY = minOf(grid.height - 1, maxY + MAX_REACH)

        // Floor (buildable area).
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val shade = if ((x + y) and 1 == 0) Colors.TILE_LIGHT else Colors.TILE_DARK
                tileRect(x, y, 1f, shade)
            }
        }

        // Debris under machines (on deck).
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

        // Pipes under track (thinner, different depth).
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                drawPipe(state, grid.index(x, y), x, y)
            }
        }

        // Track over buildings (on deck).
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                drawRail(state, grid.index(x, y), x, y)
            }
        }

        // Bridges last (above track).
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val index = grid.index(x, y)
                drawBridge(state, index, state.bridges[index] ?: continue, x, y)
            }
        }

        // Departures.
        drawDepartures(state)

        // Rocks over built (not part of vessel).
        for (rock in state.rocks) drawRock(rock)

        // Overlay over machines.
        if (overlay != Overlay.None) {
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val index = grid.index(x, y)
                    val tint = when (overlay) {
                        Overlay.Heat -> temperatureColor(state.kelvinAt(index))
                        Overlay.Air -> mixtureColor(state, index)
                        Overlay.Pressure -> divergingColor(state.air.pressureAt(index).toFloat() / AMBIENT_PRESSURE)
                        Overlay.Density -> divergingColor(state.air.densityAt(index).toFloat() / AirField.AMBIENT_AIR.total)
                        Overlay.Flow -> Colors.FLOW_BACKDROP
                        Overlay.None -> 0L
                    }
                    tileRect(x, y, 1f, tint)
                }
            }
        }

        // Flow vectors over backdrop.
        if (overlay == Overlay.Flow) {
            // Scaled to fastest tile on screen (not fixed — ordinary circulation needs variable scale).
            var peak = 0f
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val s = state.flow.speedAt(grid.index(x, y))
                    if (s > peak) peak = s
                }
            }
            if (peak > 0f) {
                for (y in minY..maxY) {
                    for (x in minX..maxX) {
                        drawFlow(state, grid.index(x, y), x, y, peak)
                    }
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
        // Dark top line (heap readability).
        rect((x + 0.5f) * tilePx, (y + 1f - h) * tilePx, Visual.DEBRIS_TOP_WIDTH * tilePx, Visual.DEBRIS_TOP_HEIGHT * tilePx, Colors.DEBRIS_TOP)
    }

    /**
     * One tile of pipe.
     *
     * The same spine as [drawRail] and deliberately so — a conduit is a conduit, and the player
     * should read the two the same way — but narrower and in copper, because it is a different
     * network and the whole point of the layer is that you can tell which run is which where they
     * cross. Nothing rides on it: a pipe carries a fluid field rather than packets.
     */
    /**
     * A rock, cell by cell, at whatever fraction of a tile it has drifted to.
     *
     * Cell by cell rather than as one box because the shape is the point — a rock is a blob and a
     * box is a crate — and because it is what will still be true in H2, when the cells are what a
     * collision is tested against. Drawing the bounding box now would be drawing something the sim
     * does not believe in.
     *
     * Each cell is inset slightly and gets a lighter face, so a rock reads as rubble rather than as
     * a solid slab of one colour at the zoom anyone plays at.
     */
    private fun drawRock(rock: Rock) {
        val ox = rock.positionX.toFloat() / Flight.PER_TILE
        val oy = rock.positionY.toFloat() / Flight.PER_TILE
        for (cy in 0 until rock.height) {
            for (cx in 0 until rock.width) {
                if (!rock.cells[cy * rock.width + cx]) continue
                val wx = (ox + cx + 0.5f) * tilePx
                val wy = (oy + cy + 0.5f) * tilePx
                rect(wx, wy, tilePx, tilePx, Colors.ROCK)
                // Cell-local checker (grain doesn't crawl as rock drifts).
                if ((cx + cy) and 1 == 0) {
                    rect(wx, wy, tilePx * Visual.ROCK_GRAIN, tilePx * Visual.ROCK_GRAIN, Colors.ROCK_GRAIN)
                }
            }
        }
    }

    private fun drawPipe(state: VesselState, tile: Int, x: Int, y: Int) {
        val segment = state.conduits.at(Conduit.Pipe, tile) ?: return
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        val color = kindColor(MachineKind.Pipe)
        for (dir in Direction.ALL) {
            if (!segment.linkedTo(dir)) continue
            rect(
                cx + dir.dx * Visual.PIPE_ARM_OFFSET * tilePx, cy + dir.dy * Visual.PIPE_ARM_OFFSET * tilePx,
                (if (dir.dx != 0) Visual.PIPE_ARM_LENGTH else Visual.PIPE_DIAMETER) * tilePx,
                (if (dir.dy != 0) Visual.PIPE_ARM_LENGTH else Visual.PIPE_DIAMETER) * tilePx,
                color,
            )
        }
        rect(cx, cy, Visual.PIPE_DIAMETER * tilePx, Visual.PIPE_DIAMETER * tilePx, color)
        // Valve: bright collar (wider than pipe, centred).
        if (segment.isValve) {
            rect(cx, cy, Visual.VALVE_COLLAR * tilePx, Visual.VALVE_COLLAR * tilePx, Colors.VALVE_CORE)
        }
    }

    /** Track tile + packet (thin spine, gauge collar). */
    private fun drawRail(state: VesselState, tile: Int, x: Int, y: Int) {
        val segment = state.rails[tile] ?: return
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        // Only joined arms (not touching — two lines side by side stay separate).
        for (dir in Direction.ALL) {
            if (!segment.linkedTo(dir)) continue
            rect(
                cx + dir.dx * Visual.RAIL_ARM_OFFSET * tilePx, cy + dir.dy * Visual.RAIL_ARM_OFFSET * tilePx,
                (if (dir.dx != 0) Visual.RAIL_ARM_LENGTH else Visual.RAIL_DIAMETER) * tilePx,
                (if (dir.dy != 0) Visual.RAIL_ARM_LENGTH else Visual.RAIL_DIAMETER) * tilePx,
                kindColor(MachineKind.Rail),
            )
        }
        // The hub, always drawn
        rect(cx, cy, Visual.RAIL_DIAMETER * tilePx, Visual.RAIL_DIAMETER * tilePx, kindColor(MachineKind.Rail))
        segment.channel?.let { channel ->
            frame(x, y, channel.color)
        }
        val packet = segment.held ?: return
        val motion = state.motion

        // Previous position (offset in tiles; packet slides from there).
        val came = motion.arrivedFrom(tile)
        val backX = if (came == null) 0f else -came.dx.toFloat()
        val backY = if (came == null) 0f else -came.dy.toFloat()

        // New packet: scales in (no blink).
        val scale = if (motion.appearedAt(tile)) alpha else 1f

        drawPacket(
            x + 0.5f + backX * (1f - alpha),
            y + 0.5f + backY * (1f - alpha),
            lerp(motion.previousMassAt(tile).toFloat(), packet.mass.toFloat(), alpha),
            scale,
            packet.contents.dominant,
        )
    }

    /** Material lump at fractional tile coords. [mass] = size (interpolated), [scale] = appear/disappear. */
    private fun drawPacket(tx: Float, ty: Float, mass: Float, scale: Float, dominant: Species?) {
        if (scale <= 0f) return
        val fill = (mass / Capacity.PACKET_GRAMS).coerceIn(Visual.PACKET_MIN_FILL, 1f)
        val side = Visual.PACKET_FILL * fill * scale
        rect(tx * tilePx, ty * tilePx, side * tilePx, side * tilePx, packetColor(dominant))
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /** Bridge: elevated track (off-color, not part of lower track). */
    private fun drawBridge(state: VesselState, index: Int, b: Bridge, x: Int, y: Int) {
        val horizontal = b.facing.dx != 0
        val long = if (horizontal) Visual.BRIDGE_SPAN_X else Visual.BRIDGE_SPAN_Y
        val across = if (horizontal) Visual.BRIDGE_SPAN_Y else Visual.BRIDGE_SPAN_X
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        drawPorts(state, index, b)
        rect(cx, cy, (long - Visual.BRIDGE_INSET) * tilePx, (across - Visual.BRIDGE_INSET) * tilePx, kindColor(MachineKind.Bridge))
        // One slot per tile (entry fixed, middle+exit slide along span).
        val slots = listOf(
            Triple(-1f, b.entry, Motion.SLOT_ENTRY),
            Triple(0f, b.middle, Motion.SLOT_MIDDLE),
            Triple(1f, b.exit, Motion.SLOT_EXIT),
        )
        for ((along, packet, slot) in slots) {
            if (packet == null) continue
            val from = if (state.motion.bridgeSlotIsNew(index, slot)) along - 1f else along
            val at = lerp(from, along, alpha)
            val size = Visual.BRIDGE_PACKET_SIZE * tilePx
            rect(
                cx + b.facing.dx * at * tilePx,
                cy + b.facing.dy * at * tilePx,
                size, size, packetColor(packet.contents.dominant),
            )
        }
    }

    /**
     * The packets that were somewhere at the start of the tick and are nowhere at the end of it.
     *
     * Drawn last and separately because they are not in the world any more — a machine has eaten
     * them. Without this a packet arriving at a smelter simply stops existing between two frames,
     * which reads as a dropped item rather than as one being taken in.
     */
    private fun drawDepartures(state: VesselState) {
        for (d in state.motion.departures) {
            drawPacket(
                state.grid.xOf(d.tile) + 0.5f,
                state.grid.yOf(d.tile) + 0.5f,
                d.packet.mass.toFloat(),
                1f - alpha,
                d.packet.contents.dominant,
            )
        }
    }

    // ── Machine drawing ───────────────────────────────────────────────────────

    private fun drawMachine(state: VesselState, index: Int, x: Int, y: Int, m: Machine) {
        val n = m.kind.size
        // No activation = stopped (red tile).
        if (m !is Sensor && m.wiring.activation(Action.Run, state.signals) <= 0) {
            bodyRect(x, y, n, Visual.MACHINE_INSET, Colors.STOPPED_BODY)
            bodyRect(x, y, n, Visual.STOP_INDICATOR_SCALE, Colors.STOPPED_INDICATOR)
            drawPorts(state, index, m)
            return
        }
        when (m) {
            // Bridges not on deck list (separate pass).
            is Bridge -> Unit
            is Extractor -> {
                // A tray, not a block. The recessed floor is what says "things go on top of this",
                // and the rock pass draws over it — see [drawRock].
                bodyRect(x, y, n, Visual.MACHINE_INSET, kindColor(MachineKind.Extractor))
                bodyRect(x, y, n, Visual.EXTRACTOR_FLOOR_INSET, Colors.EXTRACTOR_FLOOR)
                fillBar(x, y, n, m.buffer.mass.toFloat() / Extractor.BUFFER_CAP)
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
            // Tank: room-sized fill (legible at distance).
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
                // Sensor: faces target, wears broadcast colour.
                edgeMark(x, y, m.facing, m.channel.color)
                tileRect(x, y, Visual.SENSOR_EYE_SCALE, m.channel.color)
            }
            // Pump intake: arrow shows facing (room direction).
            is Pump -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(MachineKind.Pump))
                intakeArrow(x, y, m.facing)
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
     * The ramp is **absolute** — anchored at [Temperature.AMBIENT_KELVIN] rather than normalised to
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
        val f = ((kelvin - Temperature.AMBIENT_KELVIN).toFloat() / RAMP_SPAN).coerceIn(-1f, 1f)
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
    private fun mixtureColor(state: VesselState, index: Int): Long {
        val pressure = state.air.pressureAt(index)
        if (pressure <= 0L) return Colors.OVERLAY_EMPTY
        val f = (pressure.toFloat() / AirField.AMBIENT_AIR.total).coerceIn(Visual.PRESSURE_MIN_F, Visual.PRESSURE_MAX_F)
        val base = speciesColor(state.air.mixtureAt(index).dominant)
        val scale = (f / Visual.PRESSURE_MAX_F).coerceIn(Visual.PRESSURE_MIN_SCALE, Visual.PRESSURE_MAX_SCALE)
        return rgba(
            (((base shr 24) and 0xFF) * scale).toInt(),
            (((base shr 16) and 0xFF) * scale).toInt(),
            (((base shr 8) and 0xFF) * scale).toInt(),
            Colors.HEAT_ALPHA,
        )
    }

    /**
     * A scalar as a *deviation from ambient*: blue where there is less than there should be, orange
     * where there is more, and near-neutral where the vessel is behaving.
     *
     * Diverging rather than a single ramp, because both of the fields drawn with this have a
     * meaningful zero — one atmosphere — and the question asked of them is almost always "which way
     * is this wrong", not "how much is there". A one-ended ramp answers that only by making the
     * viewer remember which shade ambient was, which nobody does.
     *
     * [f] is the value over its ambient, so 1.0 is normal. The span either side is [PRESSURE_SPAN]
     * rather than the full range down to vacuum, for the reason [temperatureColor] gives at length:
     * an honest full-range ramp renders every difference worth looking at as one flat wash.
     */
    private fun divergingColor(f: Float): Long {
        if (f <= 0f) return Colors.OVERLAY_VACUUM
        val d = ((f - 1f) / PRESSURE_SPAN).coerceIn(-1f, 1f)
        return if (d <= 0f) {
            val c = -d
            rgba(
                (ColorBase.THIN_R + (Colors.THIN_R_TARGET - ColorBase.THIN_R) * c).toInt(),
                (ColorBase.THIN_G + (Colors.THIN_G_TARGET - ColorBase.THIN_G) * c).toInt(),
                (ColorBase.THIN_B + (Colors.THIN_B_TARGET - ColorBase.THIN_B) * c).toInt(),
                Colors.HEAT_ALPHA,
            )
        } else {
            rgba(
                (ColorBase.THIN_R + (Colors.DENSE_R_TARGET - ColorBase.THIN_R) * d).toInt(),
                (ColorBase.THIN_G + (Colors.DENSE_G_TARGET - ColorBase.THIN_G) * d).toInt(),
                (ColorBase.THIN_B + (Colors.DENSE_B_TARGET - ColorBase.THIN_B) * d).toInt(),
                Colors.HEAT_ALPHA,
            )
        }
    }

    /**
     * One tile's air velocity, as a tapering streak from the tile centre in the direction of flow.
     *
     * ### Why a streak of squares and not an arrow
     *
     * Because the only primitive here is an axis-aligned rectangle — there is no rotation in the
     * instanced rect shader — so a real arrowhead at an arbitrary angle is not available. Decomposing
     * the velocity into an x-bar and a y-bar was the other option and was rejected: it draws a
     * corner, and a corner reads as two flows meeting rather than one flow going diagonally.
     *
     * A run of squares stepping along the velocity, each smaller and fainter than the last, has no
     * such ambiguity. It points where it is going at any angle, it says which end is the head without
     * needing a head, and it degrades gracefully — slow air is a dot, fast air is a comet.
     *
     * The streak **straddles** the tile, head leading. It used to end at the tile centre, with the
     * whole tail behind — which put every mark on the wrong side of the thing it described and read
     * as air that had already left. Leading with the head costs a little positional precision (the
     * bright square is no longer exactly on the tile) and buys the thing the overlay is actually for:
     * at a glance the field reads as going somewhere, rather than as having been somewhere.
     *
     * [peak] is the fastest speed on screen, so the picture is always scaled to whatever is currently
     * happening; the *lengths* are relative and only the direction is absolute. That is the right
     * trade for a debugging view — the question is nearly always "where is it going", and an overlay
     * that goes blank whenever the vessel is calm answers it for exactly the cases that do not need
     * answering.
     */
    private fun drawFlow(state: VesselState, tile: Int, x: Int, y: Int, peak: Float) {
        val speed = state.flow.speedAt(tile)
        // Still air guard (0/0). Visibility threshold is FLOW_MIN_FRACTION.
        if (speed <= 0f) return
        val fraction = speed / peak
        if (fraction < Visual.FLOW_MIN_FRACTION) return

        // Unit direction (speed = magnitude of this pair).
        val scale = MomentumField.SPEED_LIMIT_RAW.toFloat() * speed
        val dx = state.flow.xAt(tile).toFloat() / scale
        val dy = state.flow.yAt(tile).toFloat() / scale

        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        val reach = fraction * Visual.FLOW_MAX_REACH

        // Streak: FLOW_SEGMENTS ahead→behind, bright end leading.
        val steps = Visual.FLOW_SEGMENTS
        for (i in -steps until steps) {
            val along = -reach * i / (steps - 1)
            // Fade: full at head, zero at tail (front indicator).
            val taper = 1f - (i + steps).toFloat() / (2 * steps)
            val size = Visual.FLOW_HEAD_SIZE * taper * tilePx
            rect(
                cx + dx * along * tilePx, cy + dy * along * tilePx,
                size, size,
                rgba(0xFF, 0xFF, 0xFF, (Colors.FLOW_ALPHA * taper).toLong()),
            )
        }
    }

    private fun rgba(r: Int, g: Int, b: Int, a: Long): Long =
        (r.coerceIn(0, 255).toLong() shl 24) or (g.coerceIn(0, 255).toLong() shl 16) or
            (b.coerceIn(0, 255).toLong() shl 8) or a

    // ── Primitives ────────────────────────────────────────────────────────────

    /** A bar on the face a pump draws through, so which room it empties is visible without clicking. */
    private fun intakeArrow(x: Int, y: Int, facing: Direction) {
        val cx = (x + 0.5f + facing.dx * Visual.INTAKE_OFFSET) * tilePx
        val cy = (y + 0.5f + facing.dy * Visual.INTAKE_OFFSET) * tilePx
        val along = Visual.INTAKE_WIDTH * tilePx
        val across = Visual.INTAKE_DEPTH * tilePx
        rect(cx, cy, if (facing.dx != 0) across else along, if (facing.dy != 0) across else along, Colors.VALVE_CORE)
    }

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

        /** Ambient, the midpoint of the diverging ramp: a dark neutral that reads as "nothing to see". */
        const val THIN_R = 0x2A
        const val THIN_G = 0x2E
        const val THIN_B = 0x38
    }

    companion object {
        /**
         * Raised from 20,000 when the flow overlay arrived: it draws up to four rects per visible
         * tile *on top of* the tint pass, which at a wide zoom is more than the deck and the
         * machines put together. Hitting the cap does not crash — [rect] drops the overflow — but it
         * drops it silently, and an observability tool that quietly stops drawing at the far side of
         * the screen is worse than none.
         */
        private const val MAX_RECTS = 48_000

        /** Reach of the largest footprint, used to widen the machine pass past the screen edge. */
        private const val MAX_REACH = 2

        private const val MIN_TILE_PX = 6f
        private const val MAX_TILE_PX = 64f

        /** Bar-full reference for machine buffers — a machine holding this much is visibly backed up. */
        private const val BUFFER_BAR_FULL = 4_000f

        /** Kelvin either side of ambient that saturates the heat ramp. */
        private const val RAMP_SPAN = 60f

        /**
         * Fraction of an atmosphere either side of ambient that saturates the pressure and density
         * ramps. Half an atmosphere: a room down to two thirds is a problem you want to see, and a
         * breach reaching full saturation on its way to vacuum is the point.
         */
        private const val PRESSURE_SPAN = 0.5f
    }

    /** Palette colours for machine kinds, tiles, UI states, and overlays. */
    object Colors {
        // ── Backgrounds ─────────────────────────────────────────────────
        const val TILE_LIGHT  = 0x141A24FFL
        const val TILE_DARK   = 0x111722FFL

        // ── Rock ────────────────────────────────────────────────────────
        // Warm, desaturated.
        const val ROCK        = 0x6B5F55FFL
        const val ROCK_GRAIN  = 0x87796BFFL

        /** The extractor's recessed floor: the plate's colour, most of the way to the deck's. */
        const val EXTRACTOR_FLOOR = 0x3A2C1EFFL

        // ── Stopped machine states ──────────────────────────────────────
        const val STOPPED_BODY    = 0x1A1A20FFL
        const val STOPPED_INDICATOR = 0x8A3030FFL

        // ── Overlay colours ─────────────────────────────────────────────
        const val OVERLAY_VACUUM  = 0x05070CD0L
        const val OVERLAY_EMPTY   = 0x120A10D8L

        /** Thin → blue, dense → orange. */
        const val THIN_R_TARGET  = 0x40
        const val THIN_G_TARGET  = 0x90
        const val THIN_B_TARGET  = 0xE0
        const val DENSE_R_TARGET = 0xF0
        const val DENSE_G_TARGET = 0x90
        const val DENSE_B_TARGET = 0x30

        // Faint air visibility.
        const val FLOW_BACKDROP = 0x0A0D14E0L
        const val FLOW_ALPHA = 224f

        // ── UI highlights ───────────────────────────────────────────────
        const val HOVER   = 0xFFFFFF1AL

        // ── Component colours ───────────────────────────────────────────
        const val VENT_CORE     = 0x0A0A0CFFL
        /** Bright, because a valve's core is the way through rather than a hole into space. */
        const val VALVE_CORE    = 0xD8A860FFL
        const val DEBRIS_TOP    = 0x00000060L

        // ── Port colours ────────────────────────────────────────────────
        const val PORT_IN  = 0xE8ECF2FFL
        const val PORT_OUT = 0x5ADB7EFFL

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
        const val FILL_WARN = 0xE05A4AFFL
        const val FILL_OK = 0x9AE07AFFL

        // ── Pressure scale coefficients ─────────────────────────────────
        const val PRESSURE_MIN_SCALE = 0.12f
        const val PRESSURE_MAX_SCALE = 1f

        // ── Species colours ─────────────────────────────────────────────
        const val IRON       = 0xB07A5AFFL
        const val ALUMINUM   = 0xB8BCC4FFL
        const val COPPER     = 0xE08A3AFFL
        const val TITANIUM   = 0xC8CCD4FFL
        const val SILICA     = 0xD8D0A8FFL
        const val CARBON     = 0x484848FFL
        const val RARE_EARTH = 0x6ED09AFFL
        const val URANIUM    = 0xA8E04AFFL
        const val OXYGEN     = 0x7AB8FFFFL
        const val NITROGEN   = 0x9A9AD0FFL
        const val CARBON_DIOXIDE = 0x8A8A8AFFL
        const val WATER      = 0x4A8AD0FFL
        const val EMPTY      = 0x707070FFL
    }

    /** Scales, thresholds, and offsets that drive the renderer's visual layout. */
    object Visual {
        /** How much of a rock cell the lighter grain square covers. */
        const val ROCK_GRAIN = 0.5f

        // ── Machine body dimensions ─────────────────────────────────────
        const val MACHINE_INSET = 0.94f
        /** The extractor's floor, inside its rim. */
        const val EXTRACTOR_FLOOR_INSET = 0.82f
        const val STOP_INDICATOR_SCALE = 0.34f
        const val SENSOR_EYE_SCALE = 0.3f
        const val VENT_CORE_SCALE = 0.4f

        // ── Port dimensions ─────────────────────────────────────────────
        const val PORT_SIZE = 0.75f

        // ── Rail dimensions ─────────────────────────────────────────────
        const val RAIL_DIAMETER = 0.50f

        /** Narrower than the rail, so a crossing reads as two runs rather than one junction. */
        const val PIPE_DIAMETER = 0.28f
        /** Wider than the pipe, so a tap reads against a long run without hiding its arms. */
        const val VALVE_COLLAR  = 0.46f
        const val INTAKE_OFFSET = 0.34f
        const val INTAKE_WIDTH  = 0.44f
        const val INTAKE_DEPTH  = 0.14f
        const val PIPE_ARM_LENGTH = (1f-PIPE_DIAMETER)/2f
        const val PIPE_ARM_OFFSET = (1f+PIPE_DIAMETER)/4f
        const val RAIL_ARM_LENGTH = (1f-RAIL_DIAMETER)/2f
        const val RAIL_ARM_OFFSET = (1f+RAIL_DIAMETER)/4f

        // ── Bridge dimensions ───────────────────────────────────────────
        const val BRIDGE_SPAN_X = 3.0f
        const val BRIDGE_SPAN_Y = 1.0f
        const val BRIDGE_INSET = 1f - RAIL_DIAMETER
        const val BRIDGE_PACKET_SIZE = 0.375f

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
        const val PRESSURE_MIN_F = 0.08f
        const val PRESSURE_MAX_F = 1.6f
        const val PRESSURE_MIN_SCALE = 0.12f
        const val PRESSURE_MAX_SCALE = 1f

        // ── Flow streaks ────────────────────────────────────────────────
        /**
         * Below this share of the fastest tile on screen, a streak is not drawn at all.
         *
         * Zero, which means every tile that is moving at all gets a mark. It was 4%, and that
         * quietly hid the thing the overlay is most often opened for: slow circulation next to
         * anything fast is *most* of what the air does, and scaling to the on-screen peak already
         * shrinks it to a dot. Still air is excluded by having no direction, not by being faint.
         */
        const val FLOW_MIN_FRACTION = 0.00f
        /** How far the tail of the fastest streak trails behind the tile centre, in tiles. */
        const val FLOW_MAX_REACH = 0.9f
        /** Squares behind the tile centre; the head leads by one more, so a streak is twice this. */
        const val FLOW_SEGMENTS = 4
        const val FLOW_HEAD_SIZE = 0.26f
    }
}

/** Palette colour for a machine kind, shared by the renderer and the HUD's brush swatch. */
fun kindColor(kind: MachineKind): Long = when (kind) {
    MachineKind.Rail -> 0x39445AFFL
    MachineKind.Gauge -> 0x39445AFFL
    MachineKind.Pipe -> 0x7A5A3AFFL
    MachineKind.Bridge -> 0x1A2030FFL
    MachineKind.Extractor -> 0x6B4A2AFFL
    MachineKind.Processor -> 0x2E5A6BFFL
    MachineKind.Smelter -> 0x8A3A2AFFL
    MachineKind.Storage -> 0x3A4A5AFFL
    MachineKind.Sensor -> 0x24303CFFL
    MachineKind.Hull -> 0x4A5464FFL
    MachineKind.Vent -> 0x3A3A44FFL
    MachineKind.Pump -> 0xB07840FFL
    MachineKind.Valve -> 0xD8A860FFL
}

/**
 * The colour a species is drawn in — shared by the renderer's packets and the HUD's readouts, so a
 * lump on a belt and its name in the inspector are unmistakably the same stuff.
 */
fun speciesColor(dominant: Species?): Long = when (dominant) {
    Species.Iron -> OutofspaceRenderer.Colors.IRON
    Species.Aluminum -> OutofspaceRenderer.Colors.ALUMINUM
    Species.Copper -> OutofspaceRenderer.Colors.COPPER
    Species.Titanium -> OutofspaceRenderer.Colors.TITANIUM
    Species.Silica -> OutofspaceRenderer.Colors.SILICA
    Species.Carbon -> OutofspaceRenderer.Colors.CARBON
    Species.RareEarth -> OutofspaceRenderer.Colors.RARE_EARTH
    Species.Uranium -> OutofspaceRenderer.Colors.URANIUM
    Species.Oxygen -> OutofspaceRenderer.Colors.OXYGEN
    Species.Nitrogen -> OutofspaceRenderer.Colors.NITROGEN
    Species.CarbonDioxide -> OutofspaceRenderer.Colors.CARBON_DIOXIDE
    Species.Water -> OutofspaceRenderer.Colors.WATER
    null -> OutofspaceRenderer.Colors.EMPTY
}
