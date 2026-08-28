package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.ReactionInfo
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.abundanceOf
import org.emerge.demo.outofspace.chem.abundanceRank
import org.emerge.demo.outofspace.chem.compositionOf
import org.emerge.demo.outofspace.chem.occursNaturally
import org.emerge.demo.outofspace.chem.fluid
import org.emerge.demo.outofspace.chem.isElement
import org.emerge.demo.outofspace.chem.reactionsConsuming
import org.emerge.demo.outofspace.chem.reactionsProducing
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Structure
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
import org.emerge.demo.outofspace.world.machine.Furnace
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.contentsBreakdown
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Segment
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.PanelBuilder
import org.emerge.render.torus.ui.Ui
import org.emerge.render.torus.ui.UiBuilder
import org.emerge.demo.outofspace.world.Stockpile

/**
 * A full-screen picker spawned from the bar under the nav map — see `OutofspaceHud.bottomBar`.
 *
 * ⛔ **One at a time, and that is the point of an enum rather than a set of flags.** A sheet dims the
 * world behind it, so two open at once is two scrims and a player who cannot tell which of them a
 * click will reach.
 *
 * ⚠️ **A sheet is modal, so the bar is unreachable while one is up** — the way out is the sheet's own
 * dismiss or the scrim, never the button that opened it. The bar's toggle is therefore an *open* in
 * practice; it is written as a toggle because that is what the gesture means, not because the second
 * half of it can currently be reached.
 */
enum class Sheet { None, Tool, Brush, Material, View, Menu }

/**
 * How many buildable species the stockpile panel names before it stops counting.
 *
 * A shortlist and not an inventory: the question the panel answers is "what could I build with right
 * now", and past the first handful the answer stops helping and starts pushing the panels below it
 * off the screen. The remainder is counted rather than dropped, so a player can tell the difference
 * between "that is all of it" and "there is more".
 */
private const val STOCKPILE_LINES = 6

