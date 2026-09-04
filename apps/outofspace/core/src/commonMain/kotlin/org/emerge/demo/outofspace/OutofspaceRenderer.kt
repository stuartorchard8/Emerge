package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.LANTHANIDE_SUITE
import org.emerge.demo.outofspace.chem.MINERALS
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.Electrolyzer
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.footprint
import org.emerge.demo.outofspace.world.machine.newDeckMachine
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.Cadence
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.Negligible
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.FlowField
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.massIn
import org.emerge.demo.outofspace.world.AMBIENT_PRESSURE
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.airlockOpenness
import org.emerge.demo.outofspace.world.diameter
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.DockingPort
import org.emerge.demo.outofspace.world.machine.Furnace
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

    /**
     * Tiles that drew themselves as condemned this frame, so the X can go over the top of them.
     *
     * A field rather than a fresh list per frame: this runs every frame at whatever rate the display
     * manages, and the draw thread allocating a collection sixty times a second for a set that is
     * almost always empty is exactly the kind of litter the renderer is careful about elsewhere.
     */
    private val markedForDeconstruction = mutableSetOf<TileIndex>()

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
            about.comMilliX.toFloat() / Rotation.MILLI_TILE,
            about.comMilliY.toFloat() / Rotation.MILLI_TILE,
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

    /**
     * How far this frame is between the rail pass that last ran and the one that runs next: 0 as a
     * packet leaves a tile, 1 as it lands on the next. Set once per [draw] from the [Cadence] the
     * rail pass stamped on [org.emerge.demo.outofspace.world.Motion].
     *
     * ⛔ **Nothing here works out when a pass ran.** This used to be `(alpha % RAIL_PERIOD) /
     * RAIL_PERIOD` off a wrapped global clock, which is only right when the pass fires on tick zero
     * of a period that divides the tick rate — and the day the subsystems were given staggered
     * offsets it became a fifth of a tile of snap on arrival and a whole tile of teleport two
     * thirds of the way through the slide. The sim says when it ran. No period or offset constant
     * belongs in this file.
     */
    private var railPacketAlpha: Float = 1f

    /** The frame's place on the sim clock — see [OutofspaceController.simTime]. */
    private var simTime: Double = SETTLED

    fun draw(
        state: VesselState,
        inspectTile: TileIndex,
        inspectLayer: InspectLayer,
        hoveredTile: TileIndex,
        overlay: Overlay = Overlay.None,
        simTime: Double = SETTLED,
        camera: CameraFrame = CameraFrame.Grid,
        /**
         * What the build tool would put on [hoveredTile], or null when it would put nothing there —
         * see [OutofspaceController.planAt]. Defaulted, so a host with no pointer to speak of (a
         * phone) says nothing rather than saying no.
         */
        plan: BuildPlan? = null,
    ) {
        followAnchor(state, camera)
        setViewAngle(if (camera == CameraFrame.World) state.ang else Coord(0))
        this.simTime = simTime
        railPacketAlpha = state.motion.cadence.progress(simTime)
        if (overlay != fadedOverlay) {
            overlayFade.forget()
            flowFade.forget()
        }
        fadedOverlay = overlay
        if (overlay != Overlay.None) {
            val cadence = cadenceOf(overlay, state)
            overlayFade.sample(state.grid, cadence, simTime) { overlayColor(overlay, state, it) }
            if (overlay == Overlay.Flow) flowFade.sample(state.grid, cadence, simTime, state.flow)
        }
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
                    if (tint == Colors.TRANSPARENT) continue
                    tileRect(x, y, 1f, tint)
                }
            }
        }

        // Floor (buildable area).
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (state.structure[grid.tile(x,y).index] != Structure.Vacuum) {
                    val shade = if ((x + y) and 1 == 0) Colors.TILE_LIGHT else Colors.TILE_DARK
                    tileRect(x, y, 1f, shade)
                }
            }
        }

        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val tile = grid.tile(x, y)
                drawDeckMachine(state, state.deck[tile] ?: continue)
            }
        }
        if (inspectLayer == InspectLayer.Deck && inspectTile != TileIndex.NONE) {
            val origin = state.occupancy[inspectTile]
            val hovered = state[origin]
            // ⚠️ Over the machine's **footprint**, not a square of `diameter` off its origin. A
            // bridge's is a line and a thruster's is not even centred on the tile it is stored at,
            // so the square form lit two tiles of open deck beside a motor and left its bell dark.
            if (hovered == null) tileRect(grid.xOf(inspectTile), grid.yOf(inspectTile), 1f, Colors.HOVER)
            else if (hovered.kind != DeckMachineKind.Bridge) footprintRect(state, hovered, 1f, Colors.HOVER)
        }

        // Signal wire under everything: it is the thinnest run and the one most often threaded
        // beneath a machine to reach it, so anything it passes under should still read clearly.
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val tile = grid.tile(x, y)
                drawWire(state, tile, x, y, highlight = inspectLayer==InspectLayer.Rail && tile==inspectTile)
            }
        }

        // Track over buildings (on deck).
        for (y in mMinY..mMaxY) {
            for (x in mMinX..mMaxX) {
                val tile = grid.tile(x, y)
                drawRail(state, tile, x, y, highlight = inspectLayer==InspectLayer.Rail && tile==inspectTile)
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
                val b = state.deck[tile] as? Bridge ?: continue
                // Once per bridge, at its middle — it is stored at its centre and drawn across all
                // three of its tiles, so visiting it per covered tile would draw it three times.
                if (b.center != tile) continue
                drawBridge(state, tile, b, x, y, highlight = inspectLayer== InspectLayer.Deck && tile==inspectTile)
            }
        }

        // ⚠️ **The condemned mark goes on last**, over every machine and every bridge. The frame
        // each of them draws for itself is laid down *before* its own body and is promptly covered by
        // it, which is why a marked building has been so easy to miss. Gathered while they draw and
        // flushed here so the mark is on top of the thing it condemns.
        for (tile in markedForDeconstruction) {
            cross(state.grid.xOf(tile), state.grid.yOf(tile), Colors.SCRAPPING)
        }
        markedForDeconstruction.clear()

        // Departures.
        drawDepartures(state)

        // Bodies over built (not part of vessel).
        for (body in state.bodies) drawBody(body, state.pose)

        // Overlay over machines.
        if (overlay != Overlay.None) {
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val tile = grid.tile(x, y)
                    val pressure = state.air.pressureAt(tile)
                    if (state[state.occupancy[tile]] == null && Negligible.pressure(pressure)) continue
                    tileRect(x, y, 1f, overlayFade.color(tile))
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
                    // The faded speed, not the tick's: a peak taken from the field as it is would
                    // jump on the pass and rescale every streak on screen at once, which is the
                    // step this is here to remove — reintroduced through the denominator.
                    val s = flowFade.speedAt(index)
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

        if (inspectLayer == InspectLayer.Atmosphere && inspectTile != TileIndex.NONE) {
            tileRect(grid.xOf(inspectTile), grid.yOf(inspectTile), 1f, Colors.HOVER)
        }

        // **Last, over everything.** The plan is the only thing on screen that is not in the world,
        // and half of what it has to say is what it is standing on top of — the machine in the way,
        // the rim it hangs off. Drawn under the deck pass it would be hidden by exactly the obstacle
        // it is reporting.
        if (plan != null) drawPlan(state, plan)

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
    private fun drawWire(state: VesselState, tile: TileIndex, x: Int, y: Int, highlight: Boolean) {
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
        if (highlight) {
            for (dir in Direction.ALL) {
                if (!segment.linkedTo(dir)) continue
                rect(
                    cx + dir.dx * Visual.WIRE_ARM_OFFSET * tilePx, cy + dir.dy * Visual.WIRE_ARM_OFFSET * tilePx,
                    (if (dir.dx != 0) Visual.WIRE_ARM_LENGTH else Visual.WIRE_DIAMETER) * tilePx,
                    (if (dir.dy != 0) Visual.WIRE_ARM_LENGTH else Visual.WIRE_DIAMETER) * tilePx,
                    Colors.HOVER,
                )
            }
            rect(cx, cy, Visual.WIRE_DIAMETER * tilePx, Visual.WIRE_DIAMETER * tilePx, Colors.HOVER)
        }
    }


    /**
     * What a length of conduit is drawn in, given how much of it is actually there.
     *
     * Three states, one ramp. Finished track is [kindColor] and always has been. A **ghost** fades up
     * from [Colors.GHOST] as it fills, so a run the player has just drawn reads at a glance as a plan
     * rather than as track, and a half-fed one reads as half-fed. A segment being taken apart goes the
     * other way, toward [Colors.SCRAPPING], so the two directions never look alike — the difference
     * between "not yet" and "on its way out" is the difference between waiting and having made a
     * mistake, and a player has to be able to see which they are looking at.
     *
     * ⚠️ The fraction is [Conduits.builtPermille] — the segment's matter over the bill for the
     * material that segment chose. Asked of the layer instead it would be weighed against the
     * conduit's default, and a finished run of anything else would be drawn for ever as very nearly
     * built. It reaches full exactly when the tile does, so the picture cannot say finished while
     * the sim says ghost.
     */
    private fun conduitColor(state: VesselState, conduit: Conduit, tile: TileIndex): Long {
        val built = state.conduits.builtPermille(conduit, tile) / 1000f
        val whole = kindColor(conduit)
        if (state.conduits.at(conduit, tile)?.deconstructing == true) {
            if (built > 0.99f) {
                // Deconstruction hasn't begun, so represent the conduit's condemnation with the X
                markedForDeconstruction.add(tile)
            }
            return lerpColor(Colors.SCRAPPING, whole, built)
        }
        return if (built >= 1f) whole else lerpColor(Colors.GHOST, whole, built)
    }

    /**
     * What a half-built machine is drawn in: [Colors.GHOST] fading toward the colour of the thing it
     * is going to be.
     *
     * ⚠️ The fraction is [org.emerge.demo.outofspace.world.machine.DeckArray.builtPermille], the same
     * *minimum per-species* ratio the sim decides ghost-ness by, so the picture reaches full colour
     * on exactly the tick the machine starts working. A total-mass ramp would show a finished
     * smelter that was still refusing to run.
     */
    /**
     * How much of its own colour a machine's outline carries before a gram has arrived.
     *
     * Not zero, because a plan the player cannot identify is not a plan: an empty ghost still has to
     * say whether it is going to be a smelter or a tank. Low enough that it still reads as unbuilt.
     */
    private val GHOST_FLOOR = 0.3f

    private fun ghostColor(state: VesselState, m: DeckMachine): Long {
        val built = state.deck.builtPermille(m) / 1000f
        // From a *dim version of its own colour* rather than from nothing, so an empty ghost still
        // says which machine it is going to be. A plan the player cannot identify is not a plan.
        return lerpColor(Colors.GHOST, kindColor(m.kind), GHOST_FLOOR + (1f - GHOST_FLOOR) * built)
    }

    /** Track tile + packet (thin spine, gauge collar). */
    private fun drawRail(state: VesselState, tile: TileIndex, x: Int, y: Int, highlight: Boolean) {
        val segment = state.railAt(tile) ?: return
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        val railColor = conduitColor(state, Conduit.Rail, tile)
        // Only joined arms (not touching — two lines side by side stay separate).
        for (dir in Direction.ALL) {
            if (!segment.linkedTo(dir)) continue
            rect(
                cx + dir.dx * Visual.RAIL_ARM_OFFSET * tilePx, cy + dir.dy * Visual.RAIL_ARM_OFFSET * tilePx,
                (if (dir.dx != 0) Visual.RAIL_ARM_LENGTH else Visual.RAIL_DIAMETER) * tilePx,
                (if (dir.dy != 0) Visual.RAIL_ARM_LENGTH else Visual.RAIL_DIAMETER) * tilePx,
                railColor,
            )
        }
        // The hub, always drawn
        rect(cx, cy, Visual.RAIL_DIAMETER * tilePx, Visual.RAIL_DIAMETER * tilePx, railColor)
        if (highlight) {
            for (dir in Direction.ALL) {
                if (!segment.linkedTo(dir)) continue
                rect(
                    cx + dir.dx * Visual.RAIL_ARM_OFFSET * tilePx, cy + dir.dy * Visual.RAIL_ARM_OFFSET * tilePx,
                    (if (dir.dx != 0) Visual.RAIL_ARM_LENGTH else Visual.RAIL_DIAMETER) * tilePx,
                    (if (dir.dy != 0) Visual.RAIL_ARM_LENGTH else Visual.RAIL_DIAMETER) * tilePx,
                    Colors.HOVER,
                )
            }
            rect(cx, cy, Visual.RAIL_DIAMETER * tilePx, Visual.RAIL_DIAMETER * tilePx, Colors.HOVER)
        }
    }

    private fun drawRailPacket(state: VesselState, tile: TileIndex, x: Int, y: Int) {
        val segment = state.railAt(tile) ?: return
        val packet = state.rail.packetAt(tile) ?: return
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
    private fun drawBridge(state: VesselState, tile: TileIndex, b: Bridge, x: Int, y: Int, highlight: Boolean) {
        val horizontal = b.facing.dx != 0
        val long = if (horizontal) Visual.BRIDGE_SPAN_X else Visual.BRIDGE_SPAN_Y
        val across = if (horizontal) Visual.BRIDGE_SPAN_Y else Visual.BRIDGE_SPAN_X
        val cx = (x + 0.5f) * tilePx
        val cy = (y + 0.5f) * tilePx
        // The span fades up from slate as it builds and is framed when it is on its way out, exactly
        // as every other machine is — it just does it here, because a bridge is drawn over the track
        // it crosses rather than on a tile. ⚠️ A marked bridge goes on carrying, so its slots are
        // still drawn below: watching a condemned gantry walk its last load off is the point.
        rect(cx, cy, (long - Visual.BRIDGE_INSET) * tilePx, (across - Visual.BRIDGE_INSET) * tilePx, ghostColor(state, b))
        if (highlight) rect(cx, cy, (long - Visual.BRIDGE_INSET) * tilePx, (across - Visual.BRIDGE_INSET) * tilePx, Colors.HOVER)
        if (tile in state.scrapping) {
            frame(x, y, Colors.SCRAPPING)
            markedForDeconstruction.add(tile)
        }
        // One slot per tile (entry fixed, middle+exit slide along span). Read off the buffer layer,
        // where a bridge's load lives now — the three role tiles are these three positions.
        val slots = listOf(
            Triple(-1f, BufferRole.Input, Motion.SLOT_ENTRY),
            Triple(0f, BufferRole.Inside, Motion.SLOT_MIDDLE),
            Triple(1f, BufferRole.Product, Motion.SLOT_EXIT),
        )
        for ((along, role, slot) in slots) {
            val store = bufferTile(state.grid, b, tile, role) ?: continue
            val packet = state.buffers.resourceAt(store) ?: continue
            val from = if (state.motion.bridgeSlotIsNew(tile, slot)) along - 1f else along
            val at = lerp(from, along, railPacketAlpha)
            val size = Visual.BRIDGE_PACKET_SIZE * tilePx
            rect(
                cx + b.facing.dx * at * tilePx,
                cy + b.facing.dy * at * tilePx,
                size, size, packet.color.toLong(),
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

    private fun drawDeckMachine(state: VesselState, m: DeckMachine) {
        val tile = m.center
        val x = state.grid.xOf(tile)
        val y = state.grid.yOf(tile)
        // How wide the machine is, for the readouts drawn *inside* a square body — a fill bar and a
        // tank level. ⚠️ Never for the body itself: see [footprintRect] for why a size and a centre
        // is not enough to say where a machine is any more.
        val n = m.kind.diameter
        // A **ghost** is drawn as one body fading up from [Colors.GHOST] as it fills, the same ramp a
        // drawn run of track uses and for the same reason: a machine the player has just placed
        // should read at a glance as a plan rather than as a machine, and a half-fed one as half-fed.
        //
        // Its innards are deliberately not drawn. A fill bar on a ghost would be reporting on a store
        // that exists but that nothing can reach — its real ports are not there yet — and an empty
        // gauge on a thing that has never run is a lie about why it is not running.
        //
        // ⚠️ A **bridge** is exempt, as it is everywhere else: it is drawn over the track it crosses
        // by [drawBridge] rather than on the tile, so a body rect here would put a box in the middle
        // of the span. It fades in its own pass.
        if (m !is Bridge && state.deck.isGhost(tile)) {
            footprintRect(state, m, Visual.MACHINE_INSET, Colors.GHOST)
            // Outlined in the colour of the thing it is going to be, brightening as it fills. The
            // fill alone is [Colors.GHOST] against a deck that is nearly the same slate, which is
            // right for a rail arm — a thin bright line over dark — and all but invisible as a body
            // the size of a machine. The outline is what says *which* machine, and the ramp is what
            // says how far along it is.
            footprintOutline(state, m, ghostColor(state, m))
            drawPorts(state, m)
            return
        }
        // No activation = stopped (red tile). An airlock is exempt: unsignalled is not a fault for a
        // door, it is *shut*, and a wall of red panic lights along the hull would say the opposite.
        // A transmitter is never "stopped": a sensor or a button with no activation is doing its
        // job, and so is a shut airlock — see the note on [Airlock.SEALED].
        if (m !is Airlock && m !is Sensor && m !is WireButton &&
            !m.wiring.isOn(Action.Run, state.signals.at(tile))
        ) {
            footprintRect(state, m, Visual.MACHINE_INSET, Colors.STOPPED_BODY)
            footprintRect(state, m, Visual.STOP_INDICATOR_SCALE, Colors.STOPPED_INDICATOR)
            drawPorts(state, m)
            return
        }
        // Marked for deconstruction: drawn as itself, and then framed in [Colors.SCRAPPING]. As
        // itself, because its stores are still real and the player wants to watch them drain — that
        // is the whole of the ordering the feature promises. Framed, because "on its way out" and
        // "not yet" are opposite mistakes and must never look alike.
        if (tile in state.scrapping) {
            frame(x, y, Colors.SCRAPPING)
            markedForDeconstruction.add(tile)
        }
        when (m) {
            // Drawn by [drawBridge] in its own pass, above the track it crosses — a bridge is the
            // one deck machine that is *over* the tile rather than on it.
            is Bridge -> {}
            // A collar rather than a body, so it reads as an instrument in the line rather than as
            // a box sitting on it — it is a fitting the track runs through. It wears no colour,
            // because it names none: what it reports on is the wire beneath it.
            is Gauge -> frame(x, y, Colors.GAUGE_COLLAR)
            // Bright core, wider than the pipe it opens, centred on the tile.
            is Valve -> footprintRect(state, m, Visual.VALVE_COLLAR, Colors.VALVE_CORE)
            is Hull -> tileRect(x, y, 1f, kindColor(DeckMachineKind.Hull))
            is Extractor -> {
                // A tray, not a block. The recessed floor is what says "things go on top of this",
                // and the rock pass draws over it — see [drawRock].
                footprintRect(state, m, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Extractor))
                footprintRect(state, m, Visual.EXTRACTOR_FLOOR_INSET, Colors.EXTRACTOR_FLOOR)
                fillBar(x, y, n, (state.buffers.resourceAt(bufferTile(state.grid, m, tile, BufferRole.Product)!!)?.total ?: 0L)
                    .toFloat() / Extractor.BUFFER_CAP)
            }

            is Concentrator -> {
                footprintRect(state, m, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Concentrator))
                fillBar(x, y, n, massIn(m, tile, state.grid, state.buffers).toFloat() / BUFFER_BAR_FULL)
            }
            // A body with a bar, like every other buffered installation. Its own collar and the
            // berth markings are a docking-increment problem; drawn plainly here so the machine is
            // visible and selectable while the economy is what is being built.
            is DockingPort -> {
                footprintRect(state, m, Visual.MACHINE_INSET, kindColor(DeckMachineKind.DockingPort))
                fillBar(x, y, n, massIn(m, tile, state.grid, state.buffers).toFloat() / BUFFER_BAR_FULL)
            }
            is Electrolyzer -> {
                footprintRect(state, m, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Electrolyzer))
                fillBar(x, y, n, massIn(m, tile, state.grid, state.buffers).toFloat() / BUFFER_BAR_FULL)
            }
            is Furnace -> {
                footprintRect(state, m, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Furnace))
                fillBar(x, y, n, massIn(m, tile, state.grid, state.buffers).toFloat() / BUFFER_BAR_FULL)
            }
            // Two tiles: the chamber it is stored at and the bell in front of it, drawn as one
            // body so a motor reads as the object it is rather than as two machines. The nozzle
            // mark sits on the *outer* face of the bell, so which way a thruster pushes is readable
            // without selecting it — the one thing about a motor you cannot afford to get wrong.
            is Thruster -> {
                footprintRect(state, m, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Thruster))
                val bell = m.bell(state.grid)
                edgeMark(state.grid.xOf(bell), state.grid.yOf(bell), m.facing, Colors.VENT_CORE)
            }

            // A button: its face lights up while it is held, and its key is written on it by the
            // wiring panel rather than by the tile — a letter at this size would be a smudge.
            is WireButton -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(DeckMachineKind.KeyInput))
                val pressed = state.signals.at(tile) / SignalField.FULL.toFloat()
                tileRect(x, y, Visual.BUTTON_FACE, lerpColor(Colors.WIRE_DARK, Colors.WIRE_LIVE, pressed))
            }
            is Sensor -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Sensor))
                // Faces its target, and its eye glows with whatever it is putting on the wire — the
                // same ramp the wire itself uses, so a lit sensor and a lit run read as one thing.
                val emitting = lerpColor(Colors.WIRE_DARK, Colors.WIRE_LIVE, state.signals.at(tile) / SignalField.FULL.toFloat())
                edgeMark(x, y, m.facing, emitting)
                tileRect(x, y, Visual.SENSOR_EYE_SCALE, emitting)
            }
            // Pump intake: arrow shows facing (room direction).
            is Pump -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Pump))
                intakeArrow(x, y, m.facing)
            }

            is Vent -> {
                tileRect(x, y, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Vent))
                tileRect(x, y, Visual.VENT_CORE_SCALE, Colors.VENT_CORE)
            }
            // An iris: hull-coloured door, with a hole in it that grows as the signal does. The
            // opening is drawn in the vent's colour on purpose — both are holes onto the same space,
            // and the player should read them as the same kind of thing.
            // Tank: room-sized fill (legible at distance).
            is Storage -> {
                footprintRect(state, m, Visual.MACHINE_INSET, kindColor(DeckMachineKind.Storage))
                val stored = state.buffers.resourceAt(bufferTile(state.grid, m, tile, BufferRole.Inside)!!)
                val level = (stored?.total ?: 0L).toFloat() / Storage.CAP
                if (level > 0f) {
                    val h = level.coerceIn(0f, 1f) * (n - Visual.TANK_SPAN_INSET)
                    val bottom = y + n * 0.5f - Visual.TANK_BOTTOM_MARGIN
                    rect(
                        (x + 0.5f) * tilePx, (bottom - h * 0.5f) * tilePx,
                        (n - Visual.TANK_SPAN_INSET) * tilePx, h * tilePx,
                        stored?.color?.toLong() ?: 0x000000FF,
                    )
                }
            }
            is Airlock -> {
                tileRect(x, y, 1f, kindColor(DeckMachineKind.Airlock))
                val open = airlockOpenness(m, state.signals) / ApertureField.OPEN
                if (open > 0f) tileRect(x, y, Visual.MACHINE_INSET * open, Colors.VENT_CORE)
            }
        }
        drawPorts(state, m)
    }

    /**
     * The cursor's own ghost: what a click would put down, where it would land, and whether it would
     * be allowed — see [BuildPlan].
     *
     * **The same drawing in two colours**, and that is the whole design. A refused placement is not
     * drawn differently, or smaller, or with a symbol on it: it is the identical footprint, the
     * identical outline and the identical ports, in the colours that mean no. So the only thing the
     * eye has to compare between a good position and a bad one is the tint, and moving the mouse
     * along a wall answers "where does this fit" without a single click.
     *
     * ⚠️ **The footprint is asked for, never assumed.** A thruster's second tile is in front of the
     * cursor and a bridge's span turns with it, so a plan near the rim has no footprint at all —
     * [DeckMachine.tiles] would *throw* rather than answer, which is right for a machine that is
     * standing somewhere and wrong for one that is only being considered. That case is the one that
     * cannot be drawn as a shape, so it is drawn as a refusal on the cursor tile alone.
     */
    private fun drawPlan(state: VesselState, plan: BuildPlan) {
        // ⚠️ **A third colour, because it is a third answer.** Green for "this goes here", red for
        // "it does not", and this for "there is already one of these and the click will tune it" —
        // which is neither, and which is drawn over a machine the player can plainly see, so the
        // cursor has to say the tile being full is the reason it works rather than the reason it
        // will not. See [BuildPlan.settingsOnly].
        val fill = when {
            plan.settingsOnly -> Colors.PLAN_SETTINGS
            plan.allowed -> Colors.PLAN
            else -> Colors.PLAN_REFUSED
        }
        val edge = when {
            plan.settingsOnly -> Colors.PLAN_SETTINGS_EDGE
            plan.allowed -> Colors.PLAN_EDGE
            else -> Colors.PLAN_REFUSED_EDGE
        }
        val x = state.grid.xOf(plan.tile)
        val y = state.grid.yOf(plan.tile)
        when (val brush = plan.brush) {
            // A stub of the conduit at its own gauge, so a plan for a pipe is visibly thinner than
            // one for track. No arms: which way a run joins is decided by the drag, and drawing arms
            // before there is a drag would be inventing a shape the click will not produce.
            is Brush.Run -> {
                val gauge = when (brush.conduit) {
                    Conduit.Rail -> Visual.RAIL_DIAMETER
                    Conduit.Signal, Conduit.Power -> Visual.WIRE_DIAMETER
                }
                rect((x + 0.5f) * tilePx, (y + 0.5f) * tilePx, gauge * tilePx, gauge * tilePx, edge)
                tileRect(x, y, 1f, fill)
            }
            is Brush.Building -> {
                if (brush.kind.footprint(plan.tile, state.grid, plan.facing) == null) {
                    tileRect(x, y, 1f, fill)
                    frame(x, y, edge)
                    return
                }
                val proposed = newDeckMachine(brush.kind, plan.tile, plan.facing)
                footprintRect(state, proposed, Visual.MACHINE_INSET, fill)
                footprintOutline(state, proposed, edge)
                // Drawn refused as well as allowed, in their own white-in/green-out colours: they
                // are how the player reads the facing they picked, and a machine whose ports are
                // pointing the wrong way is a thing worth seeing *before* finding somewhere it fits.
                drawPorts(state, proposed)
            }
        }
    }

    /**
     * A rect over the whole of [m]'s footprint, inset a little — whatever shape that footprint is.
     *
     * **The only way a machine body is drawn.** It replaced a helper that took a tile and a size
     * and drew a square centred on it, which is the question a caller must stop answering for
     * itself: a bridge is a line, and a thruster's two tiles are the one it is stored at and the one
     * in front of it, so a square drawn off the anchor is wrong by half a tile in a direction that
     * depends on the facing. Folding the footprint's own bounding box cannot be wrong that way.
     *
     * Every footprint in the game fills its own bounding box, so a rect is the whole shape. The
     * first kind whose footprint has a hole or a corner in it needs a different helper, not a
     * fudge factor in this one.
     */
    private fun footprintRect(state: VesselState, m: DeckMachine, inset: Float, color: Long) =
        overFootprint(state, m) { cx, cy, tilesW, tilesH ->
            rect(cx, cy, (tilesW - (1f - inset)) * tilePx, (tilesH - (1f - inset)) * tilePx, color)
        }

    /** A hollow [footprintRect]: four thin sides around whatever shape the machine's footprint is. */
    private fun footprintOutline(state: VesselState, m: DeckMachine, color: Long) =
        overFootprint(state, m) { cx, cy, tilesW, tilesH ->
            val w = (tilesW - (1f - Visual.MACHINE_INSET)) * tilePx
            val h = (tilesH - (1f - Visual.MACHINE_INSET)) * tilePx
            val t = Visual.FRAME_THICKNESS * tilePx
            rect(cx, cy - (h - t) * 0.5f, w, t, color)
            rect(cx, cy + (h - t) * 0.5f, w, t, color)
            rect(cx - (w - t) * 0.5f, cy, t, h, color)
            rect(cx + (w - t) * 0.5f, cy, t, h, color)
        }

    /**
     * The bounding box of [m]'s footprint, handed to [draw] as world-pixel centre and size in tiles.
     *
     * The one place the shape of a machine is turned into a place on the screen, so the two helpers
     * above cannot come to disagree about where a bridge or a motor is. A **centre** and a size and
     * not a corner, because every rect this renderer draws is centred — see [rect].
     */
    private inline fun overFootprint(
        state: VesselState,
        m: DeckMachine,
        draw: (cx: Float, cy: Float, tilesW: Int, tilesH: Int) -> Unit,
    ) {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (t in m.tiles(state.grid)) {
            val tx = state.grid.xOf(t); val ty = state.grid.yOf(t)
            if (tx < minX) minX = tx
            if (ty < minY) minY = ty
            if (tx > maxX) maxX = tx
            if (ty > maxY) maxY = ty
        }
        draw(
            (minX + maxX + 1) * 0.5f * tilePx,
            (minY + maxY + 1) * 0.5f * tilePx,
            maxX - minX + 1,
            maxY - minY + 1,
        )
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
    /**
     * A saltire across a tile: condemned, and readable at a glance.
     *
     * The frame alone was not enough. It is a thin band in a colour a few shades off the machine it
     * surrounds, which is legible when you are looking for it and invisible when you are not — and
     * "is this thing on its way out?" is exactly the question a player asks *before* they know to
     * look. An X is the one mark nothing else in the world uses, so it cannot be mistaken for a
     * fitting, a port or a wire.
     *
     * ⚠️ **Stepped out of little squares rather than drawn as two rotated bars.** [rect]'s angle is
     * applied *before* its non-uniform scale, so a long thin rect asked to lie at 45° comes out as a
     * skewed parallelogram whose slope depends on the window's aspect ratio — and a [Coord] is half a
     * turn rather than a whole one, so the obvious eighth-turn is 22.5° and the two bars very nearly
     * lie on top of each other. Both were true of the first version of this, which drew one
     * horizontal smudge. Squares along the diagonals have neither problem.
     */
    private fun cross(x: Int, y: Int, color: Long) {
        val half = Visual.CROSS_SPAN * 0.5f
        val dot = Visual.CROSS_THICKNESS * tilePx
        for (i in 0..Visual.CROSS_STEPS) {
            val t = -half + (Visual.CROSS_SPAN * i / Visual.CROSS_STEPS)
            rect((x + 0.5f + t) * tilePx, (y + 0.5f + t) * tilePx, dot, dot, color)
            rect((x + 0.5f + t) * tilePx, (y + 0.5f - t) * tilePx, dot, dot, color)
        }
    }

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

    // ── The overlays' fade ────────────────────────────────────────────────────
    //
    // Every tint overlay is a picture of a field the sim rewrites once every eight ticks, so drawn
    // straight each one is a slideshow at eight frames a second — and a diagnostic that flickers is
    // one a player stops reading.
    //
    // ⛔ **The view remembers the values, the sim only says when.** [Motion] lives in the state
    // because which of three joined neighbours a packet came from is genuinely unrecoverable
    // downstream — the mover knows and the observer is guessing. A tile does not move, so two
    // readings of its temperature or its air are unambiguous and anything that can remember what it
    // drew last time can work the rest out. So the sim stamps *when* a pass ran (see [Cadence],
    // which costs two numbers per pass) and the view keeps the *values*, which cost a grid.

    /**
     * The bookkeeping every fading view shares: whether this frame holds, rolls forward or snaps,
     * and how far through the span it is.
     *
     * Split out because there are two payloads now — packed colours for the tint overlays, a vector
     * field for the flow arrows — and the rules about *when* to roll are the subtle half. Keeping
     * one copy of them is what stops the two drifting into disagreeing about what a resize means.
     */
    private class Span {
        enum class Step { Hold, Roll, Snap }

        private var stamp = NEVER_SAMPLED

        /**
         * Grid shape the payload is indexed against.
         *
         * ⚠️ **Shape rather than array length.** The grid can grow on one edge and shift every tile
         * index sideways (see `VesselState.resized`) without changing how many tiles there are.
         * Same length, different meaning, and a fade between two different tiles' values is a lie
         * the player would read as the field moving.
         */
        private var width = -1
        private var height = -1

        /** How far this frame is through the span. */
        var fade = 1f
            private set

        /** Forget everything, so the next [advance] snaps. For a view that has just been switched to. */
        fun forget() {
            stamp = NEVER_SAMPLED
        }

        /**
         * What this frame should do with its snapshots.
         *
         * [Step.Snap] — both snapshots set to the world as it is — whenever there is nothing honest
         * to fade from: the first frame the view is drawn, a grid that has changed shape, a world
         * that has just been loaded and whose stamp therefore goes backwards, or a view that has
         * been off long enough to have missed a pass entirely.
         */
        fun advance(grid: Grid, cadence: Cadence, simTime: Double): Step {
            val sameGrid = width == grid.width && height == grid.height
            val sampled = stamp != NEVER_SAMPLED
            // Guarded by `sampled`, which is what keeps the subtraction away from the sentinel.
            val passes = if (sampled) cadence.writtenAtTick - stamp else -1L
            val step = when {
                !sameGrid || !sampled -> Step.Snap
                passes == 0L -> Step.Hold                       // still inside the span
                passes in 1..cadence.spanTicks.toLong() -> Step.Roll
                else -> Step.Snap                               // missed a pass, or time went backwards
            }
            width = grid.width
            height = grid.height
            stamp = cadence.writtenAtTick
            fade = cadence.progress(simTime)
            return step
        }
    }

    /**
     * A grid of overlay colours, fading from what the last pass showed to what this one shows.
     *
     * ⚠️ **Colours, not the quantities behind them.** It is what makes one of these serve a
     * temperature, a millimole count, a gas mass and a mixture identically — a mixture in
     * particular has no sensible midpoint to interpolate, but the colour naming it does. It also
     * keeps the ramps' kinks out of the fade: the heat ramp changes which pair of endpoints it
     * mixes at ambient, and easing a kelvin across that would wobble where easing the colour
     * does not.
     *
     * ⚠️ **It draws one pass behind, deliberately.** [to] can only be sampled once the pass has
     * landed, so what it fades *from* has to be what the previous span was showing. The cost is an
     * eighth of a second of lag on an overlay; buying it back means the sim carrying a spare copy
     * of every field in the world.
     */
    private class Fading {
        private val span = Span()
        private var from = LongArray(0)
        private var to = LongArray(0)

        fun forget() = span.forget()

        fun sample(grid: Grid, cadence: Cadence, simTime: Double, colorAt: (TileIndex) -> Long) {
            when (span.advance(grid, cadence, simTime)) {
                Span.Step.Hold -> {}
                Span.Step.Roll -> {
                    val spare = from
                    from = to
                    to = spare
                    read(colorAt)
                }
                Span.Step.Snap -> {
                    if (to.size != grid.size) {
                        from = LongArray(grid.size)
                        to = LongArray(grid.size)
                    }
                    read(colorAt)
                    to.copyInto(from)
                }
            }
        }

        private fun read(colorAt: (TileIndex) -> Long) {
            for (i in to.indices) to[i] = colorAt(TileIndex(i))
        }

        /** A tile's colour, part-way from what it was to what it is. */
        fun color(tile: TileIndex): Long {
            val a = from[tile.index]
            val b = to[tile.index]
            // Nearly every tile, nearly every frame: a field that did not move here.
            return if (a == b) b else lerpColor(a, b, span.fade)
        }
    }

    /**
     * The flow field's own fade — the same span, a different thing to blend.
     *
     * Its own type rather than a colour, because a streak is a *direction* and a *length* and
     * neither survives being packed into one: two arrows drawn in the same white are not the same
     * arrow. Of the five overlays this is the one that stepped worst — measured over a burst of
     * injected gas, every streak on screen was pixel-identical for six ticks and then jumped at
     * once, by up to 719 of a possible 765 units on a pixel, which is a streak leaving a place
     * entirely rather than shading between two values.
     *
     * ⛔ **The raw components are what is interpolated, not the angle.** A flow that reverses then
     * shrinks to nothing and grows back the other way, which is what reversing looks like. Easing
     * the *angle* instead would swing the arrow through ninety degrees on its way round and show
     * the player a rotation that never happened — a confident lie about which way the air went, of
     * exactly the kind [Motion] exists to avoid.
     *
     * ⚠️ [speed] is carried separately rather than taken as the vector's length: `FlowField` divides
     * the net mass by the tile's own mass to get it, so it has a scale of its own and the two are
     * not recoverable from each other.
     */
    private class FlowFading {
        private val span = Span()
        private var fromX = FloatArray(0)
        private var fromY = FloatArray(0)
        private var fromSpeed = FloatArray(0)
        private var toX = FloatArray(0)
        private var toY = FloatArray(0)
        private var toSpeed = FloatArray(0)

        fun forget() = span.forget()

        fun sample(grid: Grid, cadence: Cadence, simTime: Double, field: FlowField) {
            when (span.advance(grid, cadence, simTime)) {
                Span.Step.Hold -> {}
                Span.Step.Roll -> {
                    var spare = fromX; fromX = toX; toX = spare
                    spare = fromY; fromY = toY; toY = spare
                    spare = fromSpeed; fromSpeed = toSpeed; toSpeed = spare
                    read(field)
                }
                Span.Step.Snap -> {
                    if (toX.size != grid.size) {
                        fromX = FloatArray(grid.size); toX = FloatArray(grid.size)
                        fromY = FloatArray(grid.size); toY = FloatArray(grid.size)
                        fromSpeed = FloatArray(grid.size); toSpeed = FloatArray(grid.size)
                    }
                    read(field)
                    toX.copyInto(fromX)
                    toY.copyInto(fromY)
                    toSpeed.copyInto(fromSpeed)
                }
            }
        }

        private fun read(field: FlowField) {
            for (i in toX.indices) {
                val tile = TileIndex(i)
                toX[i] = field.xAt(tile).toFloat()
                toY[i] = field.yAt(tile).toFloat()
                toSpeed[i] = field.speedAt(tile)
            }
        }

        fun xAt(tile: TileIndex): Float = blend(fromX, toX, tile)

        fun yAt(tile: TileIndex): Float = blend(fromY, toY, tile)

        fun speedAt(tile: TileIndex): Float = blend(fromSpeed, toSpeed, tile)

        private fun blend(from: FloatArray, to: FloatArray, tile: TileIndex): Float {
            // Still until sampled. The arrays are empty until the flow overlay is drawn, and every
            // caller today is inside that branch — but a bounds-tolerant read is what [Motion] does
            // for the same reason, and the alternative to it here is a crash rather than a wrong
            // colour.
            if (tile.index >= to.size) return 0f
            val a = from[tile.index]
            val b = to[tile.index]
            return if (a == b) b else a + (b - a) * span.fade
        }
    }

    private val overlayFade = Fading()
    private val flowFade = FlowFading()

    /** Which overlay [overlayFade] is holding — switching overlay throws its snapshots away. */
    private var fadedOverlay = Overlay.None

    /**
     * The pass whose span an overlay fades across.
     *
     * ⚠️ **Pressure fades on the fluid pass**, which is not the confusion it looks like:
     * `Stuff.pressureAt` is millimoles of gas in a tile, and the pass that moves gas between tiles
     * is diffusion. The pressure pass computes the forces that field exerts and moves nothing.
     */
    private fun cadenceOf(overlay: Overlay, state: VesselState): Cadence = when (overlay) {
        Overlay.Heat -> state.cadences.heat
        // Flow among them: `VesselState.flow` is a dry-run diffusion off the current air, so it
        // changes exactly when the air does. Its backdrop is a flat colour with nothing to fade; the
        // streaks drawn over it are what this span is really for. See [FlowFading].
        Overlay.Air, Overlay.Pressure, Overlay.Density, Overlay.Flow -> state.cadences.fluid
        Overlay.None -> Cadence.SETTLED
    }

    /** An overlay's colour for a tile, as of right now — what [Fading] samples once a span. */
    private fun overlayColor(overlay: Overlay, state: VesselState, tile: TileIndex): Long = when (overlay) {
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
        if (Negligible.pressure(pressure)) return Colors.TRANSPARENT
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
    /**
     * Too little air, or too little movement of it, to be worth drawing.
     *
     * ⚠️ **Asked of the faded vector**, so a streak dying away shrinks through the threshold instead
     * of being cut off at whatever length the pass left it. The density is the tick's own: it is a
     * question about whether there is any air here to talk about, not about the flow.
     */
    private fun negligibleFlow(state: VesselState, tile: TileIndex): Boolean =
        Negligible.flow(flowFade.xAt(tile).toLong(), flowFade.yAt(tile).toLong(), state.air.densityAt(tile))

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
        val speed = flowFade.speedAt(tile)
        // Still air guard (0/0), and trace air, which can report any speed at all. Visibility
        // threshold among the flows that survive is FLOW_MIN_FRACTION.
        if (speed <= 0f || negligibleFlow(state, tile)) return
        val fraction = speed / peak
        if (fraction < Visual.FLOW_MIN_FRACTION) return

        // Unit direction. Normalised against the pair's own magnitude rather than against `speed`,
        // which is that magnitude over the tile's mass and so carries a scale of its own.
        val fx = flowFade.xAt(tile)
        val fy = flowFade.yAt(tile)
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
         * A clock reading infinitely far past every stamp, so every [Cadence] reads as finished.
         *
         * The default for [draw], and what a test or a screenshot wants: a capture must show where
         * things **are**, not an interpolated position corresponding to no state the sim was ever
         * in. Infinity rather than a large number because it is the honest statement of the thing —
         * settled whatever the span, whatever the stamp, with no arithmetic to get wrong.
         */
        const val SETTLED = Double.POSITIVE_INFINITY

        private fun rgba(r: Int, g: Int, b: Int, a: Long): Long =
            (r.coerceIn(0, 255).toLong() shl 24) or (g.coerceIn(0, 255).toLong() shl 16) or
                (b.coerceIn(0, 255).toLong() shl 8) or a

        /** A [Fading]'s stamp before it has ever sampled — distinct from any real tick. */
        private const val NEVER_SAMPLED = Long.MIN_VALUE

        /**
         * [a] to [b] at [f], channel by channel — the wire's value ramp, and the overlays' fade.
         *
         * The whole readability argument for the signal layer rests on this: a wire you cannot see
         * the state of is worse than a global channel, because at least a channel had a readout. A
         * run that lights up as its sensor fills is the feature.
         *
         * In the companion because [Fading] is a nested class and needs it too.
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
        private const val RAMP_SPAN = 180f

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

        // ── Building and unbuilding ─────────────────────────────────────
        // A ghost is nearly the deck it is drawn on: present enough to see the shape of the run you
        // drew, faint enough that nobody mistakes it for track. It fades up to the conduit's own
        // colour as it fills — see [conduitColor].
        const val GHOST      = 0x1A2030C0L

        // A segment on its way out, warm where a ghost is cold. The direction has to be legible
        // without reading a number: "not yet" and "going" are opposite mistakes to make.
        const val SCRAPPING  = 0x8A4A2AFFL

        // ── The cursor's plan ───────────────────────────────────────────
        //
        // What the build tool is *about to* put down, as against [GHOST], which is something that
        // has been put down and is waiting for its metal. The two must not be confused, because the
        // player can act on exactly one of them: a plan follows the mouse and vanishes when the tool
        // is put away, and a ghost is a commitment that has to be demolished to undo.
        //
        // So this is bright where a ghost is nearly the deck it stands on, and cyan where a ghost is
        // slate. Nothing else in the world is this colour.
        const val PLAN        = 0x6EC8FF66L
        const val PLAN_EDGE   = 0xA8E4FFFFL

        // Refused: the same shape in the one colour the eye reads as "no". Red rather than
        // [SCRAPPING]'s burnt orange — a condemned machine is a decision the player made and this is
        // a decision the game is declining, and they should not be a shade apart.
        const val PLAN_REFUSED      = 0xE0402866L
        const val PLAN_REFUSED_EDGE = 0xFF6A4AFFL

        // Settings only: the click will re-tune the machine already standing here rather than build
        // anything — see [BuildPlan.settingsOnly]. Green, which is the one direction left: it is a
        // *yes*, so it cannot be the refusal red, and it is a different act from building, so it
        // must not be the plan's cyan. Same green the build panel says "copied from" in.
        const val PLAN_SETTINGS      = 0x3FBF7F66L
        const val PLAN_SETTINGS_EDGE = 0x6FCF97FFL

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
        const val TRANSPARENT   = 0x0L

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
        /** Algae green */
        const val ALGAE      = 0x78F858FFL
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
        /**
         * The condemned mark: nearly the width of the tile, and thick enough to survive being drawn
         * over a machine's own body without turning into a scratch.
         */
        const val CROSS_SPAN = 0.8f
        const val CROSS_THICKNESS = 0.16f

        /** How many squares each stroke is stepped out of. Enough to read as a line, not a dotted one. */
        const val CROSS_STEPS = 8

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
fun kindColor(conduit: Conduit): Long = when (conduit) {
    Conduit.Rail -> 0x39445AFFL
    Conduit.Power, Conduit.Signal -> 0x4A7A5AFFL
}

/** The swatch the build menu shows for a brush, whichever of the two kinds it names. */
fun brushColor(brush: Brush): Long = when (brush) {
    is Brush.Run -> kindColor(brush.conduit)
    is Brush.Building -> kindColor(brush.kind)
}
fun kindColor(kind: DeckMachineKind): Long = when (kind) {
    DeckMachineKind.Bridge -> 0x1A2030FFL
    DeckMachineKind.Gauge -> 0x39445AFFL
    DeckMachineKind.Valve -> 0xD8A860FFL
    DeckMachineKind.Hull -> 0x4A5464FFL
    DeckMachineKind.Airlock -> 0x6E7C90FFL
    DeckMachineKind.Vent -> 0x3A3A44FFL
    DeckMachineKind.Storage -> 0x3A4A5AFFL
    DeckMachineKind.Sensor -> 0x24303CFFL
    DeckMachineKind.KeyInput -> 0x2E3A4AFFL
    DeckMachineKind.Pump -> 0xB07840FFL
    // Warmer than the hull it sits in, so a berth reads as a way out rather than as more wall.
    DeckMachineKind.DockingPort -> 0x7A6A9AFFL
    DeckMachineKind.Thruster -> 0xC04A30FFL
    DeckMachineKind.Concentrator -> 0x2E5A6BFFL
    DeckMachineKind.Furnace -> 0x5E5A3BFFL
    // Cool blue-green: the electrical machine in a room full of hot ones.
    DeckMachineKind.Electrolyzer -> 0x2F5E64FFL
    DeckMachineKind.Extractor -> 0x6B4A2AFFL
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
    Species.Algae -> OutofspaceRenderer.Colors.ALGAE

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
