package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.RockDensityField
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Signals
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.contentsBreakdown
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
    fun build(ui: Ui, controller: OutofspaceController, fps: Float, hovered: Int = -1) {
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
                keyValue("Mass", grams(s.massGrams))
                keyValue("Thrust", "${s.netImpulseX}, ${s.netImpulseY}")
                keyValue("Speed", tiles(s.velocityX) + ", " + tiles(s.velocityY) + " /tick")
                keyValue("Position", tiles(s.positionX) + ", " + tiles(s.positionY))
                // Felt gravity (milli-g for breach sensitivity).
                keyValue("Felt gravity", "${milliG(s.feltGravity.x.raw)}, ${milliG(s.feltGravity.y.raw)} mg")
                // Debug engine (hidden when zero).
                if (s.debugImpulseX != 0L || s.debugImpulseY != 0L) {
                    keyValue("Debug engine", "${s.debugImpulseX}, ${s.debugImpulseY}", 0xC8A44AFFL, 0xC8A44AFFL)
                }
                // Bodies (hidden when empty).
                if (s.bodies.isNotEmpty()) {
                    gap()
                    title("BODIES")
                    keyValue("Adrift", "${s.bodies.size}")
                    keyValue("Mass", grams(s.bodyGrams))
                    keyValue("Captured", grams(s.bodyCapturedGrams))
                    keyValue("Extracted", grams(s.extractedGrams))
                    // What is left is what arrived less what the extractors have eaten.
                    val bodyBalanced =
                        s.bodyGrams == s.baselineBodyGrams + s.bodyCapturedGrams - s.extractedGrams
                    row(
                        if (bodyBalanced) "balanced" else "LEAK",
                        if (bodyBalanced) 0x6ED09AFFL else 0xE05A4AFFL,
                    )
                }
                gap()
                title("MASS BALANCE")
                keyValue("Extracted", grams(s.extractedGrams))
                keyValue("Aboard", grams(s.inTransitGrams))
                keyValue("- in storage", grams(s.stockpile.totalGrams))
                keyValue("- spilled", grams(s.debrisGrams))
                keyValue("Vented", grams(s.ventedGrams))
                // Storage is a view over storages (part of "aboard").
                val balanced = s.extractedGrams == s.inTransitGrams + s.ventedGrams
                row(if (balanced) "balanced" else "LEAK", if (balanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("ATMOSPHERE")
                keyValue("Aboard", grams(s.atmosphereGrams))
                keyValue("Lost", grams(s.airVentedGrams))
                // Only shown once it is non-zero: the bellows is a debug tool, and a row reading
                // "injected 0g" on every world that never touched it is a row nobody reads.
                if (s.injectedAirGrams != 0L) keyValue("Injected", grams(s.injectedAirGrams))
                val airBalanced = s.airBalance == 0L
                row(if (airBalanced) "balanced" else "LEAK", if (airBalanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("ENERGY")
                keyValue("Generated", joules(s.generatedJoules))
                keyValue("Radiated", joules(s.radiatedJoules))
                keyValue("Stored", joules(s.storedJoules))
                keyValue("To air", joules(s.solidToAirJoules / 1000L))
                val heatBalanced = s.storedJoules + s.radiatedJoules + s.solidToAirJoules -
                    s.generatedJoules - s.constructionJoules == s.baselineJoules
                row(if (heatBalanced) "balanced" else "LEAK", if (heatBalanced) 0x6ED09AFFL else 0xE05A4AFFL)
                // Atmosphere energy ledger (separate from mass ledger).
                val airHeatBalanced =
                    s.atmosphereJoules + s.airVentedJoules - s.solidToAirJoules == s.baselineAirJoules
                keyValue("Air heat vented", joules(s.airVentedJoules / 1000L))
                row(if (airHeatBalanced) "air heat balanced" else "AIR HEAT LEAK",
                    if (airHeatBalanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("SIGNALS")
                for (channel in Channel.EMITTABLE) {
                    val value = s.signals[channel]
                    keyValue(channel.label, "${value / 10}%", channel.color, if (value > 0) channel.color else 0x5A5A5AFFL)
                }
            }

            panel(Anchor.TopRight) {
                title("STOCKPILE")
                row("(sum of all storage aboard)", 0x7A8A9AFFL)
                val entries = s.stockpile.entries()
                if (entries.isEmpty()) {
                    row("(no storage holding anything)", 0x9A9A9AFFL)
                } else {
                    for ((form, mixture) in entries) {
                        keyValue(form.name, grams(mixture.total))
                        val dominant = mixture.dominant
                        if (dominant != null && mixture[dominant] < mixture.total) {
                            // Purity is the interesting number, so say it rather than hide it.
                            val pct = mixture[dominant] * 100 / mixture.total
                            row("   $pct% ${dominant.name}", 0x9A9A9AFFL)
                        }
                    }
                }
            }

            panel(Anchor.BottomLeft) {
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
                    for (kind in MachineKind.ALL) {
                        val selected = kind == controller.brush
                        button(
                            if (selected) "> ${kind.label}" else "  ${kind.label}",
                            if (selected) kindColor(kind) or 0xFFL else 0x232A38FFL,
                        ) { controller.brush = kind }
                    }
                    gap()
                    row("click or drag to place", 0x9A9A9AFFL)
                    // Track: drag to connect (not by touching).
                    if (controller.brush.conduit != null) {
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
                } else if (controller.tool == Tool.Inject) {
                    title("INJECT  ·  ${Edit.INJECT_GRAMS}G / TICK")
                    row("hold over a permeable tile", 0x9A9A9AFFL)
                    // Named as debug in the same yellow the engine row uses, because it is the same
                    // kind of lie: it makes matter, and says so in the atmosphere panel.
                    row("debug tool · gas from nowhere, booked as INJECTED", 0xC8A44AFFL)
                } else if (controller.tool == Tool.InjectWater) {
                    title("WATER  ·  ${Edit.WATER_INJECT_GRAMS}G / TICK")
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

            inspectPanel(controller, if (hovered >= 0) hovered else controller.selected)
            wiringPanel(controller)

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

    /** Contents of machine/tile under pointer (mixture breakdown per buffer). */
    private fun org.emerge.render.torus.ui.UiBuilder.inspectPanel(controller: OutofspaceController, index: Int) {
        if (index < 0) return
        val s = controller.state
        val machine = s.machineCovering(index)
        val spill = s.debris[index]
        // Bare tile with debris (still inspectable).
        if (machine == null && spill.isEmpty() && s.railAt(index) == null) return
        val grid = s.grid

        panel(Anchor.TopRight) {
            val what = machine?.kind?.label ?: "DECK"
            title("INSPECT  ·  $what (${grid.xOf(index)}, ${grid.yOf(index)})")
            tileConditions(controller, index)

            if (spill.isNotEmpty()) {
                title("SPILLED")
                for (resource in spill) {
                    keyValue(resource.form.name, grams(resource.mass))
                    row("   " + composition(resource.mixture), 0x9AA4B4FFL)
                }
                gap()
            }
            // Rail on this tile (listed before machine — on top).
            val segment = s.railAt(index)
            if (segment != null) {
                title(if (segment.isGauge) "GAUGE" else "RAIL")
                if (segment.isGauge) {
                    if (segment.lastDominant == null) {
                        row("nothing has passed through yet", 0x9A9A9AFFL)
                    } else {
                        keyValue("LAST SEEN", segment.lastForm?.name ?: "?")
                        keyValue(
                            segment.lastDominant.name.uppercase(),
                            "${segment.lastPurity / 10}%",
                            0x9A9A9AFFL,
                            speciesColor(segment.lastDominant),
                        )
                        keyValue("of", grams(segment.lastMass))
                    }
                    segment.channel?.let { keyValue("reporting on", it.label, 0x9A9A9AFFL, it.color) }
                }
                val riding = segment.held
                if (riding == null) row("(nothing on it)", 0x9A9A9AFFL)
                else keyValue("carrying", grams(riding.mass))
                gap()
            }

            if (machine == null) return@panel

            val buffers = contentsBreakdown(machine)
            if (buffers.isEmpty()) {
                row("(empty)", 0x9A9A9AFFL)
            } else {
                for ((label, resource) in buffers) {
                    keyValue(label, "${grams(resource.mass)}  ${resource.form.name}")
                    row("   " + composition(resource.mixture), 0x9AA4B4FFL)
                }
            }
        }
    }

    /** Tile conditions: location + temperature. */
    private fun org.emerge.render.torus.ui.PanelBuilder.tileConditions(
        controller: OutofspaceController,
        index: Int,
    ) {
        val s = controller.state
        val structure = s.structure[index]
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
            if (index !in body.tiles) continue
            val k = body.kelvin
            keyValue(
                body.material.label,
                "${k}K  (${k - 273}C)",
                0x9A9A9AFFL,
                if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
        }
        // Gas (interior + vented plumes).
        val density = s.air.densityAt(index)
        if (structure == Structure.Interior || density > 0L) {
            val percent = s.pressurePercentAt(index)
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
            // Density beside pressure (gap = weight sorting).
            keyValue("DENSITY", "${density * 100 / AirField.AMBIENT_AIR.total}% atm", 0x9A9A9AFFL, 0x9AA4B4FFL)
            // Air temperature (fluid acts on this — sets pressure).
            if (density > 0L) {
                val airK = s.airKelvinAt(index)
                keyValue(
                    "AIR TEMP",
                    "${airK}K  (${airK - 273}C)",
                    0x9A9A9AFFL,
                    if (airK > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
                )
            }
            val speed = s.flow.speedAt(index)
            if (speed > 0f) {
                keyValue("FLOW", "${(speed * 1000f).toInt()} mtiles/tick ${bearing(s, index)}", 0x9A9A9AFFL, 0x9AA4B4FFL)
            }
            val mix = s.air.mixtureAt(index)
            if (!mix.isEmpty) row("   " + composition(mix), 0x9AA4B4FFL)
        }
    }

    /** Air direction as 8-point compass (+y is down). */
    private fun bearing(s: VesselState, index: Int): String {
        val x = s.flow.xAt(index)
        val y = s.flow.yAt(index)
        // A component under an eighth of the other is not a direction, it is rounding.
        val ax = if (x < 0) -x else x
        val ay = if (y < 0) -y else y
        val horizontal = if (ax * 8 < ay) "" else if (x > 0) ">" else "<"
        val vertical = if (ay * 8 < ax) "" else if (y > 0) "v" else "^"
        return horizontal + vertical
    }

    /** Mixture as percentages, richest first (4-letter species abbrev). */
    private fun composition(mixture: Mixture): String {
        if (mixture.isEmpty) return "empty"
        val total = mixture.total
        return Species.ALL
            .filter { mixture[it] > 0L }
            .sortedByDescending { mixture[it] }
            .joinToString("  ") { "${it.name.take(4).uppercase()} ${mixture[it] * 100 / total}%" }
    }

    /** Wiring editor: WHEN/PLUS terms (tap channel/weight to cycle, × to delete). */
    private fun org.emerge.render.torus.ui.UiBuilder.wiringPanel(controller: OutofspaceController) {
        if (controller.tool != Tool.Wire) return
        val index = controller.selected
        if (index < 0) return
        val machine = controller.state[index] ?: return
        val grid = controller.state.grid

        // Bottom-right (not centred — build palette owns bottom-left).
        panel(Anchor.BottomRight, rowHeight = 20f) {
            title("WIRING  ·  ${machine.kind.label} (${grid.xOf(index)}, ${grid.yOf(index)})")

            if (machine is Sensor) {
                val watched = grid.neighbour(index, machine.facing)
                val reading = controller.state.signals[machine.channel]
                clauseRow(
                    lhs = "EMIT ON",
                    cmp = machine.channel.label,
                    rhs = "${reading / 10}%",
                    onLhs = { controller.cycleSensorChannel(index, 1) },
                    onCmp = { controller.cycleSensorChannel(index, 1) },
                    onRhs = { controller.cycleSensorChannel(index, 1) },
                )
                val target = if (watched >= 0) controller.state[watched] else null
                row("watching: ${target?.kind?.label ?: "(nothing)"}", 0x9A9A9AFFL)
                gap()
            }

            val gauge = controller.state.railAt(index)
            if (gauge?.channel != null) {
                clauseRow(
                    lhs = "REPORT ON",
                    cmp = gauge.channel.label,
                    rhs = "${gauge.lastPurity / 10}%",
                    onLhs = { controller.cycleSensorChannel(index, 1) },
                    onCmp = { controller.cycleSensorChannel(index, 1) },
                    onRhs = { controller.cycleSensorChannel(index, 1) },
                )
                gap()
            }

            val action = Action.Run
            val triggers = machine.wiring.triggers(action)
            val activation = machine.wiring.activation(action, controller.state.signals)
            keyValue(action.label, "${activation / 10}%", 0x9A9A9AFFL, if (activation > 0) 0x6ED09AFFL else 0xE05A4AFFL)

            if (triggers.isEmpty()) {
                row("(never runs — no terms)", 0xE05A4AFFL)
            } else {
                for ((slot, trigger) in triggers.withIndex()) {
                    clauseRow(
                        lhs = if (slot == 0) "WHEN " + trigger.channel.label else "PLUS " + trigger.channel.label,
                        cmp = "x",
                        rhs = signed(trigger.percent),
                        onLhs = { controller.cycleTriggerChannel(index, action, slot, 1) },
                        onCmp = { controller.wire(index, action, slot, null) },
                        onRhs = { controller.cycleTriggerWeight(index, action, slot, 1) },
                    )
                }
            }
            button("+ ADD TERM", 0x2E5A6BFFL) {
                controller.wire(index, action, triggers.size, Trigger(Channel.Red, Signals.FULL))
            }
            row("tap channel / weight to cycle, x to delete", 0x7A7A7AFFL)
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

    /** Joules get large fast; kJ and MJ keep the panel narrow. */
    private fun joules(j: Long): String = when {
        j < 10_000L -> "${j}J"
        j < 10_000_000L -> "${j / 1000}kJ"
        else -> "${j / 1_000_000}MJ"
    }

    /** Grams are the sim's unit; kilograms are the reading unit past a certain size. */
    private fun grams(g: Long): String =
        if (g < 10_000L) "${g}g" else "${g / 1000}.${(g % 1000) / 100}kg"

    /** Distance/speed in PER_TILE billionths, to 6 decimals (breach sensitivity). */
    private fun tiles(v: Long): String {
        val sign = if (v < 0L) "-" else ""
        val a = if (v < 0L) -v else v
        val frac = (a % Flight.PER_TILE) / 1000L
        return "$sign${a / Flight.PER_TILE}.${frac.toString().padStart(6, '0')}"
    }

    /** A [Frac] gravity as thousandths of the one g [VesselState.PLATING_ONE_G] means. */
    private fun milliG(raw: Long): Long = raw * 1000L / Int.MAX_VALUE.toLong()

    companion object {
        /** Nav view half-width (provisional — 20s debug thrust). */
        const val NAV_RANGE_TILES: Float = 256f

        /** The speed at which the needle is fully extended, in tiles per tick. Provisional likewise. */
        const val NAV_FULL_SCALE_SPEED: Float = 2f
    }
}