/*
 * ⛔ **`MATERIAL_LINES = 4` stood here and its removal is the point of the picker moving.** It capped
 * the list because it sat inside a panel that auto-sizes to its content, under a brush list already
 * a dozen rows long. The justification was that a player chooses between the two or three materials
 * they have a useful quantity of — which is the *stockpile* panel's argument and wrong here: the
 * things a player has least of are exactly the ones they are choosing deliberately, and those were
 * the entries the cap hid. The list scrolls now and shows everything.
 */

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

    /** What the last clipboard action did, shown next to save status. Cleared after being read. */
    var clipboardStatus: String = ""

    /**
     * Which inspector sections the player has folded shut, and which they have opened.
     *
     * Two sets rather than one, because a section states its own default — see [section]. A single
     * "open" set would make every section that starts open read as shut on the first frame, and a
     * single "shut" set would do the same to the ones that start closed. What is stored either way
     * is only the *disagreements* with the default, so a section whose default later changes moves
     * with it for every player who never touched it.
     */
    /**
     * Which full-screen picker is open, if any.
     *
     * ⛔ **View state, so it lives on the HUD and not on the controller.** Nothing in the sim or in a
     * save has an opinion about whether a menu is showing, and putting it on the controller would
     * make "the player has the material list open" a fact about the vessel. Same argument the
     * collapse sets below are held here by.
     */
    private var openSheet: Sheet = Sheet.None

    private val collapsed = mutableSetOf<String>()
    private val expanded = mutableSetOf<String>()

    /**
     * @param hovered the tile under the pointer, or -1. Desktop and web have a pointer; on touch
     *   there is no hover, so the inspector falls back to the machine the player last tapped.
     */
    fun build(ui: Ui, controller: OutofspaceController, fps: Float, hovered: TileIndex = TileIndex.NONE) {
        val s = controller.state
        // ⚠️ **Once per frame, and it used to be twice.** `VesselState.stockpile` is a `get()` that
        // sweeps every buffer, every belt, the whole deck and every conduit layer; two panels asking
        // for it independently walked the world twice for one number apiece.
        val stock = s.stockpile
        ui.frame {
            panel(Anchor.TopLeft) {
                title("OUT OF SPACE")
                // The controller's count, not the state's: `state.tick` counts frozen ticks too and
                // would climb while the game was stopped. See [OutofspaceController.livedTicks].
                keyValue("Tick", controller.livedTicks.toString())
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
                keyValue("- in storage", mass(stock.totalMass))
                keyValue("Built in", mass(s.builtMass))
                keyValue("Vented", mass(s.ventedMass))
                // ⛔ **`builtMass` and `baselineCargoMass` were missing from this sum, and their
                // absence is what made the indicator useless.** `builtMass`'s own doc says why it
                // has to be here — "without this term the conservation check would read a completed
                // length of track as a leak of exactly its bill of materials" — and that is exactly
                // what this panel did: on a real save it showed 8.8 t of LEAK, of which 7.8 t was
                // simply the ship the player had built. An alarm that is always on is not an alarm,
                // and it is worse than none because it hides the 1.0 t that was real.
                //
                // ⚠️ **The expression is the harness's `massBalance`, to the term.** The suite and
                // the instrument have always used the four-term form; only the panel disagreed, so
                // this is the panel being brought into line rather than a new rule. If they ever
                // diverge again the harness is the one that is right.
                //
                // ⚠️ `reconciledMass` is subtracted, and it is shown on its own line whenever it is
                // non-zero. A write-off that did not appear anywhere would be the panel lying by a
                // measured amount, which is precisely the failure the write-off exists to end.
                val drift = s.inTransitMass + s.ventedMass + s.builtMass -
                    s.extractedMass - s.baselineCargoMass - s.reconciledMass
                if (s.reconciledMass != 0L) {
                    keyValue("Written off", mass(s.reconciledMass), 0x9A9A9AFFL, 0xC8A44AFFL)
                }
                row(
                    if (drift == 0L) "balanced" else "LEAK ${mass(drift)}",
                    if (drift == 0L) 0x6ED09AFFL else 0xE05A4AFFL,
                )
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
                row("(energy ledgers parked  ·  unit rescale step 3)", 0x8A8A8AFFL)
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
                // ⛔ **What you can BUILD with, not what you happen to own.** This used to print
                // the summed heap's dominant species and its purity, which on a real save read
                // "53% WATER" across 187 machines and said nothing about iron, titanium or steel.
                // Summing is what destroys the information: a storage can supply a species only if
                // it holds nothing else, so buildability is a per-tank fact — see [Stockpile].
                title("STOCKPILE")
                row("(pure material aboard)", 0x7A8A9AFFL)
                val held = stock.held
                if (held.isEmpty) {
                    row("(no storage holding anything)", 0x9A9A9AFFL)
                } else {
                    keyValue("TOTAL", mass(held.total))
                    val buildable = stock.buildableSpecies
                    if (buildable.isEmpty()) {
                        // The case worth saying out loud rather than leaving as a blank: a hold full
                        // of ore is not a hold full of building material, and the reason a site is
                        // not being fed is usually this.
                        row("nothing loose is pure enough to build with", 0xC8A44AFFL)
                    } else {
                        row("loose", 0x7A8A9AFFL)
                        for (species in buildable.take(STOCKPILE_LINES)) {
                            keyValue(
                                "  ${species.name}",
                                mass(stock.buildable(species)),
                                0x9A9A9AFFL,
                                speciesColor(species),
                            )
                        }
                        val rest = buildable.size - STOCKPILE_LINES
                        if (rest > 0) row("  and $rest more", 0x7A8A9AFFL)
                    }
                    // ⛔ **Its own list, and never folded into the one above.** Fabric outweighs
                    // anything in a hold, so merged on mass it swamps the panel and ranked below it
                    // it falls off the end — a save with tonnes of titanium in its casings reported
                    // none at all until these were split.
                    val inFabric = stock.fabricSpecies
                    if (inFabric.isNotEmpty()) {
                        row("in fabric · deconstruct to free", 0x7A8A9AFFL)
                        for (species in inFabric.take(STOCKPILE_LINES)) {
                            keyValue(
                                "  ${species.name}",
                                mass(stock.inFabric(species)),
                                0x9A9A9AFFL,
                                speciesColor(species),
                            )
                        }
                        val rest = inFabric.size - STOCKPILE_LINES
                        if (rest > 0) row("  and $rest more", 0x7A8A9AFFL)
                    }
                }
            }

            // ⛔ **What is left of the build menu**, and what is left is the rule the rest of it
            // was drowning: a panel that auto-sizes to its content cannot hold a list whose length
            // the *player* decides. Twenty-five rows of tools, views, brushes and materials took a
            // third of the screen and pushed their own bottom rows off it. The lists are sheets now
            // and the bar names what is chosen — see [bottomBar]. What stays here is the two things
            // that have to be legible while you are clicking: which mode owns the keyboard, and
            // what this particular tool does with a click.
            panel(Anchor.BottomLeft) {
                // Which mode owns the keyboard, and how to change it — said first and loudly,
                // because a player whose WASD has stopped panning needs the answer immediately.
                val flying = controller.mode == Mode.Flight
                button(
                    if (flying) "FLIGHT MODE  ·  F to build" else "BUILD MODE  ·  F to fly",
                    if (flying) 0x8A5A2AFFL else 0x232A38FFL,
                ) { controller.mode = controller.mode.next }
                if (flying) {
                    // The autopilot lives beside the mode toggle because it is the same kind of
                    // thing: a standing instruction about the whole ship, not a machine's setting.
                    button(
                        listOf(
                            "SAS  " to 0x9A9A9AFFL,
                            (if (controller.state.sas) "HOLDING" else "OFF") to
                                (if (controller.state.sas) 0x6EE08AFFL else 0x9A9A9AFFL),
                            "  ·  T" to 0x7A7A7AFFL,
                        ),
                        0x2E5A6BFFL,
                    ) { controller.toggleSas() }
                    val held = InputKey.ALL.filter { InputKey.heldIn(controller.heldKeys, it) }
                    row(
                        if (held.isEmpty()) "WASD moves  ·  QE turns  ·  arrows / Z / X also drive buttons"
                        else "holding: ${held.joinToString(" ") { it.label }}",
                        if (held.isEmpty()) 0x9A9A9AFFL else 0x6EE08AFFL,
                    )
                }
                gap()
                // ⚠️ **The current tool's own instructions, and only the current tool's.** These are
                // what a click is about to do, so they are the one thing here that must never be a
                // tap away — a player mid-drag cannot open a sheet to find out that a click alone
                // joins nothing. Every *choice* went to a sheet; every *consequence* stayed.
                title("${controller.tool.label}  ·  ${toolSubject(controller)}")
                for ((text, color) in toolHints(controller)) row(text, color)
            }

            // ── The bar, and the nav map sitting on it ────────────────────────────────────────
            //
            // ⛔ **Both centre on the space they can actually be seen in, not on the screen**, which
            // is the arrangement `CytoHud.renderBar` describes and it is not cosmetic: the panel
            // above is as wide as the current tool's longest sentence, and a screen-centred bar ran
            // its first button underneath that panel where nothing could be read or clicked. The two
            // must agree on the offset or they read as two instruments on different grids.
            //
            // ⚠️ **Measured this frame rather than guessed**, which is why the order here is panel,
            // then bar, then map: an auto-sized panel's width is not knowable until it is emitted,
            // and a hand-written figure would be the length of a sentence copied into a second place
            // and wrong the day it changed. Nothing else claims [Anchor.BottomCenter], so the bar
            // being late costs it nothing.
            val menuRight = lastPanelRect?.let { it.x + it.w } ?: 0f
            bottomBar(controller, s, stock, offsetX = menuRight / 2f / density)
            val bar = lastPanelRect
            // Sat on the bar rather than on the bottom of the screen: it is a canvas with
            // hand-written coordinates, so the anchor stacking that keeps panels apart cannot do
            // this for it.
            navView(
                s,
                controller.wikiSpecies?.takeIf { it.relativeAbundance > 0 },
                bottom = bar?.y ?: screenH,
                centreX = (menuRight + screenW) / 2f,
            )

            inspectPanel(controller)
            wikiPanel(controller)

            /*
             * ⛔ **SAVE, LOAD, FIT, PAUSE and RESET stood here and are in the MENU sheet now.**
             *
             * They were the corner Stu said reads like cyto's bar, which is the reason this whole
             * change exists — but two bars saying different halves of "the game itself" is one bar
             * too many, and a red RESET permanently in shot beside the storage readouts is a hazard
             * rather than a control. Every one of them has a key, and the sheet says which.
             */

            // ⛔ **Last in the frame.** A sheet is the topmost layer — it dims everything under it
            // and takes every click inside its box — so anything drawn after it would sit on top of
            // a scrim that is supposed to be covering the screen.
            sheets(controller, stock, s)
        }
        // Clear one-shot status messages after they've been displayed.
        saveStatus = ""
        clipboardStatus = ""
    }

    /**
     * Ship nav: origin marker + velocity needle (two scales — distance vs velocity).
     *
     * @param prospecting the species the field is being read for, or null for the full spectrum. It is
     *   whatever the reference is open on, so reading about a mineral is itself the act of going
     *   looking for it — the map answers the question the article raised.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.navView(
        s: VesselState,
        prospecting: Species?,
        bottom: Float,
        centreX: Float,
    ) = canvas {
        RockSpawner.highlight = prospecting
        val size = 190f * density
        val pad = 10f * density
        val x0 = centreX - size / 2f
        // ⚠️ Off the bar's top edge and not off the screen's bottom, so the two never overlap
        // however many chips the bar happens to be showing.
        val y0 = bottom - size - pad
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

        if (prospecting == null) {
            label("NAV  ·  ${NAV_RANGE_TILES.toInt()} tiles", cx, y0 + 3f * density, 9f * density, 0x7A8A9AFFL)
        } else {
            label("NAV  ·  ${prospecting.name.uppercase()}", cx, y0 + 3f * density, 9f * density, speciesColor(prospecting))
        }
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

    /**
     * The inspector: one tile, one layer, and everything that layer has to say.
     *
     * ⛔ **One layer at a time.** A tile can be a building, the track threaded under it, and a room
     * of air all at once, and the old panel printed whichever of those it could reach in one column
     * — track, then buffers, then gas — so the number the player was looking for was always in the
     * middle of numbers they were not. Now the layer is *chosen*: the tabs name every layer with
     * something in it, a click on the tile picks the topmost, and clicking again steps down. See
     * [InspectLayer].
     *
     * ⚠️ **The DECK layer is where a machine's settings live**, all of them — wiring, thermostat,
     * storage lock — beside what the machine is made of and everything it is holding. They used to
     * be three panels in one corner, each standing down for the others, which is how the storage
     * lock came to ship unreachable. A setting is a fact about a building, so it is in the place
     * that describes buildings.
     */
    private fun UiBuilder.inspectPanel(controller: OutofspaceController) {
        val s = controller.state
        val tile = controller.inspectTile
        if (tile == TileIndex.NONE || tile.index < 0 || tile.index >= s.grid.size) return
        val layers = inspectableLayers(s, tile)
        if (layers.isEmpty()) return
        // A pinned layer that has since stopped existing — the belt was deleted, the room was
        // sealed — falls back to the top of the list rather than showing an empty panel.
        val layer = controller.inspectLayer
        val grid = s.grid

        panel(Anchor.TopRight, rowHeight = 20f) {
            title("INSPECT  ·  (${grid.xOf(tile)}, ${grid.yOf(tile)})")
            placeRow(s, tile)
            // The tabs are the cycle made visible. A player who never notices that clicking again
            // steps the layer can still reach every one of them by name, and a player who does can
            // see how many are left.
            actionRow(
                layers.map { option ->
                    Triple(
                        if (option == layer) "> ${option.label}" else option.label,
                        if (option == layer) 0x3A6EA5FFL else 0x232A38FFL,
                    ) { controller.inspect(tile, option) }
                },
            )
            when (layer) {
                InspectLayer.Deck -> deckLayer(controller, tile)
                InspectLayer.Rail -> conduitLayer(controller, tile, Conduit.Rail)
                InspectLayer.Pipe -> conduitLayer(controller, tile, Conduit.Pipe)
                InspectLayer.Wire -> conduitLayer(controller, tile, Conduit.Signal)
                InspectLayer.Power -> conduitLayer(controller, tile, Conduit.Power)
                InspectLayer.Atmosphere -> atmosphereLayer(controller, tile)
            }
        }
    }

    /** Where this tile is, in the one sense every layer shares: inside, outside, hull or machine. */
    private fun PanelBuilder.placeRow(s: VesselState, tile: TileIndex) {
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
    }

    /**
     * The building on this tile: what it is made of, what it is holding, and every dial it has.
     *
     * ⚠️ **The composition is the whole machine's, not this tile's.** A furnace is nine tiles of one
     * object and a ninth of its casing is a fact about the grid rather than about the furnace. Its
     * temperature is likewise the whole casing's — energy over capacity across the footprint — which
     * is the number that decides what it can do, whereas one tile of it is the number that decides
     * nothing.
     */
    private fun PanelBuilder.deckLayer(controller: OutofspaceController, tile: TileIndex) {
        val s = controller.state
        val grid = s.grid
        val machine = s.machineCovering(tile) ?: run { row("(bare deck)", 0x9A9A9AFFL); return }
        val anchor = s.occupancy[tile]
        val parts = machine.tiles(grid)

        title("${machine.kind.label}  ·  (${grid.xOf(anchor)}, ${grid.yOf(anchor)})")

        // Built-ness first, because an unfinished machine explains every other number below it: a
        // ghost is short of its bill, holds the wrong things, and does nothing at all.
        val built = s.deck.builtPermille(machine)
        if (built < 1000) {
            keyValue("BUILT", "${built / 10}%", 0x9A9A9AFFL, 0xE0A93AFFL)
        }
        if (anchor in s.scrapping) row("marked for deconstruction", 0xE05A4AFFL)

        var casing = Mixture.EMPTY
        var energy = 0L
        var capacity = 0L
        for (part in parts) {
            casing += s.deck.stuff.mixtureAt(part)
            energy += s.deck.stuff.energyAt(part)
            capacity += s.deck.stuff.heatCapacityAt(part)
        }
        keyValue("CASING", mass(casing.total), 0x9A9A9AFFL, 0xFFFFFFFFL)
        compositionRows(controller, casing)
        if (capacity > 0L) {
            val k = (energy / capacity).toInt()
            keyValue(
                "TEMP",
                "${k}K  (${k - 273}C)",
                0x9A9A9AFFL,
                if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
        }
        keyValue("TILES", "${parts.size}", 0x9A9A9AFFL, 0x9AA4B4FFL)

        val buffers = contentsBreakdown(machine, anchor, grid, s.buffers)
        section("contents", "CONTENTS", open = true) {
            if (buffers.isEmpty()) {
                row("(holding nothing)", 0x9A9A9AFFL)
            } else {
                for ((label, resource) in buffers) {
                    keyValue(label, mass(resource.total))
                    compositionRows(controller, resource)
                }
            }
        }

        val storage = machine as? Storage
        if (storage != null) {
            section("storage", "FILTER", open = true) { storageControls(controller, tile, storage) }
        }
        val decomposer = machine as? Furnace
        if (decomposer != null) {
            section("furnace", "FURNACE", open = true) { decomposerControls(controller, tile, decomposer) }
        }
        val thruster = machine as? Thruster
        if (thruster != null) {
            section("thruster", "CONTROL", open = true) { thrusterControls(controller, tile, thruster) }
        }
        // Wiring is the one section that starts shut. Every machine has some, most machines never
        // need theirs touched, and it is the longest of the three — so it is the section that would
        // otherwise push the numbers people came for off the bottom of the panel.
        section("wiring", "WIRING", open = false) { wiringControls(controller, anchor, machine) }
    }

    /**
     * A collapsible block of rows: a header button that toggles, and the body when it is open.
     *
     * The whole of the progressive disclosure in this panel. The DECK layer of a furnace is a
     * composition, a chamber, two dials and a wiring editor — everything true about it at once —
     * and the answer to that is not to show less but to let the player shut the parts they are not
     * working on. Which sections are open is a *view* preference of this HUD and lives here rather
     * than in the controller: nothing in the world changes when a section is folded, and a saved
     * game that remembered which headings you had open would be remembering the wrong thing.
     */
    private fun PanelBuilder.section(id: String, label: String, open: Boolean, body: PanelBuilder.() -> Unit) {
        val shut = if (open) id in collapsed else id !in expanded
        gap()
        // ⚠️ No arrow glyph — the bitmap font draws "?" for anything it does not have, and this
        // header would then read "? WIRING". Square brackets and a sign are all known to exist.
        button(if (shut) "[+]  $label" else "[-]  $label", 0x1E2634FFL) {
            if (open) { if (shut) collapsed.remove(id) else collapsed.add(id) }
            else { if (shut) expanded.add(id) else expanded.remove(id) }
        }
        if (!shut) body()
    }

    /** One fitting of one conduit layer: the metal, and whatever it is carrying. */
    private fun PanelBuilder.conduitLayer(controller: OutofspaceController, tile: TileIndex, conduit: Conduit) {
        val s = controller.state
        val segment = s.conduits.at(conduit, tile) ?: run { row("(nothing here)", 0x9A9A9AFFL); return }

        title(conduit.label)
        if (s.conduits.isGhost(conduit, tile)) {
            keyValue("BUILT", "${s.conduits.builtPermille(conduit, tile) / 10}%", 0x9A9A9AFFL, 0xE0A93AFFL)
        }
        if (segment.deconstructing) row("marked for deconstruction", 0xE05A4AFFL)

        val metal = s.conduits.tracks[conduit].mixtureAt(tile)
        keyValue("FITTING", mass(metal.total), 0x9A9A9AFFL, 0xFFFFFFFFL)
        compositionRows(controller, metal)
        val capacity = s.conduits.heatCapacityAt(conduit, tile)
        if (capacity > 0L) {
            val k = (s.conduits.energyAt(conduit, tile) / capacity).toInt()
            keyValue(
                "TEMP",
                "${k}K  (${k - 273}C)",
                0x9A9A9AFFL,
                if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
        }
        keyValue("JOINED", joins(segment), 0x9A9A9AFFL, 0x9AA4B4FFL)

        // What the layer is *for*. Each conduit carries a different kind of thing, and only rail and
        // pipe carry anything at all yet — a wire carries a number and power carries nothing, and
        // saying so is better than a blank space where a cargo readout would be.
        gap()
        when (conduit) {
            Conduit.Rail -> {
                val riding = s.rail.resourceAt(tile)
                if (riding == null) {
                    row("(nothing riding it)", 0x9A9A9AFFL)
                } else {
                    keyValue("CARRYING", mass(riding.total))
                    compositionRows(controller, riding)
                    val k = s.rail.stuff.kelvinAt(tile)
                    keyValue(
                        "LUMP TEMP",
                        "${k}K  (${k - 273}C)",
                        0x9A9A9AFFL,
                        if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
                    )
                }
            }
            Conduit.Pipe -> {
                val inside = s.pipeAir.mixtureAt(tile)
                if (inside.isEmpty) {
                    row("(empty)", 0x9A9A9AFFL)
                } else {
                    keyValue("INSIDE", mass(inside.total))
                    compositionRows(controller, inside, maxEntries = 5)
                    val k = s.pipeAir.kelvinAt(tile)
                    keyValue("FLUID TEMP", "${k}K  (${k - 273}C)", 0x9A9A9AFFL, 0x9AC0E0FFL)
                }
            }
            Conduit.Signal -> {
                val network = s.networks[tile]
                if (network < 0) {
                    row("(not part of a circuit)", 0x9A9A9AFFL)
                } else {
                    val value = s.signals.ofNetwork(network)
                    keyValue("CIRCUIT $network", "${value / 10}%", 0x9A9A9AFFL, if (value > 0) 0x6EE08AFFL else 0x5A5A5AFFL)
                }
            }
            Conduit.Power -> row("carries nothing yet", 0x9A9A9AFFL)
        }
    }

    /** Which way a fitting is joined, as the compass letters the links bitmask means. */
    private fun joins(segment: Segment): String {
        val out = Direction.entries.filter { segment.linkedTo(it) }
        return if (out.isEmpty()) "nothing" else out.joinToString(" ") { it.name.take(1).uppercase() }
    }

    /**
     * The room: pressure, temperature, flow and what the gas is made of.
     *
     * ⚠️ **Offered wherever air *could* be, empty or not.** "0% atm" inside the vessel is the single
     * most useful thing this panel says — a breach reads as a room that has stopped having an
     * atmosphere — so a layer that only appeared once there was gas would vanish exactly when it
     * mattered. Below the trace floor there is genuinely nothing to describe (see [Negligible]): no
     * density worth a percentage, no temperature of a gas that is not there, and no flow, whose
     * speed is a ratio and so can read anything at all on five mass.
     */
    private fun PanelBuilder.atmosphereLayer(controller: OutofspaceController, tile: TileIndex) {
        val s = controller.state
        val density = s.air.densityAt(tile)
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
        if (Negligible.gas(density)) {
            row("   VACUUM", 0x7A8A9AFFL)
            return
        }
        keyValue("DENSITY", "${density * 100 / Stuff.AMBIENT_AIR.total}% atm", 0x9A9A9AFFL, 0x9AA4B4FFL)
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
            keyValue("MASS", mass(mix.total), 0x9A9A9AFFL, 0x9AA4B4FFL)
            compositionRows(controller, mix, maxEntries = 5)
        }
    }

    /**
     * A mixture's percentages, one species per row — and **every row is a way into the reference**.
     *
     * The species names in this panel are the only place the game ever says "periclase" to anybody,
     * so they are where a player who does not know what periclase is will look for the answer. A
     * button rather than a row: the affordance is the whole point, and a clickable line that looked
     * exactly like the twenty unclickable ones around it would be a feature nobody found.
     *
     * The "other" line stays a plain row. It is not a species and there is nothing to open.
     */
    private fun PanelBuilder.compositionRows(
        controller: OutofspaceController,
        mixture: Mixture,
        maxEntries: Int = 3,
    ) {
        if (mixture.isEmpty) return
        val total = mixture.total
        val present = Species.ALL.filter { mixture[it] > 0L }.sortedByDescending { mixture[it] }
        // Only the top two are named once the list would overflow, with the rest summed as "other".
        val named = if (present.size > maxEntries) present.take(2) else present
        var listed = 0L
        for (species in named) {
            val percent = mixture[species] * 100 / total
            listed += percent
            speciesRow(controller, "${(if (percent < 1) "<1" else percent.toString()).padStart(3)}% ${species.name.uppercase()}", species)
        }
        if (present.size > maxEntries) row(" ${(100L - listed).toString().padStart(3)}% other", 0x9AA4B4FFL)
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

    /** One species, drawn as the thing you click to read about it. */
    // ══ The bar, and the sheets it opens ══════════════════════════════════════════════════════

    /**
     * The bar under the nav map: **what is currently chosen, and a way to change it.**
     *
     * ⛔ **Every button names a value, and that is the difference from cyto's bar.** Cyto's says
     * BRUSH and LAYERS, which works because a cyto brush is a thing you set once and forget. This is
     * a placement game: the tool, the shape and the substance all decide what the *next click* does,
     * so a bar of verbs would make a player open three sheets to find out what they were about to
     * build. Naming the value keeps the answer on screen while the lists live behind it — which is
     * the whole trade, and the reason the panel this replaced could be deleted rather than moved.
     *
     * ⚠️ **The build chips appear only under the build tool**, for the same reason the sheets do:
     * a brush and a material decide nothing while you are inspecting, and a bar that shows five
     * buttons whatever you are doing teaches nobody which of them matter.
     */
    private fun UiBuilder.bottomBar(
        controller: OutofspaceController,
        s: VesselState,
        stock: Stockpile,
        offsetX: Float,
    ) {
        panel(
            Anchor.BottomCenter,
            margin = 8f, padding = 6f, background = 0x11182AF2L, rowHeight = 30f, textSize = 13f,
            offsetX = offsetX,
        ) {
            val buttons = buildList<Triple<String, Long, () -> Unit>> {
                add(Triple("TOOL  ·  ${controller.tool.label}", sheetColor(Sheet.Tool, 0x2E5A6BFFL)) {
                    toggleSheet(Sheet.Tool)
                })
                if (controller.tool == Tool.Build) {
                    add(Triple("BUILD  ·  ${controller.brush.label}", sheetColor(Sheet.Brush, 0x3A6EA5FFL)) {
                        toggleSheet(Sheet.Brush)
                    })
                    val chosen = controller.buildMaterial
                    // ⚠️ **Red when nothing is picked**, because that is not a neutral setting — it
                    // is the reason clicking does nothing at all. See `OutofspaceController.buildMaterial`.
                    add(
                        Triple(
                            "MATERIAL  ·  ${chosen?.name?.uppercase() ?: "NONE"}",
                            if (chosen == null) 0xA5453AFFL else sheetColor(Sheet.Material, 0x2E6E5EFFL),
                        ) { toggleSheet(Sheet.Material) },
                    )
                }
                add(Triple("VIEW  ·  ${controller.overlay.label}", sheetColor(Sheet.View, 0x5A4A8AFFL)) {
                    toggleSheet(Sheet.View)
                })
                add(Triple("MENU", sheetColor(Sheet.Menu, 0x2A3550FFL)) { toggleSheet(Sheet.Menu) })
            }
            actionRow(buttons)
        }
    }

    /** A bar button lit while its own sheet is the one open, so the bar says where you are. */
    private fun sheetColor(sheet: Sheet, base: Long): Long = if (openSheet == sheet) 0x8A5A2AFFL else base

    private fun toggleSheet(sheet: Sheet) { openSheet = if (openSheet == sheet) Sheet.None else sheet }

    private fun closeSheet() { openSheet = Sheet.None }

    /** Whichever sheet is open, or nothing. */
    private fun UiBuilder.sheets(controller: OutofspaceController, stock: Stockpile, s: VesselState) {
        // A sheet whose subject has gone shuts itself: switching tools with BUILD open would
        // otherwise leave a brush picker floating over a tool that has no brush.
        if ((openSheet == Sheet.Brush || openSheet == Sheet.Material) && controller.tool != Tool.Build) {
            closeSheet()
        }
        when (openSheet) {
            Sheet.None -> {}
            Sheet.Tool -> toolSheet(controller)
            Sheet.Brush -> brushSheet(controller)
            Sheet.Material -> materialSheet(controller, stock, s.creative)
            Sheet.View -> viewSheet(controller)
            Sheet.Menu -> menuSheet(controller)
        }
    }

    /**
     * The container a sheet lives in, chosen by how much room there is.
     *
     * ⛔ **A bottom sheet on a narrow screen and a centred popover on a wide one**, which is cyto's
     * rule and is not a style choice: a full-width sheet on a desktop monitor is a metre of empty
     * table with six words on it, and a centred box on a phone is a postage stamp you cannot hit.
     * The threshold is the width at which a popover stops being narrower than the screen.
     */
    private fun UiBuilder.sheetHost(
        id: String,
        title: String,
        heightFraction: Float,
        body: org.emerge.render.torus.ui.PanelBuilder.() -> Unit,
    ) {
        if (screenW > NARROW_MAX_DP * density) {
            val w = minOf(SHEET_WIDTH_DP * density, screenW * 0.5f)
            val h = minOf(screenH * 0.85f, screenH * maxOf(heightFraction, 0.35f))
            sheet(
                id, title, onDismiss = ::closeSheet,
                boxX = (screenW - w) * 0.5f, boxY = (screenH - h) * 0.5f, boxW = w, boxH = h,
                rowHeight = 24f, textSize = 13f, body = body,
            )
        } else {
            sheet(id, title, onDismiss = ::closeSheet, heightFraction = heightFraction, rowHeight = 40f, textSize = 15f, body = body)
        }
    }

    /**
     * Every tool, and — underneath — whatever the *current* one has to be pointed at.
     *
     * ⚠️ **The sub-choice belongs with the tool rather than in a sheet of its own.** Which layer
     * DELETE takes off and which conduit CUT severs are not separate concepts a player goes looking
     * for; they are the second half of choosing that tool, and a bar button apiece for them would be
     * two buttons that are dark five sixths of the time.
     */
    private fun UiBuilder.toolSheet(controller: OutofspaceController) {
        sheetHost("oos-tool", "TOOL", heightFraction = 0.7f) {
            for (tool in Tool.entries) {
                listRow(tool.label, toolBlurb(tool), selected = tool == controller.tool) {
                    controller.tool = tool
                    closeSheet()
                }
            }
            when (controller.tool) {
                Tool.Delete -> {
                    gap()
                    row("TAKES OFF  ·  E cycles", 0x7A8699FFL)
                    for (layer in DeleteLayer.entries) {
                        listRow(layer.label, selected = layer == controller.deleteLayer) {
                            controller.deleteLayer = layer
                        }
                    }
                }
                Tool.Cut -> {
                    gap()
                    row("SEVERS  ·  E cycles", 0x7A8699FFL)
                    for (conduit in Tool.CUTTABLE) {
                        listRow(conduit.label, selected = conduit == controller.cutConduit) {
                            controller.cutConduit = conduit
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /** Every shape a click can put down. Rotation stays on `R`: it is a modifier, not a choice. */
    private fun UiBuilder.brushSheet(controller: OutofspaceController) {
        sheetHost("oos-brush", "BUILD", heightFraction = 0.8f) {
            row("facing ${controller.brushFacing.name.uppercase()}  ·  R rotates", 0x7A8699FFL)
            for (option in Brush.ALL) {
                listRow(option.label, selected = option == controller.brush) {
                    controller.brush = option
                    closeSheet()
                }
            }
        }
    }

    /**
     * What you are building out of: the properties that decide it, then every choice available.
     *
     * ⛔ **The properties are above the list and inside the same sheet**, which is a change from the
     * column this replaced, where they had to be a separate rectangle so a long list could not
     * scroll them away. A sheet has one scroll and the facts are four rows at the top of it, so the
     * split stopped buying anything — and a header pinned inside a modal that is itself dismissible
     * would be a second way to lose the thing you are reading.
     *
     * ⚠️ **Picking does not close it.** Choosing a material is the one decision here a player makes
     * by *comparing* — tapping three in turn to read their melting points is the sheet working, not
     * a player failing to find the exit. The brush sheet closes on pick because a shape needs no
     * comparison.
     */
    private fun UiBuilder.materialSheet(controller: OutofspaceController, stock: Stockpile, creative: Boolean) {
        sheetHost("oos-material", "MATERIAL", heightFraction = 0.8f) {
            val chosen = controller.buildMaterial
            if (chosen == null) {
                // ⚠️ Short enough for the narrow form: a row is clipped at the box, and the long
                // version ran off a 520px phone sheet mid-word.
                row("nothing picked  ·  nothing places", 0xE05A4AFFL)
            } else {
                materialFacts(chosen)
                button("READ ABOUT ${chosen.name.uppercase()}", 0x2E5A6BFFL) {
                    controller.openWiki(chosen)
                    closeSheet()
                }
            }
            gap()
            val offer = stock.buildableSpecies
            row("LOOSE ABOARD", 0x7A8699FFL)
            if (offer.isEmpty()) row("nothing the network can deliver", 0xC8A44AFFL)
            for (species in offer) {
                listRow(species.name.uppercase(), mass(stock.buildable(species)), selected = species == chosen) {
                    controller.buildMaterial = species
                }
            }
            // ⚠️ A section of its own, below the hold and never merged into it — the allowance is a
            // property of the mode rather than something aboard. See [Stockpile.CREATIVE_MATERIALS].
            if (creative) {
                val free = Stockpile.CREATIVE_MATERIALS.filter { it !in offer }
                if (free.isNotEmpty()) {
                    gap()
                    row("CREATIVE  ·  ALWAYS AVAILABLE", 0x7A8699FFL)
                    for (species in free) {
                        listRow(species.name.uppercase(), selected = species == chosen) {
                            controller.buildMaterial = species
                        }
                    }
                }
            }
        }
    }

    /** Which field the world is painted by. */
    private fun UiBuilder.viewSheet(controller: OutofspaceController) {
        sheetHost("oos-view", "VIEW", heightFraction = 0.55f) {
            row("H cycles", 0x7A8699FFL)
            for (view in Overlay.entries) {
                listRow(view.label, selected = view == controller.overlay) {
                    controller.overlay = view
                    closeSheet()
                }
            }
        }
    }

    /**
     * The game itself rather than the world: saving it, framing it, stopping it, throwing it away.
     *
     * ⚠️ **The keyboard shortcuts live here too.** They were a block of grey rows at the foot of the
     * build menu, read once and then scrolled past for ever while taking six rows of a panel that
     * had none to spare. A player who has forgotten which key fits the grid is already looking for
     * a menu.
     */
    private fun UiBuilder.menuSheet(controller: OutofspaceController) {
        sheetHost("oos-menu", "MENU", heightFraction = 0.7f) {
            if (canSave) {
                if (saveStatus.isNotEmpty()) row(saveStatus, 0x9AA4B4FFL)
                if (clipboardStatus.isNotEmpty()) row(clipboardStatus, 0x9AA4B4FFL)
                actionRow(
                    listOf(
                        Triple("SAVE  ·  F9", 0x2E5A6BFFL) { onSave() },
                        Triple("LOAD  ·  F10", 0x2E5A6BFFL) { onLoad() },
                    ),
                )
            }
            actionRow(
                listOf(
                    Triple("FIT  ·  F8", 0x2E5A6BFFL) { onFit(); closeSheet() },
                    Triple(if (controller.paused) "PLAY  ·  SPACE" else "PAUSE  ·  SPACE", 0x3A6EA5FFL) {
                        onTogglePause()
                    },
                ),
            )
            gap()
            // ⚠️ Kept short enough to fit the popover: a row is clipped at the box, not wrapped,
            // and "E cycles a tool's target" ran off the edge at [SHEET_WIDTH_DP].
            row("Q tool  ·  WASD or right-drag pan", 0x9A9A9AFFL)
            row("wheel zoom  ·  F build / fly  ·  H view", 0x9A9A9AFFL)
            row("E cycles what a tool is aimed at", 0x9A9A9AFFL)
            row("arrows fly the ship  (debug engine)", 0xC8A44AFFL)
            gap()
            // Last, alone, and the only red thing in here: it throws the vessel away.
            button("RESET", 0xCC3333FFL) { onReset(); closeSheet() }
        }
    }

    /** One line on what a tool is for, shown beside its name in the picker. */
    private fun toolBlurb(tool: Tool): String = when (tool) {
        Tool.Inspect -> "read a tile · click again for the next layer"
        Tool.Build -> "put down conduit and buildings"
        Tool.Delete -> "take a tile apart, one layer at a time"
        Tool.Cancel -> "call off a deconstruction"
        Tool.Cut -> "sever joins without taking anything up"
        Tool.Inject -> "debug · gas from nowhere"
        Tool.InjectWater -> "debug · water from nowhere"
    }

    /** What the current tool is currently pointed at — the second half of its own name. */
    private fun toolSubject(controller: OutofspaceController): String = when (controller.tool) {
        Tool.Build -> "${controller.brush.label} facing ${controller.brushFacing.name.uppercase()}"
        Tool.Delete -> controller.deleteLayer.label
        Tool.Cut -> controller.cutConduit.label
        Tool.Inspect -> controller.inspectLayer.label
        Tool.Inject -> "${Edit.INJECT_MASS}G / TICK"
        Tool.InjectWater -> "${Edit.WATER_INJECT_MASS}G / TICK"
        Tool.Cancel -> "click a marked tile"
    }

    /** What a click with the current tool actually does. Stays on screen; see the build panel. */
    private fun toolHints(controller: OutofspaceController): List<Pair<String, Long>> = when (controller.tool) {
        Tool.Build -> buildList {
            add("click or drag to place" to 0x9A9A9AFFL)
            if (controller.brush is Brush.Run) {
                add("DRAG to connect · a click alone joins nothing" to 0xE8B84AFFL)
            }
            if (controller.buildMaterial == null) add("no material picked · nothing places" to 0xE05A4AFFL)
        }
        Tool.Delete -> listOf(
            "click or drag to remove · E cycles layer" to 0x9A9A9AFFL,
            "TOP takes one layer at a time" to 0x9A9A9AFFL,
        )
        Tool.Cut -> listOf(
            "drag ALONG a run to sever · E cycles conduit" to 0x9A9A9AFFL,
            "cuts the joins you draw · other joins stay" to 0xE8B84AFFL,
        )
        Tool.Inject -> listOf(
            "hold over a permeable tile" to 0x9A9A9AFFL,
            "debug tool · gas from nowhere, booked as INJECTED" to 0xC8A44AFFL,
        )
        Tool.InjectWater -> listOf(
            "hold over a permeable tile · ~1s fills a tile" to 0x9A9A9AFFL,
            "debug tool · water from nowhere, booked as INJECTED" to 0xC8A44AFFL,
            "arrives at ${Edit.WATER_INJECT_KELVIN}K · room temperature" to 0x9A9A9AFFL,
        )
        Tool.Inspect -> listOf(
            "click a tile to read it · click again for the next layer" to 0x9A9A9AFFL,
            "machine settings live on the DECK layer" to 0x9A9A9AFFL,
        )
        Tool.Cancel -> listOf("click a tile marked for deconstruction" to 0x9A9A9AFFL)
    }

    /*
     * ⛔ **`materialColumn` stood here and the MATERIAL sheet replaced it.**
     *
     * It was two rectangles beside the build panel — pinned properties above a scrolling list —
     * which was the right shape while the picker had to live *next to* a menu that was already
     * eating a third of the screen. A sheet has the room the column was working around: one scroll,
     * the facts at the top of it, and nothing competing for the width. What the column taught and
     * the sheet keeps is that the properties come before the list, because they are what the list
     * is read against.
     */

    /**
     * The numbers that decide what to build a thing out of.
     *
     * ⛔ **Four properties, and each of them is a reason to choose differently.** Density is what a
     * hull *weighs*, and rock is 0.42x steel, so a stone ship accelerates better. Melting point is
     * what survives a furnace next door — a structure past it is told to come apart. Conductivity
     * decides whether a run of track is a heat leak between two rooms or an insulator. Specific heat
     * is how much a wall buffers before it changes temperature at all.
     *
     * ⚠️ **Stated in real units and not in game ones**, deliberately: every one of these is a number
     * a player can look up, and printing millidegrees or Budget units would make a table anyone can
     * check into a table only this codebase can. Strength lands here when it exists.
     */
    private fun PanelBuilder.materialFacts(species: Species) {
        keyValue("DENSITY", "${species.solidKgPerCubicMetre} kg/m3", 0x9A9A9AFFL, 0x9AA4B4FFL)
        keyValue("MELTS AT", "${species.meltingKelvin} K", 0x9A9A9AFFL, meltingColor(species))
        keyValue("CONDUCTS", wattsPerMetreKelvin(species), 0x9A9A9AFFL, 0x9AA4B4FFL)
        keyValue("HOLDS HEAT", "${species.specificHeat} J/kg/K", 0x9A9A9AFFL, 0x9AA4B4FFL)
    }

    /**
     * A melting point read as a warning where it is one.
     *
     * ⚠️ **Against water's boiling point and the ambient, not against a tuned threshold.** Anything
     * that gives up below the temperature of boiling water is a material a fire in the next room
     * will destroy, and that is worth colouring; the rest is a number.
     */
    private fun meltingColor(species: Species): Long = when {
        species.meltingKelvin < 373 -> 0xE05A4AFFL
        species.meltingKelvin < 1_000 -> 0xE0A93AFFL
        else -> 0x9AA4B4FFL
    }

    /**
     * Milliwatts per metre per kelvin, printed as watts with one decimal.
     *
     * ⚠️ **One decimal because the insulators are the interesting end.** Firebrick is 2.5 W/m/K and
     * forsterite 5.0; rounded to whole watts they and every ice and salt in the table would read as
     * the same number, which is exactly the distinction the column exists to show. See
     * [Species.milliWattsPerMetreKelvin], which is stated in milliwatts for the same reason.
     */
    private fun wattsPerMetreKelvin(species: Species): String {
        val milli = species.milliWattsPerMetreKelvin
        return "${milli / 1_000}.${(milli % 1_000) / 100} W/m/K"
    }

    private fun PanelBuilder.speciesRow(controller: OutofspaceController, label: String, species: Species) =
        button(label, 0x1E2634FFL) { controller.openWiki(species) }

    /**
     * The reference: one species, what it is made of, and every reaction it is either end of.
     *
     * ⚠️ **It is a panel, not a mode.** It sits under the inspector in the same column and the
     * inspector keeps working while it is open, because the two are read together: a player looking
     * at a hopper of ilmenite wants to know what ilmenite *is* without losing sight of the hopper.
     * Anything modal here would make the game a thing you stop playing in order to read about.
     *
     * ⚠️ **Every species named anywhere in it is itself a way in.** That is the whole of the
     * navigation — an article on ilmenite reaches rutile because rutile is named in it, so there is
     * no index to write and no species that can become unreachable by being forgotten. The history
     * behind [OutofspaceController.wikiBack] exists precisely because that kind of reading wanders.
     *
     * ⛔ **A scroll area rather than a panel, and it has to be.** A panel auto-sizes to its content
     * and an article is as long as the chemistry makes it — carbon takes part in three reactions and
     * comes out of a fourth, which is a column taller than the screen, so the reactions nearest the
     * bottom (the ones a player is hunting for) were the ones that fell off it. Here the article
     * takes the room left under the inspector and scrolls inside it, so length is never a reason for
     * something to be unreachable.
     *
     * ⚠️ **Positioned off [lastPanelRect]**, which is the inspector when one is open and the
     * stockpile when none is — either way, the bottom of the right-hand column as it actually came
     * out this frame. A scroll area cannot join the anchored stacking that panels use (it is given
     * a rectangle, not a corner), and a hand-written offset would be the height of the inspector
     * copied into a second place and wrong the moment a row was added to it.
     */
    private fun UiBuilder.wikiPanel(controller: OutofspaceController) {
        val species = controller.wikiSpecies ?: return

        // ── The head: pinned, and everything in it is a reason to pin it ──────────────────────
        //
        // ⚠️ **The way out must not scroll away.** With the title and CLOSE inside the scroll area,
        // reading to the bottom of carbon left a player looking at a list with no name on it and no
        // button to shut it. What is up here is what stays true however far down the article you
        // are: which species this is, how to leave, and the three numbers that are one line each.
        // It is a normal panel, so it takes its place in the right-hand column like any other —
        // held to the body's width, so the two read as one thing rather than a tab above a box.
        panel(Anchor.TopRight, rowHeight = 20f, minWidth = MIN_REFERENCE_WIDTH_DP) {
            title(species.name.uppercase())
            actionRow(
                listOf(
                    Triple("<", if (controller.canWikiBack) 0x2E5A6BFFL else 0x1A1F28FFL) { controller.wikiBack() },
                    Triple(">", if (controller.canWikiForward) 0x2E5A6BFFL else 0x1A1F28FFL) { controller.wikiForward() },
                    Triple("CLOSE", 0x6B3A3AFFL) { controller.closeWiki() },
                ),
            )
            keyValue("KIND", if (species.isElement) "ELEMENT" else "COMPOUND", 0x9A9A9AFFL, speciesColor(species))
            keyValue("MOLAR MASS", "${species.molarMass} g/mol", 0x9A9A9AFFL, 0x9AA4B4FFL)
            // ⚠️ **The same four the picker shows, and they have to be here too.** The picker's
            // panel is the short form and this is the long one, so a player who clicks "read about
            // this" and finds fewer numbers than they came from has been sent the wrong way. Stated
            // once, in [materialFacts], so the two can never disagree.
            materialFacts(species)
            // Said only where it is true. "Solid only" on a hundred and forty-five rocks is a line
            // that teaches nobody anything; "can be a gas" on the twenty that can is the fact.
            if (species.fluid != null) row("can be a gas", 0x9AC0E0FFL)
        }

        // ── The body: as long as the chemistry makes it ───────────────────────────────────────
        val head = lastPanelRect ?: return
        val margin = 12f * density
        // Aligned to the head, and never narrower than a reaction's chips: the head is five short
        // rows and would otherwise set a width the sections cannot live in.
        val width = maxOf(head.w, MIN_REFERENCE_WIDTH_DP * density)
        val x = head.x + head.w - width
        val top = head.y + head.h
        val height = screenH - top - margin
        // Nothing left to show it in — a very short window, or an inspector reading a busy machine.
        // Better no body than a scroll area of negative height, which draws as a sliver of nothing.
        if (height < MIN_REFERENCE_HEIGHT_DP * density) return
        scrollArea("wiki", x, top, width, height, rowHeight = 20f, background = 0x000000C0L) {
            val parts = compositionOf(species)
            if (parts.isNotEmpty()) {
                section("wiki-made-of", "MADE OF", open = true) {
                    for (part in parts) {
                        val percent = part.partsPerThousand / 10
                        speciesRow(
                            controller,
                            "${percent.toString().padStart(3)}% ${part.element.name.uppercase()}  x${part.atoms}",
                            part.element,
                        )
                    }
                }
            }

            abundanceSection(species)

            reactionSection(controller, "wiki-uses", "TAKES PART IN", reactionsConsuming(species))
            reactionSection(controller, "wiki-from", "MADE BY", reactionsProducing(species))
        }
    }

    /**
     * Whether a rock can simply contain this, and how much of one it is.
     *
     * ⚠️ **The "no" is a section too**, and it is the more useful one. Aluminium is commoner than
     * gold and never occurs loose, so an empty abundance row on it would read as missing data when
     * what it means is "you cannot mine this, you have to make it" — and the routes to making it are
     * the section directly below. Ninety per cent of the table is in that position.
     *
     * ⚠️ **A rank as well as a figure.** "49 parts per hundred million" is unreadable without the
     * rest of the table beside it, and the rest of the table is not something a panel can show; the
     * rank is what says whether that number is ordinary or remarkable.
     */
    private fun PanelBuilder.abundanceSection(species: Species) {
        section("wiki-abundance", "FOUND IN ROCK", open = true) {
            if (!species.occursNaturally) {
                row("never loose in rock", 0xE0A93AFFL)
                row("it has to be made", 0x9A9A9AFFL)
                return@section
            }
            keyValue("SHARE", abundanceOf(species), 0x9A9A9AFFL, 0x9ED0B0FFL)
            keyValue(
                "RANK",
                "${abundanceRank(species)} of ${Species.NATURAL.size}",
                0x9A9A9AFFL,
                0x9AA4B4FFL,
            )
            row("by mass, of a reference rock", 0x7A7A7AFFL)
        }
    }

    /**
     * One list of reactions, each as three lines: what it takes, what it takes it at, what it gives.
     *
     * ⛔ **Three lines and not one equation.** `1 ILMENITE + 1 CARBON > 1 IRON + 1 RUTILE +
     * 1 CARBONMONOXIDE` is sixty characters on one row, and a panel auto-sizes to its widest row —
     * so the equation form would set the width of the whole right-hand column and push the
     * inspector's numbers off the screen.
     *
     * ⛔ **And not one line per species either**, which is what this was first: six rows a reaction
     * runs the panel off the bottom edge for anything as busy as carbon, where the reactions the
     * player most wants are the ones that overflow. The chips carry their formula units, so nothing
     * was lost by putting each side on a single line.
     */
    private fun PanelBuilder.reactionSection(
        controller: OutofspaceController,
        id: String,
        label: String,
        reactions: List<ReactionInfo>,
    ) {
        if (reactions.isEmpty()) return
        section(id, "$label (${reactions.size})", open = true) {
            for (reaction in reactions) {
                // What the player has to arrange, how hot, and which way the heat goes: the facts
                // that decide whether this reaction is a plan or a curiosity.
                keyValue(
                    reaction.kind.label,
                    "${reaction.onsetKelvin}K · " + if (reaction.isEndothermic) "TAKES HEAT" else "GIVES HEAT",
                    0x9A9A9AFFL,
                    0xE0864AFFL,
                )
                speciesChips(controller, "IN", reaction.inputs)
                speciesChips(controller, "OUT", reaction.products)
                gap()
            }
        }
    }

    /** One side of a reaction: an inert label, then a chip per species that opens its article. */
    private fun PanelBuilder.speciesChips(
        controller: OutofspaceController,
        side: String,
        entries: List<Pair<Species, Int>>,
    ) = actionRow(
        listOf(Triple(side, 0x00000000L) { }) +
            entries.map { (species, units) ->
                Triple("$units ${species.name.uppercase()}", 0x1E2634FFL) { controller.openWiki(species) }
            },
    )

    /**
     * The lock on a warehouse: what it is holding, and the threshold to hold it to.
     *
     * ⛔ **No species list.** The button locks the tank onto whatever it is already full of — see
     * [org.emerge.demo.outofspace.Edit.LockStorage]. Offering the player a menu of every material
     * in the game would ask them to name things they have never seen, and would let them lock a
     * warehouse onto something that has never come aboard.
     */
    private fun PanelBuilder.storageControls(controller: OutofspaceController, tile: TileIndex, storage: Storage) {
        val grid = controller.state.grid
        val store = bufferTile(grid, storage, storage.center, BufferRole.Inside)
        val held = store?.let { controller.state.buffers.resourceAt(it) }
        val filter = storage.filter

        if (filter == null) {
            val dominant = held?.dominant
            if (dominant == null) {
                keyValue("TAKES", "ANYTHING", 0x9A9A9AFFL, 0x9ED0B0FFL)
                button("LOCK PURITY TO ${SpeciesFilter.MAX_PERCENT}%", 0x2E5A6BFFL) {
                    controller.lockStoragePercent(tile, SpeciesFilter.MAX_PERCENT)
                }
            } else {
                keyValue("TAKES", "ANYTHING", 0x9A9A9AFFL, 0x9ED0B0FFL)
                button(
                    listOf(
                        "LOCK TO " to null,
                        dominant.name.uppercase() to speciesColor(dominant),
                    ),
                    0x2E5A6BFFL,
                ) {
                    val currentPercent = (held[dominant] * 100L) / held.total
                    controller.lockStoragePercent(tile, SpeciesFilter.PERCENTS.reversed().firstOrNull {
                        (it ?: 0) <= currentPercent }
                    )
                    controller.lockStorageSpecies(tile, dominant)
                }
            }
        } else {
            button(
                listOf(
                    "LOCKED TO " to null,
                    (filter.species?.name?.uppercase() ?: "PURITY") to filter.species?.let { speciesColor(it) },
                ),
                0x2E5A6BFFL,
            ) { controller.toggleStorageFilterSpecies(tile) }
            if (filter.minPercent == null) {
                button("ANY PURITY", 0x2E5A6BFFL) {
                    controller.cycleStorageFilterPercent(tile, 1)
                }
            } else {
                clauseRow(
                    lhs = "AT LEAST",
                    cmp = "${filter.minPercent}%",
                    rhs = "pure",
                    onLhs = { controller.cycleStorageFilterPercent(tile, -1) },
                    onCmp = { controller.cycleStorageFilterPercent(tile, -1) },
                    onRhs = { controller.cycleStorageFilterPercent(tile, 1) },
                )
            }
        }
    }

    /**
     * The two dials on a furnace: how hot, and how long.
     *
     * ⚠️ **Two dials because conversion is asymptotic.** There is no moment at which a charge is
     * finished — a reaction approaches completion and never arrives — so the machine cannot decide
     * when to let go and the player says instead. Hotter converts faster but spends more element and
     * leaks more heat into the room; longer converts more of each charge but throttles throughput.
     *
     * ⚠️ **"TICKS" is provisional.** This is the first duration the game shows anybody, and what a
     * tick should be called in front of a player is not decided — see [Furnace.DWELLS].
     */
    private fun PanelBuilder.decomposerControls(
        controller: OutofspaceController,
        tile: TileIndex,
        machine: Furnace,
    ) {
        val grid = controller.state.grid
        val chamber = bufferTile(grid, machine, machine.center, BufferRole.Inside)
        val chargeKelvin = chamber?.let { controller.state.buffers.stuff.kelvinAt(it) } ?: 0

        // ⛔ **Not `clauseRow`**, though the storage lock next door uses one. That control is a
        // *clause* editor — "AT LEAST | 70% | pure" — and its middle cell is a fixed three
        // characters wide, sized for a comparison operator. "2400 K" and "5000 TICKS" do not fit in
        // it, and the way they do not fit is to be silently clipped: the panel renders, reads almost
        // right, and shows the player "NO HOLI".
        button(
            listOf("HOLD AT  " to 0x9A9A9AFFL, "${machine.setTemperature} K" to 0xFFFFFFFFL),
            0x2E5A6BFFL,
        ) { controller.cycleDecomposerTemperature(tile, 1) }
        button(
            listOf(
                "FOR  " to 0x9A9A9AFFL,
                (if (machine.dwellTicks == 0) "NO HOLD" else "${machine.dwellTicks} TICKS") to 0xFFFFFFFFL,
            ),
            0x2E5A6BFFL,
        ) { controller.cycleDecomposerDwell(tile, 1) }

        if (chamber != null && controller.state.buffers.resourceAt(chamber) != null) {
            // Coloured by whether the charge is *there yet* rather than by how hot it is: below the
            // setpoint the element is still working and the dwell has not started counting.
            keyValue(
                "CHARGE",
                "$chargeKelvin K  (${chargeKelvin - 273}C)",
                0x9A9A9AFFL,
                if (chargeKelvin >= machine.setTemperature) 0xE0864AFFL else 0x9AC0E0FFL,
            )
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

    /**
     * The one control on a motor: who it takes orders from.
     *
     * ⚠️ **This panel is the only place the mode is visible.** A thruster looks identical in either,
     * and the difference — whether the pilot's stick reaches it — is not something the tile can
     * show. So the section opens by default, unlike wiring, and it names the keys: a player who has
     * just built their first engine should not have to be told elsewhere that W flies it.
     */
    private fun PanelBuilder.thrusterControls(
        controller: OutofspaceController,
        tile: TileIndex,
        machine: Thruster,
    ) {
        button(
            listOf("LISTENS TO  " to 0x9A9A9AFFL, machine.control.label to 0xFFFFFFFFL),
            0x2E5A6BFFL,
        ) { controller.toggleThrusterControl(tile) }
        when (machine.control) {
            ThrusterControl.Flight -> {
                keyValue("PUSHES", machine.thrust.name.uppercase(), 0x9A9A9AFFL, 0x9ED0B0FFL)
                row("WSAD moves  ·  QE turns  ·  press F to fly", 0x9A9A9AFFL)
                // What the motor makes of the stick right now, which is the readout that turns a
                // badly placed engine from a mystery into something a player can see is idle.
                // Read off the machine and never recomputed here — see [Thruster.firing]. On a
                // ship whose engines were throttled against each other to keep a burn straight, the
                // single-motor answer is the wrong number and it is wrong in the flattering
                // direction.
                keyValue("FIRING", "${machine.firing.coerceAtLeast(0) / 10}%", 0x9A9A9AFFL, 0xE0A93AFFL)
            }
            ThrusterControl.Wire -> row("driven by its WIRING, below", 0x9A9A9AFFL)
        }
    }

    /** Wiring editor: WHEN/PLUS terms (tap channel/weight to cycle, x to delete). */
    private fun PanelBuilder.wiringControls(controller: OutofspaceController, tile: TileIndex, machine: DeckMachine) {
        val grid = controller.state.grid

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
            row("held in FLIGHT mode  ·  press F to switch", 0x9A9A9AFFL)
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
            row("watching: ${target?.kind?.label ?: "(nothing)"}", 0x9A9A9AFFL)
        }

        if (machine is Gauge) {
            keyValue(
                "REPORTS",
                if (wired) "${machine.lastPurity / 10}% on circuit ${controller.state.networks[tile]}"
                else "(no wire under it)",
                0x9A9A9AFFL,
                if (wired) 0x6EE08AFFL else 0xE0A93AFFL,
            )
            if (machine.lastDominant == null) {
                row("nothing has passed through yet", 0x9A9A9AFFL)
            } else {
                keyValue(
                    "LAST SAW",
                    "${machine.lastPurity / 10}% ${machine.lastDominant.name.uppercase()} of ${mass(machine.lastMass)}",
                    0x9A9A9AFFL,
                    speciesColor(machine.lastDominant),
                )
            }
        }

        val action = Action.Run
        val triggers = machine.wiring.triggers(action)
        val activation = machine.wiring.activation(action, controller.state.signals.at(tile))
        keyValue(action.label, "${activation / 10}%", 0x9A9A9AFFL, if (activation > 0) 0x6ED09AFFL else 0xE05A4AFFL)

        if (triggers.isEmpty()) {
            row("(never runs  ·  no terms)", 0xE05A4AFFL)
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
        row(if (wired) "WIRE reads circuit ${controller.state.networks[tile]}" else "WIRE reads 0  ·  no wire under this tile", 0x7A7A7AFFL)
    }

    /*
     * ⛔ **`controlRowOfTools` stood here — seven buttons in a row, all lit at once.** The TOOL sheet
     * is the same choice with room to say what each of them is *for*, which a row of seven labels
     * never had. It went unused the moment the row did.
     */

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
        // ⚠️ **Sign split off first, exactly as [tiles] does two functions down.** Written without
        // it, `g < 10_000` is true of every negative number however large, so a tonne of deficit
        // printed as "-1010624g" while a tonne of surplus printed as "1.0t"; and the kg and t
        // branches would have made nonsense of a negative remainder if they had ever been reached.
        // Invisible until the mass-balance row started showing a signed drift instead of the word
        // LEAK, which is a fair argument for having made it show one.
        val sign = if (v < 0L) "-" else ""
        val g = (if (v < 0L) -v else v) / Budget.GRAM
        return when {
            g < 10_000L -> "$sign${g}g"
            g < 10_000_000L -> "$sign${g / 1000}.${(g % 1000) / 100}kg"
            else -> "$sign${g / 1_000_000}.${(g % 1_000_000) / 100_000}t"
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

        /** The narrowest the reference is allowed to be, in dp — the widest reaction row, three chips. */
        /**
         * How wide the material column is.
         *
         * Narrower than [MIN_REFERENCE_WIDTH_DP] because nothing in it is a sentence: the widest
         * row is a species name and a mass, and a column sized for prose would take a quarter of the
         * screen to show a list of single words.
         */
        /**
         * Below this width a sheet is a full-width panel off the bottom edge; above it, a centred
         * popover — see `OutofspaceHud.sheetHost`. It is the width at which a popover stops being
         * meaningfully narrower than the screen it floats on.
         */
        const val NARROW_MAX_DP: Float = 900f

        /** How wide a popover sheet is on a screen with room for one. */
        const val SHEET_WIDTH_DP: Float = 520f

        const val MATERIAL_COLUMN_WIDTH_DP: Float = 300f

        const val MIN_REFERENCE_WIDTH_DP: Float = 460f

        /** Below this much room under the head, in dp, the article's body is not drawn at all. */
        const val MIN_REFERENCE_HEIGHT_DP: Float = 80f
    }
}
