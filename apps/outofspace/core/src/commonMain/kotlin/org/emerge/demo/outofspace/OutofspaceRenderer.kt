package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.LANTHANIDE_SUITE
import org.emerge.demo.outofspace.chem.MINERALS
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.Negligible
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.machine.Vaporizer
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.massIn
import org.emerge.demo.outofspace.world.AMBIENT_PRESSURE
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.diameter
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.size
import org.emerge.render.torus.GPU
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.shader.StarscapeShader
import org.emerge.render.torus.ui.UiRectRenderer
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

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
 *
 * The camera also has an *orientation* — [viewAngle], chosen by the [CameraFrame] handed to [draw].
 * That is the one thing the batch cannot express on the CPU, because a `UiRectRenderer` instance is
 * an axis-aligned quad and turning only its centre would swirl the scene while leaving every tile
 * square. So the turn is a uniform on the shared shader and the geometry here is unchanged; the only
 * thing this class does differently in a turned view is widen the cull window and un-turn the
 * pointer.
 */
class OutofspaceRenderer {

    private val rects = UiRectRenderer(maxRects = MAX_RECTS)
    private val starscape = StarscapeShader()

    private var resW = 1f
    private var resH = 1f

    /** Anchor, pan, and zoom — see [Camera], which is where that arithmetic is tested. */
    private val camera = Camera()

    val camX: Float get() = camera.camX
    val camY: Float get() = camera.camY
    val tilePx: Float get() = camera.tilePx

    /**
     * How far the scene is turned on screen, and therefore how far every pointer position has to be
     * turned back — see [screenToTile].
     *
     * Written by [draw] from the [CameraFrame] it is handed, so a host that never mentions a frame
     * gets the axis-aligned view it always had. Kept as state rather than passed to each of the
     * pointer calls because a pointer event does not arrive during a frame: it arrives between two,
     * and the honest answer to "what tile is under the cursor" is the one the player last saw.
     */
    var viewAngle: Coord = Coord(0)
        private set

    /** `(cos, sin)` of [viewAngle] in screen-pixel axes, y down. Recomputed only when it moves. */
    private var viewCos = 1f
    private var viewSin = 0f

    /** The matrix handed to [UiRectRenderer.drawInstanced], rebuilt per frame; see [setViewAngle]. */
    private val viewTransform = Mat4.identity()

    private fun setViewAngle(angle: Coord) {
        if (angle != viewAngle) {
            val cs = ViewTurn.cosSin(angle)
            viewCos = cs[0]
            viewSin = cs[1]
            viewAngle = angle
            camera.setViewCosSin(viewCos, viewSin)
        }
        // Rebuilt every frame even when the angle held still: the aspect is the other half of it,
        // and a window resized between two frames of the same heading still moves this matrix.
        ViewTurn.transform(viewCos, viewSin, resW / resH, viewTransform)
    }

