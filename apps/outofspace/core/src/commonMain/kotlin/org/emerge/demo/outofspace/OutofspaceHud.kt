package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.RockDensityField
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Negligible
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.contentsBreakdown
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.Ui

/** In-game UI panel (flight data, stockpile, tool/wiring). */
class OutofspaceHud {

    var onTogglePause: () -> Unit = {}
    var onReset: () -> Unit = {}
    var onFit: () -> Unit = {}

    /** Save/load (host capability — requires file system access). */
    var canSave: Boolean = false
    var onSave: () -> Unit = {}
    var onLoad: () -> Unit = {}

    /** What the last save or load did, shown next to the buttons. Blank until something happens. */
    var saveStatus: String = ""

    /**
     * @param hovered the tile under the pointer, or -1. Desktop and web have a pointer; on touch
     *   there is no hover, so the inspector falls back to the machine the player last tapped.
     */
    fun build(ui: Ui, controller: OutofspaceController, fps: Float, hovered: TileIndex = TileIndex.NONE) {
        val s = controller.state
        ui.frame {
            // Drawn first (occludes everything).
            navView(s)
            panel(Anchor.TopLeft) {
                title("OUT OF SPACE")
                keyValue("Tick", s.tick.toString())
                keyValue("FPS", fps.toInt().toString())
                keyValue("Speed", "${controller.speed}x")
                gap()
                title("FLIGHT")
                keyValue("Mass", mass(s.mass))
                keyValue("Thrust", "${s.netImpulseX}, ${s.netImpulseY}")
                keyValue("Speed", tiles(s.velocityX) + ", " + tiles(s.velocityY) + " /tick")
                keyValue("Position", tiles(s.positionX) + ", " + tiles(s.positionY))
                // Felt gravity (milli-g for breach sensitivity).
                keyValue("Felt gravity", "${milliG(s.feltGravity.x.raw)}, ${milliG(s.feltGravity.y.raw)} mg")
                // Debug engine (hidden when zero).
                if (s.debugImpulseX != 0L || s.debugImpulseY != 0L) {
                    keyValue("Debug engine", "${s.debugImpulseX}, ${s.debugImpulseY}", 0xC8A44AFFL, 0xC8A44AFFL)
                }
                gap()
                title("MASS BALANCE")
                keyValue("Extracted", mass(s.extractedMass))
                keyValue("Aboard", mass(s.inTransitMass))
                keyValue("- in storage", mass(s.stockpile.totalMass))
                keyValue("Vented", mass(s.ventedMass))
                // Storage is a view over storages (part of "aboard").
                val balanced = s.extractedMass == s.inTransitMass + s.ventedMass
                row(if (balanced) "balanced" else "LEAK", if (balanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("ATMOSPHERE")
                keyValue("Aboard", mass(s.atmosphereMass))
                keyValue("Lost", mass(s.airVentedMass))
                // Only shown once it is non-zero: the bellows is a debug tool, and a row reading
                // "injected 0g" on every world that never touched it is a row nobody reads.
                if (s.injectedAirMass != 0L) keyValue("Injected", mass(s.injectedAirMass))
                val airBalanced = s.airBalance == 0L
                row(if (airBalanced) "balanced" else "LEAK", if (airBalanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("ENERGY")
                keyValue("Generated", energy(s.generatedEnergy))
                keyValue("Radiated", energy(s.radiatedEnergy))
                keyValue("Stored", energy(s.storedEnergy))
                keyValue("To air", energy(s.solidToAirEnergy))
                keyValue("Air heat vented", energy(s.airVentedEnergy))
                // ⚠️ The two `balanced` rows that stood here — solid heat and air heat — are PARKED,
                // per step 3 of apps/outofspace/PLAN_unit_rescale.md. The energy accumulators
                // overflow at the target mass unit and that is accepted for the duration, so a LEAK
                // lamp here would be lit by the plan rather than by a bug, and a lamp that is always
                // on is one nobody looks at again. The readouts above stay: they are still the
                // numbers, it is only the verdict on them that is suspended. Mass balance, which
                // survives the rescale, keeps its lamp and is the tripwire that matters.
                row("(energy ledgers parked — unit rescale §3)", 0x8A8A8AFFL)
                gap()
                // One row per circuit the player has actually laid, rather than six fixed colours
                // most of which read zero. An empty list here means no wire aboard, which is the
                // honest thing to say.
                title("SIGNALS")
                if (s.signals.networkCount == 0) {
                    row("(no wire laid)", 0x5A5A5AFFL)
                } else {
                    for (id in 0 until s.signals.networkCount) {
                        val value = s.signals.ofNetwork(id)
                        keyValue("circuit $id", "${value / 10}%", 0x9A9A9AFFL, if (value > 0) 0x6EE08AFFL else 0x5A5A5AFFL)
                    }
                }
            }

            panel(Anchor.TopRight) {
                title("STOCKPILE")
                row("(sum of all storage aboard)", 0x7A8A9AFFL)
                val held = s.stockpile.held
                if (held.isEmpty) {
                    row("(no storage holding anything)", 0x9A9A9AFFL)
                } else {
                    keyValue("TOTAL", mass(held.total))
                    val dominant = held.dominant
                    if (dominant != null && held[dominant] < held.total) {
                        // Purity is the interesting number, so say it rather than hide it.
                        val pct = held[dominant] * 100 / held.total
                        row("   $pct% ${dominant.name}", 0x9A9A9AFFL)
                    }
                }
            }

            panel(Anchor.BottomLeft) {
                // Which mode owns the keyboard, and how to change it — said first and loudly,
                // because a player whose WASD has stopped panning needs the answer immediately.
                val flying = controller.mode == Mode.Flight
                button(
                    if (flying) "FLIGHT MODE  ·  F to build" else "BUILD MODE  ·  F to fly",
                    if (flying) 0x8A5A2AFFL else 0x232A38FFL,
                ) { controller.mode = controller.mode.next }
                if (flying) {
                    val held = InputKey.ALL.filter { InputKey.heldIn(controller.heldKeys, it) }
                    row(
                        if (held.isEmpty()) "arrows / WASD / Z / X drive your buttons"
                        else "holding: ${held.joinToString(" ") { it.label }}",
                        if (held.isEmpty()) 0x9A9A9AFFL else 0x6EE08AFFL,
                    )
                }
                gap()
                title("TOOL  ·  ${controller.tool.label}   VIEW  ·  ${controller.overlay.label}")
                controlRowOfTools(controller)
                actionRow(
                    Overlay.entries.map { view ->
                        Triple(
                            if (view == controller.overlay) "> ${view.label}" else view.label,
                            if (view == controller.overlay) 0x8A5A2AFFL else 0x232A38FFL,
                        ) { controller.overlay = view }
                    },
                )
                gap()
                if (controller.tool == Tool.Build) {
                    title("BUILD  ·  ${controller.brush.label} facing ${controller.brushFacing.name.uppercase()}")
                    for (option in Brush.ALL) {
                        val selected = option == controller.brush
                        button(
                            if (selected) "> ${option.label}" else "  ${option.label}",
                            if (selected) brushColor(option) or 0xFFL else 0x232A38FFL,
                        ) { controller.brush = option }
                    }
                    gap()
                    row("click or drag to place", 0x9A9A9AFFL)
                    // Track: drag to connect (not by touching).
                    if (controller.brush is Brush.Run) {
                        row("DRAG to connect · a click alone joins nothing", 0xE8B84AFFL)
                    }
                    row("R rotate brush", 0x9A9A9AFFL)
                } else if (controller.tool == Tool.Delete) {
                    title("DELETE  ·  ${controller.deleteLayer.label}")
                    actionRow(
                        DeleteLayer.entries.map { layer ->
                            Triple(
                                if (layer == controller.deleteLayer) "> ${layer.label}" else layer.label,
                                if (layer == controller.deleteLayer) 0xA5453AFFL else 0x232A38FFL,
                            ) { controller.deleteLayer = layer }
                        },
                    )
                    gap()
                    row("click or drag to remove · E cycles layer", 0x9A9A9AFFL)
                    row("TOP takes one layer at a time", 0x9A9A9AFFL)
                } else if (controller.tool == Tool.Cut) {
                    title("CUT  ·  ${controller.cutConduit.label}")
                    actionRow(
                        Tool.CUTTABLE.map { conduit ->
                            Triple(
                                if (conduit == controller.cutConduit) "> ${conduit.label}" else conduit.label,
                                if (conduit == controller.cutConduit) 0xA5453AFFL else 0x232A38FFL,
                            ) { controller.cutConduit = conduit }
                        },
                    )
                    gap()
                    row("drag ALONG a run to sever · E cycles conduit", 0x9A9A9AFFL)
                    row("cuts the joins you draw · other joins stay", 0xE8B84AFFL)
                } else if (controller.tool == Tool.Inject) {
                    title("INJECT  ·  ${Edit.INJECT_MASS}G / TICK")
                    row("hold over a permeable tile", 0x9A9A9AFFL)
                    // Named as debug in the same yellow the engine row uses, because it is the same
                    // kind of lie: it makes matter, and says so in the atmosphere panel.
                    row("debug tool · gas from nowhere, booked as INJECTED", 0xC8A44AFFL)
                } else if (controller.tool == Tool.InjectWater) {
                    title("WATER  ·  ${Edit.WATER_INJECT_MASS}G / TICK")
                    row("hold over a permeable tile · ~1s fills a tile", 0x9A9A9AFFL)
                    row("debug tool · water from nowhere, booked as INJECTED", 0xC8A44AFFL)
                    // Said on the tool itself rather than left in a plan file, because the number is
                    // surprising enough that anyone pouring water will otherwise assume a bug.
                    row("arrives at ${Edit.WATER_INJECT_KELVIN}K — this model boils water at -33C", 0xC8A44AFFL)
                } else {
                    row("click a machine to wire it", 0x9A9A9AFFL)
                    row("a sensor reads the tile it faces", 0x9A9A9AFFL)
                }
                row("Q tool · WASD or right-drag pan · wheel zoom", 0x9A9A9AFFL)
                row("space pause", 0x9A9A9AFFL)
                // Debug engine row (yellow, named).
                row("arrows fly the ship  (debug engine)", 0xC8A44AFFL)
                row("F8 fit grid", 0x9A9A9AFFL)
                if (canSave) row("F9 save · F10 load", 0x9A9A9AFFL)
            }

            inspectPanel(controller, if (hovered != TileIndex.NONE) hovered else controller.selected)
            wiringPanel(controller)
            storagePanel(controller)
            decomposerPanel(controller)

            panel(Anchor.BottomRight) {
                if (canSave) {
                    if (saveStatus.isNotEmpty()) row(saveStatus, 0x9AA4B4FFL)
                    actionRow(
                        listOf(
                            Triple("SAVE", 0x2E5A6BFFL) { onSave() },
                            Triple("LOAD", 0x2E5A6BFFL) { onLoad() },
                        ),
                    )
                }
                button("FIT", 0x2E5A6BFFL) { onFit() }
                button(if (controller.paused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { onTogglePause() }
                button("RESET", 0xCC3333FFL) { onReset() }
            }
        }
    }

    /** Ship nav: origin marker + velocity needle (two scales — distance vs velocity). */
    private fun org.emerge.render.torus.ui.UiBuilder.navView(s: VesselState) = canvas {
        val size = 190f * density
        val pad = 10f * density
        val x0 = (screenW - size) / 2f
        val y0 = screenH - size - pad
        val cx = x0 + size / 2f
        val cy = y0 + size / 2f

        // Opaque background (hull must not show through).
        rect(x0, y0, size, size, 0x080D14FFL)
        border(x0, y0, size, size, 1f * density, 0x3A4A66FFL)
        // Crosshair (not grid — bearing instrument).
        rect(x0 + 2f * density, cy, size - 4f * density, 1f * density, 0x1C2740FFL)
        rect(cx, y0 + 2f * density, 1f * density, size - 4f * density, 0x1C2740FFL)

        val perPx = (size / 2f - 6f * density) / NAV_RANGE_TILES

        // Rock density field: one textured quad, sampled with hardware bilinear filtering from a
        // texture RockSpawner/RockDensityField keeps in lockstep with the chunk window — so it slides
        // continuously with the vessel's own tile position, not in per-chunk jumps.
        val vesselTileX = s.positionX.toFloat() / Flight.PER_TILE
        val vesselTileY = s.positionY.toFloat() / Flight.PER_TILE
        val chunksPerAxis = RockSpawner.WINDOW_BUFFER_SIZE.toFloat()
        fun worldTileToU(worldTileX: Float) = (vesselTileX + worldTileX) / RockSpawner.CHUNK_SIZE / chunksPerAxis - RockSpawner.windowBaseChunkX / chunksPerAxis
        fun worldTileToV(worldTileY: Float) = (vesselTileY + worldTileY) / RockSpawner.CHUNK_SIZE / chunksPerAxis - RockSpawner.windowBaseChunkY / chunksPerAxis
        image(
            x0, y0, size, size,
            RockDensityField.textureId(),
            uvMinX = worldTileToU((x0 - cx) / perPx), uvMinY = worldTileToV((y0 - cy) / perPx),
            uvMaxX = worldTileToU((x0 + size - cx) / perPx), uvMaxY = worldTileToV((y0 + size - cy) / perPx),
        )

        // Origin marker (shows motion, not position).
        val ox = cx - s.positionX.toFloat() / Flight.PER_TILE * perPx
        val oy = cy - s.positionY.toFloat() / Flight.PER_TILE * perPx
        if (ox > x0 && ox < x0 + size && oy > y0 && oy < y0 + size) {
            val d = 2.5f * density
            rect(ox - d, oy - d, d * 2f, d * 2f, 0x5A82A8FFL)
            // Label above marker (avoids overlap).
            label("origin", ox, oy - d - 9f * density, 8f * density, 0x5A82A8FFL)
        }

        // Velocity needle (drawn from ship outward; stationary = nothing).
        val vx = s.velocityX.toFloat() / Flight.PER_TILE
        val vy = s.velocityY.toFloat() / Flight.PER_TILE
        val needle = size / 2f - 8f * density
        val speed = kotlin.math.sqrt(vx * vx + vy * vy)
        if (speed > 0f) {
            val reach = needle * (speed / NAV_FULL_SCALE_SPEED).coerceAtMost(1f)
            line(cx, cy, cx + vx / speed * reach, cy + vy / speed * reach, 1.5f * density, 0x6ED09AFFL)
        }

        // Ship (drawn last, legible over everything).
        val h = 2.5f * density
        rect(cx - h - density, cy - h - density, (h + density) * 2f, (h + density) * 2f, 0x080D14FFL)
        rect(cx - h, cy - h, h * 2f, h * 2f, 0xFFFFFFFFL)

        label("NAV  ·  ${NAV_RANGE_TILES.toInt()} tiles", cx, y0 + 3f * density, 9f * density, 0x7A8A9AFFL)
        label(
            "${tiles(s.positionX)}, ${tiles(s.positionY)}",
            cx, y0 + size - 11f * density, 9f * density, 0x9AA4B4FFL,
        )
    }

    /** A hollow box, which the canvas has no primitive for: four rectangles is the whole of it. */
    private fun org.emerge.render.torus.ui.CanvasBuilder.border(
        x: Float, y: Float, w: Float, h: Float, t: Float, color: Long,
    ) {
        rect(x, y, w, t, color)
        rect(x, y + h - t, w, t, color)
        rect(x, y, t, h, color)
        rect(x + w - t, y, t, h, color)
    }

    /** Line as stepped chain of squares (no axis alignment). */
    private fun org.emerge.render.torus.ui.CanvasBuilder.line(
        x0: Float, y0: Float, x1: Float, y1: Float, t: Float, color: Long,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = (kotlin.math.sqrt(dx * dx + dy * dy) / (t / 2f)).toInt().coerceIn(1, 400)
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            rect(x0 + dx * f - t / 2f, y0 + dy * f - t / 2f, t, t, color)
        }
    }

    /** Contents of tile under pointer (mixture breakdown per buffer). */
    private fun org.emerge.render.torus.ui.UiBuilder.inspectPanel(controller: OutofspaceController, tile: TileIndex) {
        if (tile.index < 0) return
        val s = controller.state
        val machine = s.machineCovering(tile)
        val grid = s.grid

        panel(Anchor.TopRight) {
            val what = machine?.kind?.label ?: "DECK"
            title("INSPECT  ·  $what (${grid.xOf(tile)}, ${grid.yOf(tile)})")
            tileConditions(controller, tile)

            // Rail on this tile (listed before machine — on top).
            val segment = s.railAt(tile)
            if (segment != null) {
                // The gauge is a building standing over the track now, so it is titled by whatever
                // is on the deck and the track under it is just track.
                val gauge = s.deck[tile] as? Gauge
                title(if (gauge != null) "GAUGE" else "RAIL")
                if (gauge != null) {
                    if (gauge.lastDominant == null) {
                        row("nothing has passed through yet", 0x9A9A9AFFL)
                    } else {
                        keyValue(
                            gauge.lastDominant.name.uppercase(),
                            "${gauge.lastPurity / 10}%",
                            0x9A9A9AFFL,
                            speciesColor(gauge.lastDominant),
                        )
                        keyValue("of", mass(gauge.lastMass))
                    }
                    keyValue("reporting on", "the wire beneath it", 0x9A9A9AFFL, 0x6EE08AFFL)
                }
                val riding = controller.state.rail.massAt(tile)
                if (riding == 0L) row("(nothing on it)", 0x9A9A9AFFL)
                else keyValue("carrying", mass(riding))
                gap()
            }

            if (machine == null) return@panel

            val buffers = contentsBreakdown(machine, tile, controller.state.grid, controller.state.buffers)
            if (buffers.isEmpty()) {
                row("(empty)", 0x9A9A9AFFL)
            } else {
                for ((label, resource) in buffers) {
                    keyValue(label, mass(resource.total))
                    val rows = composition(resource).split('\n')
                    for (r in rows) {
                        row(" $r", 0x9AA4B4FFL)
                    }
                }
            }
        }
    }

    /** Tile conditions: location + temperature. */
    private fun org.emerge.render.torus.ui.PanelBuilder.tileConditions(
        controller: OutofspaceController,
        tile: TileIndex,
    ) {
        val s = controller.state
        val structure = s.structure[tile.index]
        keyValue(
            "PLACE",
            when (structure) {
                Structure.Vacuum -> "OUTSIDE"
                Structure.Hull -> "HULL"
                Structure.Interior -> "INSIDE"
                Structure.Machine -> "MACHINE"
            },
            0x9A9A9AFFL,
            if (structure == Structure.Vacuum) 0x7A8AA0FFL else 0x9ED0B0FFL,
        )
        // Each solid body's temp (not averaged — cold line under furnace is interesting).
        for (body in s.solids) {
            if (tile != body.tile) continue
            val k = body.kelvin
            keyValue(
                body.material.label,
                "${k}K  (${k - 273}C)",
                0x9A9A9AFFL,
                if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
        }
        // Gas (interior + vented plumes). A trace is not a plume — see [Negligible] — but an interior
        // tile is still worth a pressure reading when it has been emptied, since "0% atm" inside the
        // vessel is the single most useful thing this panel says.
        val density = s.air.densityAt(tile)
        val trace = Negligible.gas(density)
        if (structure == Structure.Interior || !trace) {
            val percent = s.pressurePercentAt(tile)
            keyValue(
                "PRESSURE",
                "$percent% atm",
                0x9A9A9AFFL,
                when {
                    percent < 40 -> 0xE05A4AFFL
                    percent < 85 -> 0xE0A93AFFL
                    else -> 0x9ED0B0FFL
                },
            )
            // Below the floor there is nothing to describe: no density worth a percentage, no
            // temperature of a gas that isn't there, no flow (its speed is a ratio, so a trace tile
            // can report any speed), and no composition of five mass.
            if (trace) {
                row("   (no gas to speak of)", 0x7A8A9AFFL)
                return
            }
            // Density beside pressure (gap = weight sorting).
            keyValue("DENSITY", "${density * 100 / Stuff.AMBIENT_AIR.total}% atm", 0x9A9A9AFFL, 0x9AA4B4FFL)
            // Air temperature (fluid acts on this — sets pressure).
            val airK = s.airKelvinAt(tile)
            keyValue(
                "AIR TEMP",
                "${airK}K  (${airK - 273}C)",
                0x9A9A9AFFL,
                if (airK > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
            val speed = s.flow.speedAt(tile)
            if (speed > 0f && !Negligible.flow(s.flow.xAt(tile), s.flow.yAt(tile), density)) {
                keyValue("FLOW", "${(speed * 1000f).toInt()} mtiles/tick ${bearing(s, tile)}", 0x9A9A9AFFL, 0x9AA4B4FFL)
            }
            val mix = s.air.mixtureAt(tile)
            if (!mix.isEmpty) {
                val rows = composition(mix, 5).split('\n')
                for (r in rows) {
                    row(" $r", 0x9AA4B4FFL)
                }
            }
        }
    }

    /** Air direction as 8-point compass (+y is down). */
    private fun bearing(s: VesselState, tile: TileIndex): String {
        val x = s.flow.xAt(tile)
        val y = s.flow.yAt(tile)
        // A component under an eighth of the other is not a direction, it is rounding.
        val ax = if (x < 0) -x else x
        val ay = if (y < 0) -y else y
        val horizontal = if (ax * 8 < ay) "" else if (x > 0) ">" else "<"
        val vertical = if (ay * 8 < ax) "" else if (y > 0) "v" else "^"
        return horizontal + vertical
    }

    /** Mixture as percentages, richest first. */
    private fun composition(mixture: Mixture, maxEntries: Int = 3): String {
        if (mixture.isEmpty) return "empty"
        val total = mixture.total
        val present = Species.ALL.filter { mixture[it] > 0L }.sortedByDescending { mixture[it] }
        // Only top 2 minerals are shown, with remaining composition represented as "other"
        val named = if (present.size > maxEntries) present.take(2) else present
        val pcts = named.map { mixture[it] * 100 / total }
        val remainingPercent = 100L - pcts.sum()
        val listed = named.indices.joinToString("\n") { "${pcts[it].toString().padStart(3)}% ${named[it].name.uppercase()}" }
        return if (present.size <= maxEntries) listed else "$listed\n${remainingPercent.toString().padStart(3)}% other"
    }

    /**
     * The lock on the selected warehouse: what it is holding, and the threshold to hold it to.
     *
     * ⛔ **No species list.** The button locks the tank onto whatever it is already full of — see
     * [org.emerge.demo.outofspace.Edit.LockStorage]. Offering the player a menu of every material
     * in the game would ask them to name things they have never seen, and would let them lock a
     * warehouse onto something that has never come aboard.
     *
     * Shares the bottom-right corner with the wiring editor, so it stands down while the wire tool
     * is out rather than drawing over it.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.storagePanel(controller: OutofspaceController) {
        if (controller.tool == Tool.Wire) return
        val tile = controller.selected
        if (tile == TileIndex.NONE) return
        val storage = controller.state.machineCovering(tile) as? Storage ?: return
        val grid = controller.state.grid
        val centre = storage.center
        val store = bufferTile(grid, storage, centre, BufferRole.Inside)
        val held = store?.let { controller.state.buffers.resourceAt(it) }
        val filter = storage.filter

        panel(Anchor.BottomRight, rowHeight = 20f) {
            title("STORAGE  ·  (${grid.xOf(centre)}, ${grid.yOf(centre)})")
            if (filter == null) {
                val dominant = held?.dominant
                if (dominant == null) {
                    row("TAKES ANYTHING", 0x9ED0B0FFL)
                    row("(empty — fill it before locking)", 0x9A9A9AFFL)
                } else {
                    keyValue("TAKES", "ANYTHING", 0x9A9A9AFFL, 0x9ED0B0FFL)
                    button(
                        "LOCK TO ${dominant.name.uppercase()}",
                        0x2E5A6BFFL,
                    ) { controller.lockStorage(tile, SpeciesFilter.DEFAULT_PERCENT) }
                    row("nothing purer than the bar will be sent here", 0x7A7A7AFFL)
                }
            } else {
                keyValue(
                    "LOCKED TO",
                    filter.species.name.uppercase(),
                    0x9A9A9AFFL,
                    speciesColor(filter.species),
                )
                clauseRow(
                    lhs = "AT LEAST",
                    cmp = "${filter.minPercent}%",
                    rhs = "pure",
                    onLhs = { controller.cycleStorageFilter(tile, 1) },
                    onCmp = { controller.cycleStorageFilter(tile, 1) },
                    onRhs = { controller.cycleStorageFilter(tile, 1) },
                )
                button("UNLOCK", 0x6B3A3AFFL) { controller.lockStorage(tile, null) }
                row("tap the bar to raise the threshold", 0x7A7A7AFFL)
            }
        }
    }

    /**
     * The two dials on the selected thermal decomposer: how hot, and how long.
     *
     * ⚠️ **Two dials because conversion is asymptotic.** There is no moment at which a charge is
     * finished — a reaction approaches completion and never arrives — so the machine cannot decide
     * when to let go and the player says instead. Hotter converts faster but spends more element and
     * leaks more heat into the room; longer converts more of each charge but throttles throughput.
     *
     * ⚠️ **"TICKS" is provisional.** This is the first duration the game shows anybody, and what a
     * tick should be called in front of a player is not decided — see [ThermalDecomposer.DWELLS].
     *
     * Shares the bottom-right corner with the storage lock and the wiring editor, and stands down for
     * the wire tool exactly as they do. A tile cannot be both a warehouse and a furnace, so the two
     * machine panels cannot collide.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.decomposerPanel(controller: OutofspaceController) {
        if (controller.tool == Tool.Wire) return
        val tile = controller.selected
        if (tile == TileIndex.NONE) return
        val machine = controller.state.machineCovering(tile) as? ThermalDecomposer ?: return
        val grid = controller.state.grid
        val centre = machine.center

        val chamber = bufferTile(grid, machine, centre, BufferRole.Inside)
        val charge = chamber?.let { controller.state.buffers.resourceAt(it) }
        val chargeKelvin = chamber?.let { controller.state.buffers.stuff.kelvinAt(it) } ?: 0

        panel(Anchor.BottomRight, rowHeight = 20f) {
            title("THERMAL DECOMPOSER  ·  (${grid.xOf(centre)}, ${grid.yOf(centre)})")

            // ⛔ **Not `clauseRow`**, though the storage lock next door uses one. That control is a
            // *clause* editor — "AT LEAST | 70% | pure" — and its middle cell is a fixed three
            // characters wide, sized for a comparison operator. "2400 K" and "5000 TICKS" do not fit
            // in it, and the way they do not fit is to be silently clipped: the panel renders, reads
            // almost right, and shows the player "NO HOLI".
            button(
                listOf("HOLD AT  " to 0x9A9A9AFFL, "${machine.setTemperature} K" to 0xFFFFFFFFL),
                0x2E5A6BFFL,
            ) { controller.cycleDecomposerTemperature(tile, 1) }
            button(
                listOf(
                    "FOR  " to 0x9A9A9AFFL,
                    // ⚠️ "TICKS" is provisional — see [ThermalDecomposer.DWELLS].
                    (if (machine.dwellTicks == 0) "NO HOLD" else "${machine.dwellTicks} TICKS") to 0xFFFFFFFFL,
                ),
                0x2E5A6BFFL,
            ) { controller.cycleDecomposerDwell(tile, 1) }

            gap()

            if (charge == null) {
                row("(no charge in the chamber)", 0x9A9A9AFFL)
            } else {
                // What it is *now*, not what went in — the whole point of the wait is that the
                // chemistry may have changed it, and a readout naming the input would be describing
                // something that is no longer there.
                // Coloured by whether it is *there yet* rather than by how hot it is, because that
                // is the question the panel is for: below the setpoint the element is still working
                // and the dwell has not started counting.
                keyValue(
                    "CHARGE",
                    "$chargeKelvin K  (${chargeKelvin - 273}C)",
                    0x9A9A9AFFL,
                    if (chargeKelvin >= machine.setTemperature) 0xE0864AFFL else 0x9AC0E0FFL,
                )
                // One row per line, like every other caller: `composition` returns a newline-joined
                // block and a `row` draws a single line, so handing it the whole string renders the
                // first species and silently swallows the rest.
                for (line in composition(charge, maxEntries = 3).split('\n')) row(line, 0xC8C8C8FFL)
                if (machine.dwellTicks > 0) {
                    keyValue(
                        "HELD",
                        "${machine.heldTicks} of ${machine.dwellTicks}",
                        0x9A9A9AFFL,
                        if (machine.heldTicks >= machine.dwellTicks) 0x6EE08AFFL else 0xE0A93AFFL,
                    )
                }
            }
            // ⚠️ No semicolon: the bitmap font has no glyph for one and draws "?" instead. The
            // interpunct is already used in every panel title, so it is known to exist.
            row("tap a dial to raise it  ·  wraps around", 0x7A7A7AFFL)
        }
    }

    /** Wiring editor: WHEN/PLUS terms (tap channel/weight to cycle, × to delete). */
    private fun org.emerge.render.torus.ui.UiBuilder.wiringPanel(controller: OutofspaceController) {
        if (controller.tool != Tool.Wire) return
        val tile = controller.selected
        if (tile == TileIndex.NONE) return
        val machine = controller.state.deck[tile] ?: return
        machineWiringPanel(controller, tile, machine)
    }

    private fun org.emerge.render.torus.ui.UiBuilder.machineWiringPanel(controller: OutofspaceController, tile: TileIndex, machine: DeckMachine) {
        val grid = controller.state.grid

        // Bottom-right (not centred — build palette owns bottom-left).
        panel(Anchor.BottomRight, rowHeight = 20f) {
            title("WIRING  ·  ${machine.kind.label} (${grid.xOf(tile)}, ${grid.yOf(tile)})")

            // A transmitter no longer picks anything, so there is nothing to tap: it drives the wire
            // under it, and the readout's job is to say whether there is one.
            val wired = controller.state.networks[tile] >= 0

            if (machine is WireButton) {
                clauseRow(
                    lhs = "WHEN KEY",
                    cmp = machine.key.label,
                    rhs = if (wired) "${controller.state.signals.at(tile) / 10}%" else "(no wire)",
                    onLhs = { controller.cycleInputKey(tile, 1) },
                    onCmp = { controller.cycleInputKey(tile, 1) },
                    onRhs = { controller.cycleInputKey(tile, 1) },
                )
                row("held in FLIGHT mode — press F to switch", 0x9A9A9AFFL)
                gap()
            }

            if (machine is Sensor) {
                val watched = grid.neighbour(tile, machine.facing)
                keyValue(
                    "EMITS",
                    if (wired) "${controller.state.signals.at(tile) / 10}% on circuit ${controller.state.networks[tile]}"
                    else "(no wire under it)",
                    0x9A9A9AFFL,
                    if (wired) 0x6EE08AFFL else 0xE0A93AFFL,
                )
                val target = if (watched != TileIndex.NONE) controller.state.machineCovering(watched) else null
                if (target != null) {
                    row("watching: ${target.kind.label}", 0x9A9A9AFFL)
                } else {
                    val targetDeck = if (watched != TileIndex.NONE) controller.state.machineCovering(watched) else null
                    row("watching: ${targetDeck?.kind?.label ?: "(nothing)"}", 0x9A9A9AFFL)
                }
                gap()
            }

            val gauge = controller.state.deck[tile] as? Gauge
            if (gauge != null) {
                keyValue(
                    "REPORTS",
                    if (wired) "${gauge.lastPurity / 10}% on circuit ${controller.state.networks[tile]}"
                    else "(no wire under it)",
                    0x9A9A9AFFL,
                    if (wired) 0x6EE08AFFL else 0xE0A93AFFL,
                )
                gap()
            }

            val action = Action.Run
            val triggers = machine.wiring.triggers(action)
            val activation = machine.wiring.activation(action, controller.state.signals.at(tile))
            keyValue(action.label, "${activation / 10}%", 0x9A9A9AFFL, if (activation > 0) 0x6ED09AFFL else 0xE05A4AFFL)

            if (triggers.isEmpty()) {
                row("(never runs — no terms)", 0xE05A4AFFL)
            } else {
                for ((slot, trigger) in triggers.withIndex()) {
                    clauseRow(
                        lhs = if (slot == 0) "WHEN " + trigger.source.label else "PLUS " + trigger.source.label,
                        cmp = "x",
                        rhs = signed(trigger.percent),
                        onLhs = { controller.cycleTriggerSource(tile, action, slot, 1) },
                        onCmp = { controller.wire(tile, action, slot, null) },
                        onRhs = { controller.cycleTriggerWeight(tile, action, slot, 1) },
                    )
                }
            }
            button("+ ADD TERM", 0x2E5A6BFFL) {
                controller.wire(tile, action, triggers.size, Trigger(SignalSource.Wire, SignalField.FULL))
            }
            row("tap source / weight to cycle, x to delete", 0x7A7A7AFFL)
            row(if (wired) "WIRE reads circuit ${controller.state.networks[tile]}" else "WIRE reads 0 — no wire under this tile", 0x7A7A7AFFL)
        }
    }

    private fun org.emerge.render.torus.ui.PanelBuilder.controlRowOfTools(controller: OutofspaceController) {
        actionRow(
            Tool.entries.map { tool ->
                Triple(
                    if (tool == controller.tool) "> ${tool.label}" else tool.label,
                    if (tool == controller.tool) 0x3A6EA5FFL else 0x232A38FFL,
                ) { controller.tool = tool }
            },
        )
    }

    private fun signed(percent: Int): String = if (percent >= 0) "+$percent%" else "$percent%"

    /**
     * Energy, read in joules whatever the sim's own unit currently is.
     *
     * The panel is the player's, and the player never chose [Budget.NANOJOULES_PER_UNIT] — so the
     * conversion happens here, once, rather than every readout carrying a factor. Joules get large
     * fast, so kJ and MJ keep the panel narrow.
     */
    private fun energy(v: Long): String {
        val j = v / Budget.JOULE
        return when {
            j < 10_000L -> "${j}J"
            j < 10_000_000L -> "${j / 1000}kJ"
            else -> "${j / 1_000_000}MJ"
        }
    }

    /**
     * Mass, read in grams whatever the sim's own unit currently is — the twin of [energy], and see
     * its note for why the conversion belongs here.
     *
     * Tonnes earn a tier because a vessel is tens of them: a hull plate quoted in kilograms is six
     * digits before it means anything to anyone.
     */
    private fun mass(v: Long): String {
        val g = v / Budget.GRAM
        return when {
            g < 10_000L -> "${g}g"
            g < 10_000_000L -> "${g / 1000}.${(g % 1000) / 100}kg"
            else -> "${g / 1_000_000}.${(g % 1_000_000) / 100_000}t"
        }
    }

    /** Distance/speed in PER_TILE billionths, to 6 decimals (breach sensitivity). */
    private fun tiles(v: Long): String {
        val sign = if (v < 0L) "-" else ""
        val a = if (v < 0L) -v else v
        val frac = (a % Flight.PER_TILE) / 1000L
        return "$sign${a / Flight.PER_TILE}.${frac.toString().padStart(6, '0')}"
    }

    /** Gravity as thousandths of the one g [VesselState.PLATING_ONE_G] means. */
    private fun milliG(raw: Long): Long = raw * 1000L / Int.MAX_VALUE.toLong()

    companion object {
        /** Nav view half-width (provisional — 20s debug thrust). */
        const val NAV_RANGE_TILES: Float = 256f

        /** The speed at which the needle is fully extended, in tiles per tick. Provisional likewise. */
        const val NAV_FULL_SCALE_SPEED: Float = 2f
    }
}
