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
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Signals
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.contentsBreakdown
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.Ui

/**
 * The in-game UI, built with the shared immediate-mode toolkit.
 *
 * The wiring panel is the interesting one. It is the same *sentence* as Cyto's gene editor —
 * `WHEN <channel> AT <weight>` instead of `WHEN <condition> DO <action>` — and it is built on the
 * same `clauseRow` widget, three tappable tokens in a row. Reusing that shape rather than inventing
 * a node graph is the largest single saving available here, and it means the mechanic arrives
 * already legible to anyone who has met the other game.
 */
class OutofspaceHud {

    var onTogglePause: () -> Unit = {}
    var onReset: () -> Unit = {}

    /**
     * Saving is a **host** capability, not a game one: the sim knows how to turn a world into text
     * ([org.emerge.demo.outofspace.world.Save]) but nothing in shared code knows what a file is. A
     * host that can write one sets [canSave] and the two buttons appear; the others simply do not
     * offer what they cannot do, which beats a button that quietly fails.
     */
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
            // Drawn first, so every panel occludes it rather than the other way round.
            navView(s)
            panel(Anchor.TopLeft) {
                title("OUT OF SPACE")
                keyValue("Tick", s.tick.toString())
                keyValue("FPS", fps.toInt().toString())
                keyValue("Speed", "${controller.speed}x")
                gap()
                title("MASS BALANCE")
                keyValue("Mined", grams(s.minedGrams))
                keyValue("Aboard", grams(s.inTransitGrams))
                keyValue("- in storage", grams(s.stockpile.totalGrams))
                keyValue("- spilled", grams(s.debrisGrams))
                keyValue("Vented", grams(s.ventedGrams))
                // Storage is part of "aboard", not a term beside it: the stockpile is a view over the
                // storages rather than a separate account, so adding it here would double-count.
                val balanced = s.minedGrams == s.inTransitGrams + s.ventedGrams
                row(if (balanced) "balanced" else "LEAK", if (balanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("ATMOSPHERE")
                keyValue("Aboard", grams(s.atmosphereGrams))
                keyValue("Lost", grams(s.airVentedGrams))
                val airBalanced = s.atmosphereGrams + s.airVentedGrams == s.baselineAirGrams
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
                // The atmosphere's energy keeps its own ledger, for the same reason its mass does:
                // a break in one should not obscure the other. Two ways out now: overboard with
                // venting gas, and back into the fabric — so what the solids say they gave the air
                // appears here with the opposite sign, and both sides have to close on their own.
                val airHeatBalanced =
                    s.atmosphereJoules + s.airVentedJoules - s.solidToAirJoules == s.baselineAirJoules
                keyValue("Air heat vented", joules(s.airVentedJoules / 1000L))
                row(if (airHeatBalanced) "air heat balanced" else "AIR HEAT LEAK",
                    if (airHeatBalanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("FLIGHT")
                keyValue("Mass", grams(s.massGrams))
                keyValue("Thrust", "${s.netImpulseX}, ${s.netImpulseY}")
                keyValue("Speed", tiles(s.velocityX) + ", " + tiles(s.velocityY) + " /tick")
                keyValue("Position", tiles(s.positionX) + ", " + tiles(s.positionY))
                // What anything loose aboard is falling toward, which is the plating plus the engine.
                // In milli-g, because a breach is worth a fraction of one and a readout in whole g
                // would show nothing happening while quite a lot happens.
                keyValue("Felt gravity", "${milliG(s.feltGravity.x.raw)}, ${milliG(s.feltGravity.y.raw)} mg")
                // The debug engine's running total, shown whenever it is not zero and hidden when it
                // is, so the readout is an admission rather than furniture — see [Edit.Thrust]. The
                // ledger stays balanced *because* this is counted, which is the whole arrangement.
                if (s.debugImpulseX != 0L || s.debugImpulseY != 0L) {
                    keyValue("Debug engine", "${s.debugImpulseX}, ${s.debugImpulseY}", 0xC8A44AFFL, 0xC8A44AFFL)
                }
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
                    row("click place · right-click remove", 0x9A9A9AFFL)
                    // Track connects by being dragged, not by touching, so this is not a shortcut —
                    // it is the only way to build a run, and the player has to be told.
                    if (controller.brush.conduit != null) {
                        row("DRAG to connect · a click alone joins nothing", 0xE8B84AFFL)
                    }
                    row("R rotate brush · middle-drag pan", 0x9A9A9AFFL)
                } else {
                    row("click a machine to wire it", 0x9A9A9AFFL)
                    row("a sensor reads the tile it faces", 0x9A9A9AFFL)
                }
                row("W tool · wheel zoom · space pause", 0x9A9A9AFFL)
                // In the engine's own colour, and named as debug, because it is a stand-in that has
                // to look like one — a key nobody can find is a key nobody can tell you is wrong.
                row("arrows fly the ship  (debug engine)", 0xC8A44AFFL)
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
                button(if (controller.paused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { onTogglePause() }
                button("RESET", 0xCC3333FFL) { onReset() }
            }
        }
    }

    /**
     * **Where the ship is**, which nothing else in the game can show you.
     *
     * Zooming out does not answer this. The grid *is* the vessel's frame — it travels with the ship
     * and nothing on it moves because the ship does — so the widest possible view of the world shows
     * a small hull in an empty box no matter how far the vessel has flown. Open space has no
     * representation at all without this, and from increment H1 there will be things in it.
     *
     * The ship is fixed at the centre and the world slides under it, because that is the honest
     * frame and it is the one the grid already uses. See `docs/out-of-space-plan.md` §5f.
     *
     * ⚠️ **Two scales, deliberately.** Distance and velocity cannot share one: at a range wide
     * enough to hold a journey, a tile per tick of speed is a fraction of a pixel, and at a scale
     * that shows the speed the journey is off the screen. So the ring is distance and the needle is
     * speed, and the needle says what it is worth in text rather than pretending to be a distance.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.navView(s: VesselState) = canvas {
        val size = 190f * density
        val pad = 10f * density
        val x0 = (screenW - size) / 2f
        val y0 = screenH - size - pad
        val cx = x0 + size / 2f
        val cy = y0 + size / 2f

        // Opaque, and that is not a style choice. At any alpha the hull behind it shows through, and
        // the one thing this panel exists to say is that the world behind it is *not* where the ship
        // is — a nav view you can see the deck through is telling you the opposite of the truth.
        rect(x0, y0, size, size, 0x080D14FFL)
        border(x0, y0, size, size, 1f * density, 0x3A4A66FFL)
        // A crosshair rather than a grid: this is a bearing instrument, and ruled squares at a range
        // that changes would read as a scale that does not.
        rect(x0 + 2f * density, cy, size - 4f * density, 1f * density, 0x1C2740FFL)
        rect(cx, y0 + 2f * density, 1f * density, size - 4f * density, 0x1C2740FFL)

        val perPx = (size / 2f - 6f * density) / NAV_RANGE_TILES

        // Where the journey started, and for now the only thing out there. It is what makes the
        // panel show *motion* rather than a dot in a box, and rocks join it in H1.
        val ox = cx - s.positionX.toFloat() / Flight.PER_TILE * perPx
        val oy = cy - s.positionY.toFloat() / Flight.PER_TILE * perPx
        if (ox > x0 && ox < x0 + size && oy > y0 && oy < y0 + size) {
            val d = 2.5f * density
            rect(ox - d, oy - d, d * 2f, d * 2f, 0x5A82A8FFL)
            // Above the marker, not below it. Below puts the word across the centre of the box for
            // every ship that has flown up and to the right of where it started, which is where the
            // ship marker lives — and the label would then be drawn over the thing it is not naming.
            label("origin", ox, oy - d - 9f * density, 8f * density, 0x5A82A8FFL)
        }

        // The needle. Its own scale — see the note above — and drawn from the ship outward, so a
        // vessel under way points where it is going and a stationary one shows nothing at all.
        val vx = s.velocityX.toFloat() / Flight.PER_TILE
        val vy = s.velocityY.toFloat() / Flight.PER_TILE
        val needle = size / 2f - 8f * density
        val speed = kotlin.math.sqrt(vx * vx + vy * vy)
        if (speed > 0f) {
            val reach = needle * (speed / NAV_FULL_SCALE_SPEED).coerceAtMost(1f)
            line(cx, cy, cx + vx / speed * reach, cy + vy / speed * reach, 1.5f * density, 0x6ED09AFFL)
        }

        // The ship, last, so nothing is drawn over it — and on a dark pad, so it stays legible when
        // the needle or a marker passes under it.
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

    /**
     * A line, as a chain of squares, because the canvas draws axis-aligned rectangles and a velocity
     * does not point along an axis. Stepped by half a thickness so the chain has no gaps in it.
     */
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

    /**
     * What is actually inside the thing under the pointer.
     *
     * Ore is a *mixture*, and until this existed nothing in the game said so — you could watch a
     * refinery run for an hour and never learn that its ore was 41% iron, let alone that the
     * concentrate leaving the front was 75%. Every buffer is listed separately, because "this
     * processor holds 6kg" is far less useful than knowing which of its three buffers is the stuck
     * one.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.inspectPanel(controller: OutofspaceController, index: Int) {
        if (index < 0) return
        val s = controller.state
        val machine = s.machineCovering(index)
        val spill = s.debris[index]
        // A bare tile with a heap on it is still worth inspecting -- otherwise the material you just
        // dumped on the deck would be visible but unreadable, which is the gap the analyser existed
        // to close in the first place.
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
            // The track on this tile, and anything riding on it. Listed before the building because
            // it is literally on top of it, and because a run threaded under a machine is otherwise
            // impossible to read.
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

    /**
     * Where the tile sits and how hot it is — the two facts that will matter most once air arrives,
     * and the ones with nowhere else to live.
     */
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
        // Every solid thing standing here, each with its own temperature. A tile can hold a rail, a
        // conduit and a machine at once, and one averaged TEMP for the three of them was exactly the
        // reading the body model exists to stop giving — a cold copper line under a furnace is the
        // interesting case, and an average erases it.
        for (body in s.bodies) {
            if (index !in body.tiles) continue
            val k = body.kelvin
            keyValue(
                body.material.label,
                "${k}K  (${k - 273}C)",
                0x9A9A9AFFL,
                if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
        }
        // Wherever there is gas, not only inside — a vented plume is out in the vacuum by definition,
        // and it was the one thing the fluid sim does that the inspector could not be pointed at.
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
            // Beside pressure, because the two being different is the whole point: equal readings
            // mean ordinary air, and a gap between them means the tile has been sorted by weight.
            keyValue("DENSITY", "${density * 100 / AirField.AMBIENT_AIR.total}% atm", 0x9A9A9AFFL, 0x9AA4B4FFL)
            // The air's own temperature, which is its own number and not the fabric's above. The two
            // are coupled now, so a gap between them is a wall heating a room rather than two
            // unrelated readings. This is the one the fluid acts on -- it is what sets pressure, so a
            // tile reading hot and over-pressured is a tile that is about to rise.
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

    /**
     * Which way a tile's air is going, as an eight-point compass arrow.
     *
     * A bearing rather than two signed components, because "is it leaving through that breach" is the
     * question, and reading a sign pair as a direction is a step the panel can take for the player.
     * **+y is down** — see [org.emerge.demo.outofspace.world.fluid.EdgeGrid] — so the vertical glyphs
     * are the opposite way round from what the arithmetic first suggests.
     */
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

    /**
     * A mixture as percentages, richest first — `IRON 41%  SILI 30%  COPP 18%  TITA 11%`.
     *
     * Percentages rather than masses because the question being asked is almost always "how clean is
     * this", and four-letter species keep the line narrow enough not to stretch the panel.
     */
    private fun composition(mixture: Mixture): String {
        if (mixture.isEmpty) return "empty"
        val total = mixture.total
        return Species.ALL
            .filter { mixture[it] > 0L }
            .sortedByDescending { mixture[it] }
            .joinToString("  ") { "${it.name.take(4).uppercase()} ${mixture[it] * 100 / total}%" }
    }

    /**
     * The wiring editor: one tappable sentence per term of `RUN = Σ(signal × weight)`.
     *
     * Tapping the channel cycles it, tapping the weight cycles the ladder, tapping the `×` removes
     * the term. Cycling rather than opening a dropdown keeps the whole editor to three tap targets
     * per row, which is what makes it work under a thumb.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.wiringPanel(controller: OutofspaceController) {
        if (controller.tool != Tool.Wire) return
        val index = controller.selected
        if (index < 0) return
        val machine = controller.state[index] ?: return
        val grid = controller.state.grid

        // Bottom-right rather than centred: a centre anchor centres on the *screen*, which is the
        // wrong centre when the build palette owns the bottom-left corner — they overlapped.
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

    /**
     * A distance or a speed in [Flight.PER_TILE]'s billionths, to six decimal places of a tile.
     *
     * Six because a bare breached hull picks up about a quarter of a millionth of a tile per tick
     * per tick, and a readout that rounded that to zero would report an engine as broken.
     */
    private fun tiles(v: Long): String {
        val sign = if (v < 0L) "-" else ""
        val a = if (v < 0L) -v else v
        val frac = (a % Flight.PER_TILE) / 1000L
        return "$sign${a / Flight.PER_TILE}.${frac.toString().padStart(6, '0')}"
    }

    /** A [Frac] gravity as thousandths of the one g [VesselState.DEFAULT_GRAVITY] means. */
    private fun milliG(raw: Long): Long = raw * 1000L / Int.MAX_VALUE.toLong()

    companion object {
        /**
         * Half-width of the nav view, in tiles.
         *
         * A **provisional** number, and the plan says so: how far apart rocks want to be and how
         * fast a ship actually travels are both unknown until H1 flies, and picking a range before
         * there is anything to see at it would be picking it from nothing. Two hundred and fifty-six
         * is about twenty seconds of held debug thrust, which makes the origin marker drift visibly
         * without leaving immediately. It becomes a dial when there is a reason to turn one.
         */
        const val NAV_RANGE_TILES: Float = 256f

        /** The speed at which the needle is fully extended, in tiles per tick. Provisional likewise. */
        const val NAV_FULL_SCALE_SPEED: Float = 2f
    }
}