    // Flat batch (refilled each frame).
    private val matrices = FloatArray(MAX_RECTS * Mat4.FLOATS)
    private val colors = FloatArray(MAX_RECTS * 4)
    private var count = 0

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = max(1f, widthPx)
        resH = max(1f, heightPx)
        GPU.setViewport(0, 0, resW.toInt(), resH.toInt())
    }

    /**
     * What the anchor follows, once per frame, and it is not the same thing in both modes.
     *
     * **Flying**, it is the ship's centre of mass: that is the point the hull turns about, so the
     * ship turns in place instead of swinging about some corner of itself.
     *
     * **Building**, nothing follows anything. A centre of mass moves whenever a machine is placed or
     * a crate slides along a belt, and a workbench that drifts a few pixels every time the player
     * builds on it is worse than one that sits still — the ship is not going anywhere, and the
     * player is aiming at tiles. So the anchor is simply left where it is, and the only thing that
     * touches it is [FrameShift], for the one case where the tile it names has genuinely moved: the
     * grid growing on a near edge, which shifts every coordinate written down outside the state.
     *
     * The shift is *advanced* every frame either way, growth in flight included, or a Build mode
     * returned to would apply a delta it had already been spared and jump.
     */
    private fun followAnchor(state: VesselState, frame: CameraFrame) {
        val move = (shift ?: FrameShift(state).also { shift = it }).advance(state)
        when (frame) {
            CameraFrame.World -> followVessel(state)
            CameraFrame.Grid -> camera.shiftAnchor(move.dx.toFloat(), move.dy.toFloat())
        }
    }

    /** Lazily created: the first state is not known until the first frame. */
    private var shift: FrameShift? = null

    /**
     * The ship's centre of mass, as the anchor.
     *
     * A vessel with no mass — an empty grid, or the last machine deleted — leaves the anchor alone
     * rather than snapping the view to the grid's corner.
     */
    private fun followVessel(state: VesselState) {
        val about = state.distribution
        if (about.mass <= 0L) return
        camera.followVessel(
            about.comX.toFloat() / Rotation.MILLI_TILE,
            about.comY.toFloat() / Rotation.MILLI_TILE,
        )
    }

    /** Centre on built area (fallback: grid centre). */
    fun centreOn(state: VesselState) {
        // The anchor first: [centreOn] is the one framing call that arrives before any frame has
        // been drawn, and a pan is measured from wherever the anchor is when it is taken. The ship
        // is where a fresh camera should start in either mode — Build mode holds that, rather than
        // starting from the grid's corner.
        followVessel(state)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (tile in state.grid.tiles) {
            val machine = state[tile]
            val deckMachine = state.deck[tile]
            if (machine == null && deckMachine == null) continue
            val x = state.grid.xOf(tile)
            val y = state.grid.yOf(tile)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX < minX) {
            camera.lookAt(state.grid.width / 2f, state.grid.height / 2f)
        } else {
            camera.lookAt((minX + maxX + 1) / 2f, (minY + maxY + 1) / 2f)
        }
    }

    /** Focus on a tile at a stated zoom (scripted panning). */
    fun focusOn(tileX: Float, tileY: Float, pixelsPerTile: Float = tilePx) {
        camera.lookAt(tileX, tileY)
        camera.setZoom(pixelsPerTile)
    }

    /** A drag is screen-space and stays that way; [camX] is where it lands in the grid. */
    fun panByPixels(dxPixels: Float, dyPixels: Float) = camera.panByPixels(dxPixels, dyPixels)

    fun zoomAtScreen(px: Float, py: Float, factor: Float) =
        camera.zoomAtScreen(px, py, factor, resW, resH)

    /**
     * Framebuffer pixel → fractional tile coordinates.
     *
     * The offset from the screen centre is turned *back* by [viewAngle] before it is scaled, which
     * is what keeps building honest in a rotated view: the tile the player clicks is the tile they
     * see under the cursor, and a pipe dragged along a tilted hull follows the hull.
     */
    fun screenToTile(px: Float, py: Float): FloatArray = camera.screenToTile(px, py, resW, resH)

    private fun unturnX(dx: Float, dy: Float): Float = ViewTurn.unturnX(viewCos, viewSin, dx, dy)

    private fun unturnY(dx: Float, dy: Float): Float = ViewTurn.unturnY(viewCos, viewSin, dx, dy)

    /** Framebuffer pixel → tile index, or -1 when the pointer is off the grid. */
    fun tileIndexAt(px: Float, py: Float, state: VesselState): TileIndex {
        val t = screenToTile(px, py)
        val x = floor(t[0]).toInt()
        val y = floor(t[1]).toInt()
        return if (state.grid.inBounds(x, y)) state.grid.tile(x, y) else TileIndex.NONE
    }

    /** Tick progress 0–[OutofspaceConfig.ticksPerSecond] (see [OutofspaceController.tickAlpha]). Defaults to 1 (tests). */
    private var alpha: Float = 1f
    private val railPacketAlpha get() = (alpha%RAIL_PERIOD)/RAIL_PERIOD

    fun draw(
        state: VesselState,
        hoveredTile: TileIndex = TileIndex.NONE,
        overlay: Overlay = Overlay.None,
        tickAlpha: Float = 1f,
        ticksPerSecond: Float = 1f,
        camera: CameraFrame = CameraFrame.Grid,
    ) {
        followAnchor(state, camera)
        setViewAngle(if (camera == CameraFrame.World) state.ang else Coord(0))
        alpha = tickAlpha.coerceIn(0f, ticksPerSecond)
        GPU.setClearColor(0.05f, 0.06f, 0.08f, 1f) // dark blue-grey void
        GPU.clearColorBuffer()
        val starscapeBearing = if (camera == CameraFrame.Grid) state.ang else Coord(0)
        starscape.draw(bearing = starscapeBearing.toFloat() * PI.toFloat(), resolutionX = resW, resolutionY = resH)
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        count = 0

        val grid = state.grid
        // On-screen tiles only. Turned, the screen's corners reach further out along both axes than
        // its edges do, so the window becomes the circle that contains it — never under-drawing, at
        // the cost of some off-screen tiles at 45°. Anything cleverer would be a polygon test per
        // tile to save work that is already one instanced call.
        val turned = viewAngle.raw != 0
        val radius = sqrt(resW * resW + resH * resH) / (2f * tilePx)
        val halfW = if (turned) radius else resW / (2f * tilePx)
        val halfH = if (turned) radius else resH / (2f * tilePx)
        val minX = max(0, floor(camX - halfW).toInt())
        val maxX = minOf(grid.width - 1, floor(camX + halfW).toInt() + 1)
        val minY = max(0, floor(camY - halfH).toInt())
        val maxY = minOf(grid.height - 1, floor(camY + halfH).toInt() + 1)
        // Machines drawn from centre; widen pass by MAX_REACH.
        val mMinX = max(0, minX - MAX_REACH)
        val mMaxX = minOf(grid.width - 1, maxX + MAX_REACH)
        val mMinY = max(0, minY - MAX_REACH)
        val mMaxY = minOf(grid.height - 1, maxY + MAX_REACH)

        if (overlay == Overlay.None) {
            // underlay air
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val index = grid.tile(x, y)
                    val tint = mixtureColor(state, index)
                    tileRect(x, y, 1f, tint)
                }
            }
        }

        // Floor (buildable area).
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val shade = if ((x + y) and 1 == 0) Colors.TILE_LIGHT else Colors.TILE_DARK
                tileRect(x, y, 1f, shade)
            }
        }

        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val tile = grid.tile(x, y)
                drawDeckMachine(state, state.deck[tile] ?: continue)
            }
        }

        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val tile = grid.tile(x, y)
                drawMachine(state, tile, x, y, state[tile] ?: continue)
            }
        }

        // Signal wire under everything: it is the thinnest run and the one most often threaded
        // beneath a machine to reach it, so anything it passes under should still read clearly.
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                drawWire(state, grid.tile(x, y), x, y)
            }
        }

        // Pipes under track (thinner, different depth).
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                drawPipe(state, grid.tile(x, y), x, y)
            }
        }

        // Track over buildings (on deck).
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                drawRail(state, grid.tile(x, y), x, y)
            }
        }
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                drawRailPacket(state, grid.tile(x, y), x, y)
            }
        }

        // Bridges last (above track).
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val tile = grid.tile(x, y)
                drawBridge(state, tile, state.bridges[tile.index] ?: continue, x, y)
            }
        }

        // Departures.
        drawDepartures(state)

        // Bodies over built (not part of vessel).
        for (body in state.bodies) drawBody(body, state.pose)

        // Overlay over machines.
        if (overlay != Overlay.None) {
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val tile = grid.tile(x, y)
                    val tint = when (overlay) {
                        Overlay.Heat -> temperatureColor(state.kelvinAt(tile))
                        Overlay.Air -> mixtureColor(state, tile)
                        // A trace reads as vacuum rather than as "very thin air": see [Negligible].
                        Overlay.Pressure -> state.air.pressureAt(tile).let {
                            if (Negligible.pressure(it)) Colors.OVERLAY_VACUUM
                            else divergingColor(it.toFloat() / AMBIENT_PRESSURE)
                        }
                        Overlay.Density -> state.air.densityAt(tile).let {
                            if (Negligible.gas(it)) Colors.OVERLAY_VACUUM
                            else divergingColor(it.toFloat() / Stuff.AMBIENT_AIR.total)
                        }
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
            // Negligible tiles are excluded from the peak as well as from the drawing: the speed is a
            // ratio, so a one-gram tile can top the scale and shrink every real flow to a dot.
            var peak = 0f
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val index = grid.tile(x, y)
                    if (negligibleFlow(state, index)) continue
                    val s = state.flow.speedAt(index)
                    if (s > peak) peak = s
                }
            }
            if (peak > 0f) {
                for (y in minY..maxY) {
                    for (x in minX..maxX) {
                        drawFlow(state, grid.tile(x, y), x, y, peak)
                    }
                }
            }
        }

        if (hoveredTile != TileIndex.NONE) {
            tileRect(grid.xOf(hoveredTile), grid.yOf(hoveredTile), 1f, Colors.HOVER)
        }

        rects.drawInstanced(count, matrices, colors)
        GPU.disableBlend()
    }

    fun cleanup() {
        starscape.deleteProgram()
        rects.deleteProgram()
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
    private fun drawBody(body: RigidBody, pose: Pose) {
        // The world is where a body lives; the grid is where this function draws. The pose is
        // composed once — it runs a CORDIC loop — and then every cell centre goes through it.
        val inGrid = body.poseIn(pose)
        val half = Flight.PER_TILE / 2L
        for (cy in 0 until body.height) {
            for (cx in 0 until body.width) {
                if (!body.cells[cy * body.width + cx]) continue
                // Turned centre, axis-aligned square — which is not a rendering shortcut but the
                // shape the *sim* collides with, spelled the same way. See [collectHullContacts].
                val lx = cx * Flight.PER_TILE + half
                val ly = cy * Flight.PER_TILE + half
                val wx = inGrid.toWorldX(lx, ly).toFloat() / Flight.PER_TILE * tilePx
                val wy = inGrid.toWorldY(lx, ly).toFloat() / Flight.PER_TILE * tilePx
                rect(wx, wy, tilePx, tilePx, Colors.ROCK, inGrid.ang)
                // Cell-local checker (grain doesn't crawl as body drifts).
                if ((cx + cy) and 1 == 0) {
                    rect(wx, wy, tilePx * Visual.ROCK_GRAIN, tilePx * Visual.ROCK_GRAIN, Colors.ROCK_GRAIN)
                }
            }
        }
    }

    /**
     * One tile of signal wire.
     *
     * The same spine as [drawPipe] and [drawRail] — a conduit is a conduit — but thinner than either,
     * because it carries a reading rather than a thing and should not compete with the runs that move
     * mass. Its colour is the value on it (Increment C); until something transmits, that is the dull
     * end of the ramp, which is the honest picture of a wire nobody is driving.
     */
    private fun drawWire(state: VesselState, tile: TileIndex, x: Int, y: Int) {
        val segment = state.conduits.at(Conduit.Signal, tile) ?: return
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        val color = lerpColor(Colors.WIRE_DARK, Colors.WIRE_LIVE, state.signals.at(tile) / SignalField.FULL.toFloat())
        for (dir in Direction.ALL) {
            if (!segment.linkedTo(dir)) continue
            rect(
                cx + dir.dx * Visual.WIRE_ARM_OFFSET * tilePx, cy + dir.dy * Visual.WIRE_ARM_OFFSET * tilePx,
                (if (dir.dx != 0) Visual.WIRE_ARM_LENGTH else Visual.WIRE_DIAMETER) * tilePx,
                (if (dir.dy != 0) Visual.WIRE_ARM_LENGTH else Visual.WIRE_DIAMETER) * tilePx,
                color,
            )
        }
        rect(cx, cy, Visual.WIRE_DIAMETER * tilePx, Visual.WIRE_DIAMETER * tilePx, color)
    }

    private fun drawPipe(state: VesselState, tile: TileIndex, x: Int, y: Int) {
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
    private fun drawRail(state: VesselState, tile: TileIndex, x: Int, y: Int) {
        val segment = state.railAt(tile) ?: return
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
        // A gauge wears a collar so it reads as an instrument in the line rather than as track. It
        // no longer wears a colour, because it no longer names one: what it reports on is the wire
        // beneath it, and that wire says its own value.
        if (segment.isGauge) frame(x, y, Colors.GAUGE_COLLAR)
    }

    private fun drawRailPacket(state: VesselState, tile: TileIndex, x: Int, y: Int) {
        val segment = state.railAt(tile) ?: return
        // A gauge wears a collar so it reads as an instrument in the line rather than as track. It
        // no longer wears a colour, because it no longer names one: what it reports on is the wire
        // beneath it, and that wire says its own value.
        if (segment.isGauge) frame(x, y, Colors.GAUGE_COLLAR)
        val packet = segment.held ?: return
        val motion = state.motion

        // Previous position (offset in tiles; packet slides from there).
        val came = motion.arrivedFrom(tile)
        val backX = if (came == null) 0f else -came.dx.toFloat()
        val backY = if (came == null) 0f else -came.dy.toFloat()

        // New packet: scales in (no blink).
        val scale = if (motion.appearedAt(tile)) railPacketAlpha else 1f

        drawPacket(
            x + 0.5f + backX * (1f - railPacketAlpha),
            y + 0.5f + backY * (1f - railPacketAlpha),
            lerp(motion.previousMassAt(tile).toFloat(), packet.mass.toFloat(), railPacketAlpha),
            scale,
            packet.contents,
        )
    }

    /** Material lump at fractional tile coords. [mass] = size (interpolated), [scale] = appear/disappear. */
    private fun drawPacket(tx: Float, ty: Float, mass: Float, scale: Float, mixture: Mixture) {
        if (scale <= 0f) return
        val fill = (mass / Capacity.PACKET_MASS).coerceIn(Visual.PACKET_MIN_FILL, 1f)
        val side = Visual.PACKET_FILL * fill * scale
        rect(tx * tilePx, ty * tilePx, side * tilePx, side * tilePx, mixture.color.toLong())
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /** Bridge: elevated track (off-color, not part of lower track). */
    private fun drawBridge(state: VesselState, tile: TileIndex, b: Bridge, x: Int, y: Int) {
        val horizontal = b.facing.dx != 0
        val long = if (horizontal) Visual.BRIDGE_SPAN_X else Visual.BRIDGE_SPAN_Y
        val across = if (horizontal) Visual.BRIDGE_SPAN_Y else Visual.BRIDGE_SPAN_X
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        drawPorts(state, tile, b)
        rect(cx, cy, (long - Visual.BRIDGE_INSET) * tilePx, (across - Visual.BRIDGE_INSET) * tilePx, kindColor(MachineKind.Bridge))
        // One slot per tile (entry fixed, middle+exit slide along span).
        val slots = listOf(
            Triple(-1f, b.entry, Motion.SLOT_ENTRY),
            Triple(0f, b.middle, Motion.SLOT_MIDDLE),
            Triple(1f, b.exit, Motion.SLOT_EXIT),
        )
        for ((along, packet, slot) in slots) {
            if (packet == null) continue
            val from = if (state.motion.bridgeSlotIsNew(tile, slot)) along - 1f else along
            val at = lerp(from, along, railPacketAlpha)
            val size = Visual.BRIDGE_PACKET_SIZE * tilePx
            rect(
                cx + b.facing.dx * at * tilePx,
                cy + b.facing.dy * at * tilePx,
                size, size, packet.contents.color.toLong(),
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
                1f - railPacketAlpha,
                d.packet.contents,
            )
        }
    }

    // ── Machine drawing ───────────────────────────────────────────────────────

    private fun drawMachine(state: VesselState, tile: TileIndex, x: Int, y: Int, m: Machine) {
        val n = m.kind.size
        // No activation = stopped (red tile). An airlock is exempt: unsignalled is not a fault for a
        // door, it is *shut*, and a wall of red panic lights along the hull would say the opposite.
        if (m !is Sensor && m !is WireButton && m !is Airlock && m.wiring.activation(Action.Run, state.signals.at(tile)) <= 0) {
            bodyRect(x, y, n, Visual.MACHINE_INSET, Colors.STOPPED_BODY)
            bodyRect(x, y, n, Visual.STOP_INDICATOR_SCALE, Colors.STOPPED_INDICATOR)
            drawPorts(state, tile, m)
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
            is ThermalDecomposer -> {
                bodyRect(x, y, n, Visual.MACHINE_INSET, kindColor(MachineKind.ThermalDecomposer))
                fillBar(x, y, n, massIn(m).toFloat() / BUFFER_BAR_FULL)
            }
            is Vaporizer -> {
                bodyRect(x, y, n, Visual.MACHINE_INSET, kindColor(MachineKind.Vaporizer))
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
                        m.contents?.mixture?.color?.toLong() ?: 0x000000FF,
                    )
                }
            }
            // An iris: hull-coloured door, with a hole in it that grows as the signal does. The
            // opening is drawn in the vent's colour on purpose — both are holes onto the same space,
            // and the player should read them as the same kind of thing.
            is Airlock -> {
                tileRect(x, y, 1f, kindColor(MachineKind.Airlock))
                val open = m.wiring.activation(Action.Run, state.signals.at(tile))
                    .coerceIn(0, SignalField.FULL) / SignalField.FULL.toFloat()
                if (open > 0f) tileRect(x, y, Visual.MACHINE_INSET * open, Colors.VENT_CORE)
            }
            // A button: its face lights up while it is held, and its key is written on it by the
            // wiring panel rather than by the tile — a letter at this size would be a smudge.
            is WireButton -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(MachineKind.KeyInput))
                val pressed = state.signals.at(tile) / SignalField.FULL.toFloat()
                tileRect(x, y, Visual.BUTTON_FACE, lerpColor(Colors.WIRE_DARK, Colors.WIRE_LIVE, pressed))
            }
            is Sensor -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(MachineKind.Sensor))
                // Faces its target, and its eye glows with whatever it is putting on the wire — the
                // same ramp the wire itself uses, so a lit sensor and a lit run read as one thing.
                val emitting = lerpColor(Colors.WIRE_DARK, Colors.WIRE_LIVE, state.signals.at(tile) / SignalField.FULL.toFloat())
                edgeMark(x, y, m.facing, emitting)
                tileRect(x, y, Visual.SENSOR_EYE_SCALE, emitting)
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
            // The bell marks the exhaust face, so which way a motor pushes is readable without
            // selecting it — the one thing about a thruster you cannot afford to get wrong.
            is Thruster -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(MachineKind.Thruster))
                edgeMark(x, y, m.facing, Colors.VENT_CORE)
            }
        }
        drawPorts(state, tile, m)
    }

    private fun drawDeckMachine(state: VesselState, m: DeckMachine) {
        when (m) {
            is Hull -> tileRect(state.grid.xOf(m.tile), state.grid.yOf(m.tile), 1f, kindColor(DeckMachineKind.Hull))
        }
        drawPorts(state, m)
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
    private fun drawPorts(state: VesselState, tile: TileIndex, m: Machine) {
        for (port in portsOf(state.grid, m, tile)) {
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
    private fun drawPorts(state: VesselState, m: DeckMachine) {
        for (port in portsOf(state.grid, m)) {
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
    /**
     * [a] to [b] at [f], channel by channel — the wire's value ramp.
     *
     * The whole readability argument for the signal layer rests on this: a wire you cannot see the
     * state of is worse than a global channel, because at least a channel had a readout. A run that
     * lights up as its sensor fills is the feature.
     */
    private fun lerpColor(a: Long, b: Long, f: Float): Long {
        val t = f.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val from = ((a shr shift) and 0xFF).toInt()
            val to = ((b shr shift) and 0xFF).toInt()
            return (from + (to - from) * t).toInt()
        }
        return rgba(ch(24), ch(16), ch(8), ch(0).toLong())
    }

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
    private fun mixtureColor(state: VesselState, tile: TileIndex): Long {
        val pressure = state.air.pressureAt(tile)
        if (Negligible.pressure(pressure)) return Colors.OVERLAY_EMPTY
        val f = (pressure.toFloat() / AMBIENT_PRESSURE).coerceIn(Visual.PRESSURE_MIN_F, Visual.PRESSURE_MAX_F)
        val base = state.air.mixtureAt(tile).color
        val scale = (f / Visual.PRESSURE_MAX_F).coerceIn(Visual.PRESSURE_MIN_SCALE, Visual.PRESSURE_MAX_SCALE)
        return rgba(
            (((base shr 24) and 0xFF) * scale).toInt(),
            (((base shr 16) and 0xFF) * scale).toInt(),
            (((base shr 8) and 0xFF) * scale).toInt(),
            Colors.HEAT_ALPHA,
        )
    }

    /** Whether this tile's flow is beneath notice — asked by both the scaling pass and the drawing. */
    private fun negligibleFlow(state: VesselState, tile: TileIndex): Boolean =
        Negligible.flow(state.flow.xAt(tile), state.flow.yAt(tile), state.air.densityAt(tile))

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
    private fun drawFlow(state: VesselState, tile: TileIndex, x: Int, y: Int, peak: Float) {
        val speed = state.flow.speedAt(tile)
        // Still air guard (0/0), and trace air, which can report any speed at all. Visibility
        // threshold among the flows that survive is FLOW_MIN_FRACTION.
        if (speed <= 0f || negligibleFlow(state, tile)) return
        val fraction = speed / peak
        if (fraction < Visual.FLOW_MIN_FRACTION) return

        // Unit direction. Normalised against the pair's own magnitude rather than against `speed`,
        // which is that magnitude over the tile's mass and so carries a scale of its own.
        val fx = state.flow.xAt(tile).toFloat()
        val fy = state.flow.yAt(tile).toFloat()
        val scale = sqrt(fx * fx + fy * fy)
        if (scale <= 0f) return
        val dx = fx / scale
        val dy = fy / scale

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
    private fun rect(wx: Float, wy: Float, w: Float, h: Float, color: Long, angle: Coord = Coord(0)) {
        if (count >= MAX_RECTS) return
        val px = wx - camX * tilePx + resW * 0.5f
        val py = wy - camY * tilePx + resH * 0.5f

        val m = viewTransform.times(Mat4.translation(
            px / resW * 2f - 1f,
            1f - py / resH * 2f,
        )).times(Mat4.scale(
            w / resW,
            h / resH,
        )).times(Mat4.rotationZ(angle.toFloat() * -PI.toFloat()))   // TODO why do we need to use -PI here
        m.copyInto(matrices, count * Mat4.FLOATS)

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
        const val TILE_LIGHT  = 0x141A2480L
        const val TILE_DARK   = 0x11172280L

        // ── Rock ────────────────────────────────────────────────────────
        // Warm, desaturated.
        const val ROCK        = 0x6B5F55FFL
        const val ROCK_GRAIN  = 0x87796BFFL

        /** The extractor's recessed floor: the plate's colour, most of the way to the deck's. */
        const val EXTRACTOR_FLOOR = 0x3A2C1EFFL

        // ── Stopped machine states ──────────────────────────────────────
        /** The gauge's collar, and the two ends of the wire's value ramp. */
        const val GAUGE_COLLAR = 0xE0A93AFFL
        const val WIRE_DARK    = 0x33513FFFL
        const val WIRE_LIVE    = 0x6EE08AFFL

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
        /** Osmium really is faintly blue — the only metal that is, and the reason it is recognisable. */
        const val OSMIUM     = 0x8898B8FFL
        /** The lilac of an argon discharge tube, which is the only way anyone has ever seen argon. */
        const val ARGON      = 0xB88AD8FFL
        const val EMPTY      = 0x707070FFL

        // ── Mineral-class colours ───────────────────────────────────────
        //
        // The fallback palette for the long tail of the species table — see `speciesClassColor`.
        // Chosen to be separable at the size a belt packet is actually drawn, which rules out
        // fine distinctions: these have to read as different at a dozen pixels across.
        /** Silicates: the pale, dusty rock everything else is buried in. */
        const val SILICATE   = 0xC4B89AFFL
        /** Sulfides: brassy, which is genuinely what pyrite and chalcopyrite look like. */
        const val SULFIDE    = 0xC8A040FFL
        /** Oxides: the red-browns of rust and hematite. */
        const val OXIDE      = 0x9A5240FFL
        /** Carbonates: chalky and near-white. */
        const val CARBONATE  = 0xD8D4CCFFL
        /** Halides and sulfates: the glassy, faintly translucent evaporite look. */
        const val SALT       = 0xA8C0C8FFL
        /** Refined metal that has no colour of its own — the generic ingot grey. */
        const val METAL      = 0xA0A4ACFFL
        /** Ices, which read as cold before they read as anything else. */
        const val ICE        = 0xA8D8E8FFL
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
        /** The lit face of a button — big, because it is the one thing you look at while flying. */
        const val BUTTON_FACE = 0.62f
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

        // ── Signal wire dimensions ──────────────────────────────────────
        /** Thinner than the pipe: it carries a reading, not a thing, and should not shout. */
        const val WIRE_DIAMETER = 0.16f
        const val WIRE_ARM_LENGTH = (1f-WIRE_DIAMETER)/2f
        const val WIRE_ARM_OFFSET = (1f+WIRE_DIAMETER)/4f

        // ── Bridge dimensions ───────────────────────────────────────────
        const val BRIDGE_SPAN_X = 3.0f
        const val BRIDGE_SPAN_Y = 1.0f
        const val BRIDGE_INSET = 1f - RAIL_DIAMETER
        const val BRIDGE_PACKET_SIZE = 0.375f

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
    MachineKind.ThermalDecomposer -> 0x5E5A3BFFL
    MachineKind.Vaporizer -> 0x905A6BFFL
    MachineKind.Smelter -> 0x8A3A2AFFL
    MachineKind.Storage -> 0x3A4A5AFFL
    MachineKind.Sensor -> 0x24303CFFL
    MachineKind.KeyInput -> 0x2E3A4AFFL
    // Lighter than hull, so a door reads as a door in a wall at a glance.
    MachineKind.Airlock -> 0x6E7C90FFL
    MachineKind.Vent -> 0x3A3A44FFL
    MachineKind.Pump -> 0xB07840FFL
    MachineKind.Thruster -> 0xC04A30FFL
    MachineKind.Valve -> 0xD8A860FFL
    MachineKind.Wire -> 0x4A7A5AFFL
}
fun kindColor(kind: DeckMachineKind): Long = when (kind) {
    DeckMachineKind.Hull -> 0x4A5464FFL
}

/**
 * The colour a species is drawn in — shared by the renderer's packets and the HUD's readouts, so a
 * lump on a belt and its name in the inspector are unmistakably the same stuff.
 *
 * ### Why this is no longer one branch per species
 *
 * It used to be an exhaustive `when`, which was the right shape for fourteen species and is the
 * wrong one for a hundred and fifty: nobody can hand-pick that many colours that stay distinguishable,
 * and an exhaustive `when` turns every addition to the table into a compile error in the renderer.
 *
 * So the ones the player handles constantly keep their authored colour, and everything else takes
 * its **class**'s colour. That is not a degradation — it is more readable than a hundred and fifty
 * near-identical greys would be. A player does not need to tell xenotime from monazite at a glance;
 * they need to tell *a rare-earth phosphate* from *a sulfide*, and the eye does that far better with
 * eight colours than with a hundred. The specific name is a tooltip away.
 */
fun speciesColor(dominant: Species?): Long = when (dominant) {
    null -> OutofspaceRenderer.Colors.EMPTY

    // ── The ones worth knowing on sight ──
    Species.Iron -> OutofspaceRenderer.Colors.IRON
    Species.Aluminum -> OutofspaceRenderer.Colors.ALUMINUM
    Species.Copper -> OutofspaceRenderer.Colors.COPPER
    Species.Titanium -> OutofspaceRenderer.Colors.TITANIUM
    Species.Quartz -> OutofspaceRenderer.Colors.SILICA
    Species.Carbon -> OutofspaceRenderer.Colors.CARBON
    Species.Uranium -> OutofspaceRenderer.Colors.URANIUM
    Species.Oxygen -> OutofspaceRenderer.Colors.OXYGEN
    Species.Nitrogen -> OutofspaceRenderer.Colors.NITROGEN
    Species.CarbonDioxide -> OutofspaceRenderer.Colors.CARBON_DIOXIDE
    Species.Water -> OutofspaceRenderer.Colors.WATER
    Species.Osmium -> OutofspaceRenderer.Colors.OSMIUM
    Species.Argon -> OutofspaceRenderer.Colors.ARGON

    else -> speciesClassColor(dominant)
}

/**
 * The fallback: what family this species belongs to, rendered as a colour.
 *
 * Membership is read off [MINERALS] wherever it can be, rather than restated as a list here. A
 * sulfide is a thing whose formula contains sulfur — that is the definition, and deriving it means
 * a mineral added to the table is coloured correctly without anyone remembering to come here.
 */
private fun speciesClassColor(s: Species): Long {
    val formula = MINERALS[s]
    return when {
        // Ices first: several are also "carbonates" by formula and this is the meaning that matters.
        s in ICES -> OutofspaceRenderer.Colors.ICE
        formula == null -> if (s in LANTHANIDES) OutofspaceRenderer.Colors.RARE_EARTH
            else OutofspaceRenderer.Colors.METAL
        LANTHANIDE_SUITE.containsKey(s) -> OutofspaceRenderer.Colors.RARE_EARTH
        Species.Sulfur in formula -> OutofspaceRenderer.Colors.SULFIDE
        Species.Silicon in formula -> OutofspaceRenderer.Colors.SILICATE
        Species.Carbon in formula -> OutofspaceRenderer.Colors.CARBONATE
        Species.Chlorine in formula || Species.Fluorine in formula -> OutofspaceRenderer.Colors.SALT
        Species.Oxygen in formula -> OutofspaceRenderer.Colors.OXIDE
        else -> OutofspaceRenderer.Colors.EMPTY
    }
}

/** The volatiles, whose *phase* is what the player cares about rather than their chemistry. */
private val ICES: Set<Species> = setOf(
    Species.Water, Species.CarbonDioxide, Species.CarbonMonoxide, Species.Ammonia,
    Species.Methane, Species.HydrogenSulfide, Species.SulfurDioxide,
    Species.Nitrogen, Species.Hydrogen, Species.Oxygen,
    Species.Helium, Species.Neon, Species.Argon, Species.Krypton, Species.Xenon,
)

private val LANTHANIDES: Set<Species> = setOf(
    Species.Lanthanum, Species.Cerium, Species.Praseodymium, Species.Neodymium,
    Species.Samarium, Species.Europium, Species.Gadolinium, Species.Terbium,
    Species.Dysprosium, Species.Holmium, Species.Erbium, Species.Thulium,
    Species.Ytterbium, Species.Lutetium, Species.Yttrium, Species.Scandium,
)
