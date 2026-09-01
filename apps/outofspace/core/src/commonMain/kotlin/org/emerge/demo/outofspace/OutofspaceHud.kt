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
import org.emerge.render.torus.ui.ActionButton
import kotlin.math.absoluteValue

/** A full-screen overlay: the game's own controls, or the sim's readouts. One at a time. */
enum class Sheet { None, Menu, Readouts, SaveLoad }

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

    // ── Save/load dialog state ────────────────────────────────────────────────────────────────
    /** Whether the name input field is capturing keyboard characters. */
    var capturingName: Boolean = false
        private set

    /** Whether we're in save mode (currently saving, not loading). */
    var saveMode: Boolean = true
        private set

    /** Buffer for the name being typed in the save/load dialog. */
    private val nameBuffer = StringBuilder()

    /** The list of available save names. */
    private var availableSaves: List<String> = emptyList()

    /** Pending save/load/delete callbacks (host-provided). */
    private var pendingOnSave: ((String) -> Unit)? = null
    private var pendingOnLoad: ((String) -> Unit)? = null
    private var pendingOnDelete: ((String) -> Unit)? = null

    /** Whether a delete confirmation is pending for this save name. */
    private var pendingDelete: String? = null

    /** Open the save/load dialog. */
    fun openSaveLoadDialog(
        onSave: (String) -> Unit,
        onLoad: (String) -> Unit,
        onDelete: (String) -> Unit,
        saves: List<String>,
        saveMode: Boolean,
        defaultName: String,
    ) {
        this.pendingOnSave = onSave
        this.pendingOnLoad = onLoad
        this.pendingOnDelete = onDelete
        this.availableSaves = saves
        this.saveMode = saveMode
        nameBuffer.setLength(0)
        nameBuffer.append(defaultName)
        capturingName = true
        pendingDelete = null
        openSheet = Sheet.SaveLoad
    }

    /** Dismiss the save/load dialog. */
    fun closeSaveLoadDialog() {
        capturingName = false
        pendingOnSave = null
        pendingOnLoad = null
        pendingOnDelete = null
        pendingDelete = null
        if (openSheet == Sheet.SaveLoad) openSheet = Sheet.None
    }

    /** Handle a typed character in the name field. */
    fun typeChar(c: Char) {
        if (capturingName && nameBuffer.length < 40 && c >= ' ') nameBuffer.append(c)
    }

    /** Handle backspace in the name field. */
    fun backspace() {
        if (capturingName && nameBuffer.isNotEmpty()) nameBuffer.setLength(nameBuffer.length - 1)
    }

    /** The current name being typed. */
    fun currentName(): String = nameBuffer.toString()

    /** Commit the current name as a save (called from the key callback). */
    fun commitSave() {
        val name = currentName().trim()
        if (name.isNotBlank()) {
            pendingOnSave?.invoke(name)
        }
        closeSaveLoadDialog()
    }

    /** Commit the current name as a load (called from the key callback). */
    fun commitLoad() {
        val name = currentName().trim()
        if (name.isNotBlank()) {
            pendingOnLoad?.invoke(name)
        }
        closeSaveLoadDialog()
    }

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
     * Which sheet is showing, if any.
     *
     * ⛔ **View state, so it lives on the HUD and not on the controller.** Nothing in the sim or in a
     * save has an opinion about whether a menu is open, and putting it on the controller would make
     * "the player is looking at the save button" a fact about the vessel. Same argument the collapse
     * sets below are held here by.
     *
     * ⚠️ **An enum rather than a flag apiece**, now there are two: a sheet dims the world behind it,
     * so two open at once is two scrims and a player who cannot tell which of them a click reaches.
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
            // Drawn first (occludes everything).
            navView(s, controller.wikiSpecies?.takeIf { it.relativeAbundance > 0 })
            /*
             * ⛔ **The readouts panel stood here and is behind MENU > READOUTS now.**
             *
             * Ledgers, drift lamps, circuit percentages and a tick counter: instruments for finding
             * out whether the sim is lying, and every one of them permanently in the top-left corner
             * of a game about looking at a ship. Stu does not want to see them in normal play, and
             * they lose nothing by being a click away — a leak is not a thing you catch by noticing
             * it in your peripheral vision, it is a thing you go and check.
             */

            sessionPanel(controller, fps)

            panel(Anchor.TopRight) {
                // ⛔ **What you can BUILD with, not what you happen to own.** This used to print
                // the summed heap's dominant species and its purity, which on a real save read
                // "53% WATER" across 187 machines and said nothing about iron, titanium or steel.
                // Summing is what destroys the information: a storage can supply a species only if
                // it holds nothing else, so buildability is a per-tank fact — see [Stockpile].
                title("STOCKPILE")
                text("(pure material aboard)", 0x7A8A9AFFL)
                val held = stock.held
                if (held.isEmpty) {
                    text("(no storage holding anything)", 0x9A9A9AFFL)
                } else {
                    keyValue("TOTAL", mass(held.total))
                    val buildable = stock.buildableSpecies
                    if (buildable.isEmpty()) {
                        // The case worth saying out loud rather than leaving as a blank: a hold full
                        // of ore is not a hold full of building material, and the reason a site is
                        // not being fed is usually this.
                        text("nothing loose is pure enough to build with", 0xC8A44AFFL)
                    } else {
                        text("loose", 0x7A8A9AFFL)
                        for (species in buildable.take(STOCKPILE_LINES)) {
                            keyValue(
                                "  ${species.name}",
                                mass(stock.buildable(species)),
                                0x9A9A9AFFL,
                                speciesColor(species),
                            )
                        }
                        val rest = buildable.size - STOCKPILE_LINES
                        if (rest > 0) text("  and $rest more", 0x7A8A9AFFL)
                    }
                    // ⛔ **Its own list, and never folded into the one above.** Fabric outweighs
                    // anything in a hold, so merged on mass it swamps the panel and ranked below it
                    // it falls off the end — a save with tonnes of titanium in its casings reported
                    // none at all until these were split.
                    val inFabric = stock.fabricSpecies
                    if (inFabric.isNotEmpty()) {
                        text("built-in · deconstruct to free", 0x7A8A9AFFL)
                        for (species in inFabric.take(STOCKPILE_LINES)) {
                            keyValue(
                                "  ${species.name}",
                                mass(stock.inFabric(species)),
                                0x9A9A9AFFL,
                                speciesColor(species),
                            )
                        }
                        val rest = inFabric.size - STOCKPILE_LINES
                        if (rest > 0) text("  and $rest more", 0x7A8A9AFFL)
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
                    for (option in Brush.ALL) {
                        val selected = option == controller.brush
                        button(
                            if (selected) "> ${option.label}" else "  ${option.label}",
                            if (selected) brushColor(option) or 0xFFL else 0x232A38FFL,
                        ) { controller.brush = option }
                    }
                    gap()
                    text("click or drag to place", 0x9A9A9AFFL)
                    // Track: drag to connect (not by touching).
                    if (controller.brush is Brush.Run) {
                        text("DRAG to connect · a click alone joins nothing", 0xE8B84AFFL)
                    }
                    text("R rotate brush", 0x9A9A9AFFL)

                    // ── What it is to be made of ──────────────────────────────
                    //
                    // ⛔ **Offers what the network can deliver, and nothing else.** Every entry here
                    // is a material a site built from it will actually finish on: `buildable` counts
                    // tanks, buffers, belts, and anything already marked for deconstruction, which
                    // is what makes "mark that furnace, then build in titanium" a thing the picker
                    // can honestly offer. Fabric nobody has ordered taken apart is deliberately
                    // absent — it would be promising a build that cannot start.
                    //
                    // ⚠️ **Sticky, and it says so.** A material is a decision about a batch of
                    // building rather than about one click, so it survives changing brush, changing
                    // facing, and switching between clicking and dragging.
                    gap()
                    // ⛔ **The picker itself is a column of its own now** — see [materialColumn].
                    // What stays here is the one thing the build panel has to say: which substance
                    // the next click will use. The list outgrew a panel that auto-sizes to its
                    // content the moment the answer stopped being four entries long, and a build
                    // menu that pushes the tool buttons off the bottom of the screen is worse than
                    // one that names its material and points sideways.
                    val chosen = controller.buildMaterial
                    title("MATERIAL  ·  ${chosen?.name?.uppercase() ?: "NONE PICKED"}")
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
                    text("click or drag to remove · E cycles layer", 0x9A9A9AFFL)
                    text("TOP takes one layer at a time", 0x9A9A9AFFL)
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
                    text("drag ALONG a run to sever · E cycles conduit", 0x9A9A9AFFL)
                    text("cuts the joins you draw · other joins stay", 0xE8B84AFFL)
                } else if (controller.tool == Tool.Inject) {
                    title("INJECT  ·  ${Edit.INJECT_MASS}G / TICK")
                    text("hold over a permeable tile", 0x9A9A9AFFL)
                    // Named as debug in the same yellow the engine row uses, because it is the same
                    // kind of lie: it makes matter, and says so in the atmosphere panel.
                    text("debug tool · gas from nowhere, booked as INJECTED", 0xC8A44AFFL)
                } else if (controller.tool == Tool.InjectWater) {
                    title("WATER  ·  ${Edit.WATER_INJECT_MASS}G / TICK")
                    text("hold over a permeable tile · ~1s fills a tile", 0x9A9A9AFFL)
                    text("debug tool · water from nowhere, booked as INJECTED", 0xC8A44AFFL)
                    text("arrives at ${Edit.WATER_INJECT_KELVIN}K  ·  room temperature", 0x9A9A9AFFL)
                } else {
                    title("INSPECT  ·  ${controller.inspectLayer.label}")
                    text("click a tile to read it  ·  click again for the next layer", 0x9A9A9AFFL)
                }
                text("Q tool · WASD or right-drag pan · wheel zoom", 0x9A9A9AFFL)
                text("space pause", 0x9A9A9AFFL)
                text("F8 fit grid", 0x9A9A9AFFL)
                if (canSave) text("F9 save · F10 load", 0x9A9A9AFFL)
            }

            // ⚠️ **Immediately after the build panel and before anything else**, because it is
            // positioned off [lastPanelRect] and that is only the build panel until the next panel
            // is emitted. Same arrangement the wiki has with the inspector, for the same reason.
            if (controller.tool == Tool.Build) materialColumn(controller, stock, s.creative)

            // ⚠️ **In this order and coupled by that rect**, because the reference hangs off the
            // top of the inspector: the two are read together, and the one a player is holding the
            // pointer over is the one that must not move.
            val inspector = inspectPanel(controller)
            wikiPanel(controller, inspector)

            // ⚠️ **Last in the frame.** A sheet dims everything under it and takes every click
            // inside its box, so anything drawn after it would sit on top of a scrim that is
            // supposed to be covering the screen.
            when (openSheet) {
                Sheet.None -> {}
                Sheet.Menu -> menuSheet(controller)
                Sheet.Readouts -> readoutsSheet(controller, fps, stock)
                Sheet.SaveLoad -> saveLoadSheet(controller)
            }
        }
        // Clear one-shot status messages after they've been displayed.
        saveStatus = ""
        clipboardStatus = ""
    }

    /**
     * Every number the sim will tell you about itself: ledgers, drift lamps and circuits.
     *
     * ⛔ **Instruments, not gameplay.** Each of these exists to answer "is the simulation lying to
     * me" — the mass balance is the conservation tripwire, the atmosphere lamp catches a hull
     * breach's bookkeeping, the energy rows are parked mid-rescale and say so. None of them is a
     * thing a player *does* anything with, and all of them together were the top-left corner of the
     * screen in every frame of a game about looking at a ship.
     *
     * ⚠️ **Nothing was dropped in the move.** It is the same block, in the same order, with the same
     * comments on why each figure is the figure — including FLIGHT and SIGNALS, which are arguably
     * play rather than diagnosis. They are here because they were in the panel; if either wants to
     * come back out it should come back out on its own account and not by being overlooked.
     */
    private fun UiBuilder.readoutsSheet(controller: OutofspaceController, fps: Float, stock: Stockpile) {
        val s = controller.state
        val body: org.emerge.render.torus.ui.PanelBuilder.() -> Unit = {
                // ⚠️ The panel's "OUT OF SPACE" heading went with the panel: the sheet has a title
                // bar of its own, and a heading naming the game inside a window inside the game says
                // nothing to anybody.
                // The controller's count, not the state's: `state.tick` counts frozen ticks too and
                // would climb while the game was stopped. See [OutofspaceController.livedTicks].
                keyValue("Tick", controller.livedTicks.toString())
                // ⚠️ FPS and the speed dial are not here: they came back out to the top-right
                // corner, where they are permanently visible — see [timePanel]. Printing them twice
                // would be two places to read one number and one of them free to go stale.
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
                text(
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
                text(if (airBalanced) "balanced" else "LEAK", if (airBalanced) 0x6ED09AFFL else 0xE05A4AFFL)
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
                text("(energy ledgers parked  ·  unit rescale step 3)", 0x8A8A8AFFL)
                gap()
                // One row per circuit the player has actually laid, rather than six fixed colours
                // most of which read zero. An empty list here means no wire aboard, which is the
                // honest thing to say.
                title("SIGNALS")
                if (s.signals.networkCount == 0) {
                    text("(no wire laid)", 0x5A5A5AFFL)
                } else {
                    for (id in 0 until s.signals.networkCount) {
                        val value = s.signals.ofNetwork(id)
                        keyValue("circuit $id", "${value / 10}%", 0x9A9A9AFFL, if (value > 0) 0x6EE08AFFL else 0x5A5A5AFFL)
                    }
                }
        }
        val dismiss = { openSheet = Sheet.None }
        // Taller and wider than the menu: it is a column of readouts rather than four buttons, and
        // it scrolls, which is what lets the circuit list be as long as the wiring is.
        if (screenW > NARROW_MAX_DP * density) {
            val w = minOf(READOUTS_WIDTH_DP * density, screenW * 0.6f)
            val h = screenH * 0.85f
            sheet(
                "oos-readouts", "READOUTS", onDismiss = dismiss,
                boxX = (screenW - w) * 0.5f, boxY = (screenH - h) * 0.5f, boxW = w, boxH = h,
                rowHeight = SHEET_ROW_DP, textSize = 14f, body = body,
            )
        } else {
            sheet("oos-readouts", "READOUTS", onDismiss = dismiss, heightFraction = 0.85f, rowHeight = 34f, textSize = 15f, body = body)
        }
    }

    /**
     * The game itself rather than the world: saving it, framing it, stopping it, throwing it away.
     *
     * ⛔ **Centred, and over everything.** These were an always-open cluster in the bottom-right
     * corner, which is the one place in this HUD where a control was competing for space with a
     * readout — and losing, since the storage panel shares that corner. They are also the rarest
     * things here: a player saves once in a session and resets once in a while. Rare, deliberate and
     * dangerous is exactly the shape a modal is for.
     *
     * ⚠️ **Every one of them has a key, and the sheet says which**, so the fast path never goes
     * through here at all.
     *
     * Uses the shared [org.emerge.render.torus.ui.UiBuilder.sheet], which is the same primitive
     * cyto's overlays are built from — a centred popover where there is room for one and a
     * full-width panel off the bottom edge where there is not.
     */
    private fun UiBuilder.menuSheet(controller: OutofspaceController) {
        val body: org.emerge.render.torus.ui.PanelBuilder.() -> Unit = {
            if (canSave) {
                if (saveStatus.isNotEmpty()) text(saveStatus, 0x9AA4B4FFL)
                if (clipboardStatus.isNotEmpty()) text(clipboardStatus, 0x9AA4B4FFL)
                actionRow(
                    listOf(
                        Triple("SAVE  ·  F9", 0x2E5A6BFFL) { onSave() },
                        Triple("LOAD  ·  F10", 0x2E5A6BFFL) { onLoad() },
                    ),
                )
            }
            actionRow(
                listOf(
                    Triple("FIT  ·  F8", 0x2E5A6BFFL) { onFit(); openSheet = Sheet.None },
                    Triple(if (controller.paused) "PLAY  ·  SPACE" else "PAUSE  ·  SPACE", 0x3A6EA5FFL) {
                        onTogglePause()
                    },
                ),
            )
            // ⚠️ The way to the instruments, and the only way: they are not on screen otherwise.
            button("READOUTS  ·  ledgers, drift, circuits", 0x2A3550FFL) { openSheet = Sheet.Readouts }
            gap()
            // Last, alone, and the only red thing in here: it throws the vessel away.
            button("RESET", 0xCC3333FFL) { onReset(); openSheet = Sheet.None }
        }
        val dismiss = { openSheet = Sheet.None }
        // ⛔ A centred popover where there is room and a bottom sheet where there is not: a
        // full-width sheet on a desktop monitor is a metre of empty table with six words on it, and
        // a centred box on a phone is a postage stamp you cannot hit.
        if (screenW > NARROW_MAX_DP * density) {
            val w = minOf(SHEET_WIDTH_DP * density, screenW * 0.5f)
            // ⚠️ **Sized to what is in it**, because a popover does not auto-size the way a panel
            // does — it is given a box. Left at a round number it was a third of the screen holding
            // three buttons, which reads as a menu that has lost its contents. Two rows of controls,
            // a gap and RESET, plus room for the one-shot status line a save leaves behind.
            val rows = (if (canSave) 2 else 1) + 2
            val h = minOf(screenH * 0.5f, (SHEET_TITLE_DP + rows * SHEET_ROW_DP + 30f) * density)
            sheet(
                "oos-menu", "MENU", onDismiss = dismiss,
                boxX = (screenW - w) * 0.5f, boxY = (screenH - h) * 0.5f, boxW = w, boxH = h,
                rowHeight = SHEET_ROW_DP, textSize = 14f, body = body,
            )
        } else {
            sheet("oos-menu", "MENU", onDismiss = dismiss, heightFraction = 0.4f, rowHeight = 40f, textSize = 15f, body = body)
        }
    }

    /**
     * Save/load dialog: type a name to save, click a name to load, click "Del" to delete.
     *
     * Replaces the old single-slot save/load with a directory-based store where each save has a
     * user-given name. Enter saves/loads, Escape cancels.
     */
    private fun UiBuilder.saveLoadSheet(controller: OutofspaceController) {
        val name = currentName()
        val body: org.emerge.render.torus.ui.PanelBuilder.() -> Unit = {
            if (saveMode) {
                title("Save World", 0x6FD6C4FFL)
                text("Type a name (Enter to save):", 0x8B96A8FFL)
                gap(4f)
                text("> ${name}_", 0xFFFFFFFFL)
                gap(10f)
                if (name.isNotBlank()) {
                    button("Save", 0x2E6E5EFFL) {
                        pendingOnSave?.invoke(name)
                    }
                }
                button("Cancel", 0x53384AFFL) { closeSaveLoadDialog() }
            } else {
                title("Load World", 0x6FD6C4FFL)
                gap(4f)
                if (availableSaves.isEmpty()) {
                    text("No saves yet.", 0x8B96A8FFL)
                } else {
                    for (s in availableSaves.take(12)) {
                        if (pendingDelete == s) {
                            actionRow(listOf(
                                Triple("Delete '$s'?", 0x53384AFFL) { },
                                Triple("Yes", 0xB03A3AFFL) { pendingOnDelete?.invoke(s); pendingDelete = null },
                                Triple("No", 0x2A3550FFL) { pendingDelete = null },
                            ))
                        } else {
                            actionRow(listOf(
                                Triple(s, 0x2A3550FFL) { pendingOnLoad?.invoke(s) },
                                Triple("Del", 0x53384AFFL) { pendingDelete = s },
                            ))
                        }
                    }
                }
                gap(8f)
                button("Cancel", 0x53384AFFL) {
                    pendingDelete = null
                    closeSaveLoadDialog()
                }
            }
        }
        val dismiss = { pendingDelete = null; closeSaveLoadDialog() }
        if (screenW > NARROW_MAX_DP * density) {
            val w = minOf(SHEET_WIDTH_DP * density, screenW * 0.5f)
            val maxRows = if (saveMode) 5 else 14
            val h = minOf(screenH * 0.6f, (SHEET_TITLE_DP + maxRows * SHEET_ROW_DP + 30f) * density)
            sheet(
                "oos-saveload", if (saveMode) "SAVE" else "LOAD", onDismiss = dismiss,
                boxX = (screenW - w) * 0.5f, boxY = (screenH - h) * 0.5f, boxW = w, boxH = h,
                rowHeight = SHEET_ROW_DP, textSize = 14f, body = body,
            )
        } else {
            sheet("oos-saveload", if (saveMode) "SAVE" else "LOAD", onDismiss = dismiss, heightFraction = 0.5f, rowHeight = 40f, textSize = 15f, body = body)
        }
    }

    /**
     * **The session, in one corner**: how fast time is going, which mode owns the keyboard, and the
     * way into the game's own menu.
     *
     * ⛔ **These are the controls that are about the sitting rather than about a tile**, which is
     * what makes them one panel. Everything in the bottom-left is an answer to "what does a click
     * do"; nothing here is. The mode toggle and the menu button used to head that panel and were
     * pushed up and down the screen by the length of whatever tool was selected — a button that
     * moves when you change tools is a button you have to look for.
     *
     * ⚠️ **Time first, and in the very corner.** It is the control reached for without looking, and
     * the only one here a player touches more than a handful of times a session.
     *
     * ⚠️ **Every rate button shows its own state**, which is the whole reason there are seven rather
     * than a pair of arrows: a dial you step through tells you the rate you are at only by printing
     * it somewhere else, and a player who has lost track of whether they are at 4x or 8x has to go
     * and read a number. Here the answer is which button is lit.
     *
     * ⚠️ **PAUSE and the rate are independent, and the panel has to show both**, because they are:
     * the dial says how fast the clock turns and the pause says whether passes run, and a world
     * stopped at 4x is still settling its animations at 4x. See `OutofspaceController.speed`.
     */
    private fun UiBuilder.sessionPanel(controller: OutofspaceController, fps: Float) {
        // ⛔ **A stated width, because a panel auto-sizes and this one changes contents.** Entering
        // flight adds two rows of key hints, and the widest of them was setting the panel's width —
        // so the seven time buttons, which share that width between them, grew and shifted every
        // time the player switched mode. A control that moves when you do something unrelated is a
        // control you have to look for, which is the whole complaint the mode toggle was moved up
        // here to answer.
        panel(Anchor.TopLeft, rowHeight = 20f, minWidth = SESSION_WIDTH_DP) {
            val midLabel = if (controller.paused) "PAUSED" else rateLabel(controller.speed)
            val midColor = if (controller.paused) 0x8A5A2AFFL else 0x232A38FFL
            row {
                button("MENU", 0x2A3550FFL) { openSheet = Sheet.Menu }
                controlRow(listOf(
                    ActionButton("<<", 0x3A6EA5FFL, enabled = controller.speed > 0.25) { controller.nudgeSpeed(false) },
                    ActionButton(midLabel, midColor) { onTogglePause() },
                    ActionButton(">>", 0x3A6EA5FFL, enabled = controller.speed < 8f) { controller.nudgeSpeed(true) },
                ))
                spacer()
                // ⚠️ Coloured by what it means rather than by what it is: the number only ever matters
                // as "is this smooth", and a bare figure makes every player learn the thresholds.
                val shown = fps.toInt()
                keyValue(
                    "FPS",
                    shown.toString(),
                    0x9A9A9AFFL,
                    when {
                        shown >= 50 -> 0x6ED09AFFL
                        shown >= 25 -> 0xE0A93AFFL
                        else -> 0xE05A4AFFL
                    },
                )
            }
            gap()
            // Which mode owns the keyboard, and how to change it — said loudly, because a player
            // whose WASD has stopped panning needs the answer immediately.
            val flying = controller.mode == Mode.Flight
            button(if (flying) "FLIGHT MODE  ·  F to build" else "BUILD MODE  ·  F to fly", if (flying) 0x8A5A2AFFL else 0x232A38FFL) {
                controller.mode = controller.mode.next
            }
            if (flying) {
                // The autopilot lives beside the mode toggle because it is the same kind of thing:
                // a standing instruction about the whole ship, not a machine's setting.
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
                if (held.isEmpty()) {
                    // ⚠️ Two short rows rather than one long one: the single row was half again the
                    // width of everything else here and set the panel's size on its own.
                    text("WASD moves  ·  QE turns", 0x9A9A9AFFL)
                    text("arrows / Z / X also drive buttons", 0x9A9A9AFFL)
                } else {
                    text("holding: ${held.joinToString(" ") { it.label }}", 0x6EE08AFFL)
                }
            }
        }
    }

    /**
     * A speed as a button reads it: `.25x`, `.5x`, `1x`, `8x`.
     *
     * ⚠️ **Built from hundredths rather than from the float's own string**, for two reasons. Seven
     * buttons share one row, so "0.25x" is two characters that buy nothing — the leading zero goes
     * and a trailing one never appears, which is what makes ".5x" and not ".50x". And a float's
     * decimal spelling is the platform's business, not ours: this is common code and JS and the JVM
     * are free to disagree about it.
     */
    private fun rateLabel(rate: Float): String {
        if (rate >= 1f) return "${rate.toInt()}x"
        val hundredths = (rate * 100f).toInt()
        return if (hundredths % 10 == 0) ".${hundredths / 10}x" else ".${hundredths}x"
    }

    /**
     * Ship nav: origin marker + velocity needle (two scales — distance vs velocity).
     *
     * @param prospecting the species the field is being read for, or null for the full spectrum. It is
     *   whatever the reference is open on, so reading about a mineral is itself the act of going
     *   looking for it — the map answers the question the article raised.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.navView(s: VesselState, prospecting: Species?) = canvas {
        RockSpawner.highlight = prospecting
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
    private fun UiBuilder.inspectPanel(controller: OutofspaceController): Ui.UiElement? {
        val s = controller.state
        val tile = controller.inspectTile
        if (tile == TileIndex.NONE || tile.index < 0 || tile.index >= s.grid.size) return null
        val layers = inspectableLayers(s, tile)
        if (layers.isEmpty()) return null
        // A pinned layer that has since stopped existing — the belt was deleted, the room was
        // sealed — falls back to the top of the list rather than showing an empty panel.
        val layer = controller.inspectLayer
        val grid = s.grid

        // ⛔ **Bottom-right, not top-right.** It used to stack under the stockpile, which put the
        // thing a player is *reading right now* below a thing they check twice a session — and made
        // its length the stockpile's problem, since a busy machine pushed the column down the
        // screen. Anchored to the bottom it grows upward into space nothing else claims, and the
        // reference then hangs above it. See [wikiPanel].
        panel(Anchor.BottomRight, rowHeight = 20f) {
            title("INSPECT  ·  (${grid.xOf(tile)}, ${grid.yOf(tile)})")
            gap()
            // The tabs are the cycle made visible. A player who never notices that clicking again
            // steps the layer can still reach every one of them by name, and a player who does can
            // see how many are left.
            val deckLabel = s.machineCovering(tile)?.kind?.label ?: InspectLayer.Deck.label
            actionRow(
                layers.map { option ->
                    Triple(
                        if (option == InspectLayer.Deck) deckLabel else option.label,
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
        return lastPanelRect
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
        val machine = s.machineCovering(tile) ?: run { text("(bare deck)", 0x9A9A9AFFL); return }
        val anchor = s.occupancy[tile]
        val parts = machine.tiles(grid)

        val deconstructing = anchor in s.scrapping
        val built = s.deck.builtPermille(machine)
        if (deconstructing || built < 1000) {
            row {
                if (deconstructing) text("DECONSTRUCTING", 0xE05A4AFFL)
                else text("UNDER CONSTRUCTION", 0x9A9A9AFFL)
                text("(${built / 10}%)", 0xE0A93AFFL)
            }
        }
        gap()
        var casing = Mixture.EMPTY
        var energy = 0L
        var capacity = 0L
        for (part in parts) {
            casing += s.deck.stuff.mixtureAt(part)
            energy += s.deck.stuff.energyAt(part)
            capacity += s.deck.stuff.heatCapacityAt(part)
        }
        val dominantCasing = casing.dominant
        if (dominantCasing != null) {
            text("MADE FROM", 0x9A9A9AFFL)
            row {
                text(mass(casing.total), 0xFFFFFFFFL)
                speciesRow(controller, dominantCasing)
            }
        }
        gap()
        if (capacity > 0L) {
            text("TEMPERATURE", 0x9A9A9AFFL)
            val k = (energy / capacity).toInt()
            text(
                "${k}K  (${k - 273}C)",
                if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
        }

        val buffers = contentsBreakdown(machine, anchor, grid, s.buffers)
        if (buffers.isNotEmpty()) {
            section("contents", "CONTENTS", open = true) {
                for ((label, resource) in buffers) {
                    keyValue(label, mass(resource.total))
                    compositionRows(controller, resource)
                }
            }
        }

        val storage = machine as? Storage
        if (storage != null) {
            section("storage", "FILTER", open = true) { storageControls(controller, storage) }
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
        section("wiring", "WIRING", open = true) { wiringControls(controller, anchor, machine) }
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
        val segment = s.conduits.at(conduit, tile) ?: run { text("(nothing here)", 0x9A9A9AFFL); return }

        val deconstructing = segment.deconstructing
        val built = s.conduits.builtPermille(conduit, tile)
        if (deconstructing || built < 1000) {
            row {
                if (deconstructing) text("DECONSTRUCTING", 0xE05A4AFFL)
                else text("UNDER CONSTRUCTION", 0x9A9A9AFFL)
                text("(${built / 10}%)", 0xE0A93AFFL)
            }
        }
        gap()
        val casing = s.conduits.tracks[conduit].mixtureAt(tile)
        val dominantCasing = casing.dominant
        if (dominantCasing != null) {
            row {
                text("MADE FROM ", 0x9A9A9AFFL)
                spacer()
                text(mass(casing.total), 0xFFFFFFFFL)
                speciesRow(controller, dominantCasing)
            }
        }
        gap()
        val capacity = s.conduits.heatCapacityAt(conduit, tile)
        if (capacity > 0L) {
            val k = (s.conduits.energyAt(conduit, tile) / capacity).toInt()
            keyValue(
                "TEMPERATURE",
                "${k}K  (${k - 273}C)",
                0x9A9A9AFFL,
                if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
        }

        // What the layer is *for*. Each conduit carries a different kind of thing, and only rail and
        // pipe carry anything at all yet — a wire carries a number and power carries nothing, and
        // saying so is better than a blank space where a cargo readout would be.
        gap()
        when (conduit) {
            Conduit.Rail -> {
                val riding = s.rail.resourceAt(tile)
                if (riding != null) {
                    keyValue("CARRYING", mass(riding.total))
                    compositionRows(controller, riding)
                    val k = s.rail.stuff.kelvinAt(tile)
                    keyValue(
                        "TEMPERATURE",
                        "${k}K  (${k - 273}C)",
                        0x9A9A9AFFL,
                        if (k > Temperature.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
                    )
                }
            }
            Conduit.Pipe -> {
                val inside = s.pipeAir.mixtureAt(tile)
                if (inside.isEmpty) {
                    text("(empty)", 0x9A9A9AFFL)
                } else {
                    keyValue("INSIDE", mass(inside.total))
                    compositionRows(controller, inside, maxEntries = 5)
                    val k = s.pipeAir.kelvinAt(tile)
                    keyValue("FLUID TEMPERATURE", "${k}K  (${k - 273}C)", 0x9A9A9AFFL, 0x9AC0E0FFL)
                }
            }
            Conduit.Signal -> {
                val network = s.networks[tile]
                if (network < 0) {
                    text("(not part of a circuit)", 0x9A9A9AFFL)
                } else {
                    val value = s.signals.ofNetwork(network)
                    keyValue("CIRCUIT $network", "${value / 10}%", 0x9A9A9AFFL, if (value > 0) 0x6EE08AFFL else 0x5A5A5AFFL)
                }
            }
            Conduit.Power -> text("carries nothing yet", 0x9A9A9AFFL)
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
        // Straight into the details - this gap separates them from the layer selector
        gap()
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
            text("   VACUUM", 0x7A8A9AFFL)
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
            speciesRow(controller, species, "${(if (percent < 1) "<1" else percent.toString()).padStart(4)}%")
        }
        if (present.size > maxEntries) text("${(if (listed > 99) "<1" else (100L - listed).toString()).padStart(4)}% other", 0x9AA4B4FFL)
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
    /**
     * **What you are building out of** — the properties that decide it, and every choice available.
     *
     * ⛔ **A column of its own rather than a section of the build menu**, and the list had to move
     * for the reason the wiki's body did: a panel auto-sizes to its content, and the number of
     * things a ship can be built from is set by what the player has mined rather than by anything
     * this file knows. It was capped at four entries with "and N more" underneath — which is to say
     * the materials a player had least of were the ones they could pick, and the rest were
     * unreachable. Here the list scrolls and the cap is gone.
     *
     * ⚠️ **Two rectangles and not one**, which is the same lesson the wiki head taught: with the
     * properties inside the scroll area, reading down a long list scrolled away the numbers you were
     * reading the list *against*. What is pinned is what stays true however far down you are — what
     * you have currently chosen, what it is like, and the way to read more about it.
     *
     * ⚠️ **Positioned off [lastPanelRect]**, which is the build panel as it actually came out this
     * frame. A hand-written offset would be the height of a menu whose length depends on the tool,
     * the mode and whether the ship can be saved, copied into a second place and wrong immediately.
     */
    private fun UiBuilder.materialColumn(
        controller: OutofspaceController,
        stock: Stockpile,
        creative: Boolean,
    ) {
        val build = lastPanelRect ?: return
        val margin = 12f * density
        val width = MATERIAL_COLUMN_WIDTH_DP * density
        val x = build.x + build.w + margin
        // Off the top of the build panel, not off the bottom of the screen: the two are one control
        // read together, so they share a top edge whatever the menu's length happens to be.
        //
        // ⛔ **Clamped into the window, because the build panel is not.** It is bottom-anchored and
        // auto-sized, so on a short window its top edge is *negative* — and sharing that edge put the
        // pinned properties and the way into the wiki above the top of the screen, which is the one
        // thing this column is arranged in two rectangles to prevent. Seen at 440px.
        val top = maxOf(build.y, margin)
        val bottom = maxOf(build.y + build.h, top)
        val rowH = 20f
        val chosen = controller.buildMaterial

        // ── Pinned: what is chosen, and what it is like ───────────────────────────────────────
        val infoRows = if (chosen == null) 3 else 7
        val infoHeight = infoRows * rowH * density + 16f * density
        scrollArea("material-head", x, top, width, infoHeight, rowHeight = rowH, background = 0x000000C0L) {
            title("MATERIAL  ·  ${chosen?.name?.uppercase() ?: "NONE PICKED"}")
            if (chosen == null) {
                text("pick one below", 0xE05A4AFFL)
                text("nothing places until you do", 0x9A9A9AFFL)
            } else {
                materialFacts(chosen)
                // ⚠️ **The way into the article, and it is a button rather than the name being
                // clickable** — the name is up in the title where a click would compete with
                // nothing, but a player who has just chosen a material is not looking at the title,
                // they are looking at the numbers. The button sits under them.
                button("MORE INFO", 0x2E5A6BFFL) { controller.openWiki(chosen) }
            }
        }

        // ── The list, as long as the hold makes it ────────────────────────────────────────────
        val listTop = top + infoHeight + margin
        val height = bottom - listTop
        // Nothing left to show it in — a short window, or a build menu that has eaten the screen.
        // Better no list than a scroll area of negative height, which draws as a sliver of nothing.
        if (height < MIN_REFERENCE_HEIGHT_DP * density) return
        val offer = stock.buildableSpecies
        scrollArea("material-list", x, listTop, width, height, rowHeight = rowH, background = 0x000000C0L) {
            title("LOOSE ABOARD")
            if (offer.isEmpty()) text("nothing the network can deliver", 0xC8A44AFFL)
            for (species in offer) materialChoice(controller, species, mass(stock.buildable(species)))
            // ⚠️ A section of its own, below the hold and never merged into it — the allowance is a
            // property of the mode rather than something aboard. See [Stockpile.CREATIVE_MATERIALS].
            if (creative) {
                val free = Stockpile.CREATIVE_MATERIALS.filter { it !in offer }
                if (free.isNotEmpty()) {
                    gap()
                    title("CREATIVE")
                    text("always available", 0x7A7A7AFFL)
                    for (species in free) materialChoice(controller, species, "")
                }
            }
        }
    }

    /**
     * One material a player may choose, and how much of it there is.
     *
     * ⚠️ **The whole row selects; there is no separate "info" affordance.** The properties panel
     * above already follows the selection, so choosing a material *is* how you read about it, and a
     * second click target per row would be two ways to do one thing on a list that can be a hundred
     * entries long.
     */
    private fun PanelBuilder.materialChoice(
        controller: OutofspaceController,
        species: Species,
        held: String,
    ) {
        val selected = species == controller.buildMaterial
        button(
            "${if (selected) ">" else " "} ${species.name}${if (held.isEmpty()) "" else "  $held"}",
            if (selected) speciesColor(species) or 0xFFL else 0x232A38FFL,
        ) { controller.buildMaterial = species }
    }

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

    private fun PanelBuilder.speciesRow(controller: OutofspaceController, species: Species, prefix: String? = null, suffix: String? = null) =
        row {
            if (prefix != null) text(prefix)
            button(species.name.uppercase(), 0x1E2634FFL) { controller.openWiki(species) }
            if (suffix != null) text(suffix)
        }

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
    private fun UiBuilder.wikiPanel(controller: OutofspaceController, inspector: Ui.UiElement?) {
        val species = controller.wikiSpecies ?: return
        val margin = 12f * density
        val rowH = 20f
        val width = MIN_REFERENCE_WIDTH_DP * density
        // Right edges flush with the inspector's, so the two read as one column rather than as two
        // boxes that happen to be near each other. With no inspector open it takes the screen's.
        val right = inspector?.let { it.x + it.w } ?: (screenW - margin)
        val x = right - width
        // It hangs off the top of the inspector, or off the bottom of the screen when there is none.
        val bottom = (inspector?.y ?: screenH) - margin

        // ── The head: pinned, and everything in it is a reason to pin it ──────────────────────
        //
        // ⚠️ **The way out must not scroll away.** With the title and CLOSE inside the scroll area,
        // reading to the bottom of carbon left a player looking at a list with no name on it and no
        // button to shut it. What is up here is what stays true however far down the article you
        // are: which species this is, how to leave, and the numbers that are one line each.
        //
        // ⚠️ **Its height is counted rather than measured**, because it has to be known before the
        // body's rectangle can be: a fixed number of rows, plus the one that is only true of a
        // fluid. A panel would size itself and cannot be placed here — the whole column is built
        // upward from an edge, and an anchored panel does not know about edges.
        val headRows = if (species.fluid != null) 9 else 8
        val headH = headRows * rowH * density + 16f * density
        val head: PanelBuilder.() -> Unit = {
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
            if (species.fluid != null) text("can be a gas", 0x9AC0E0FFL)
        }

        // ── The body: as long as the chemistry makes it, and no longer ────────────────────────
        //
        // ⛔ **It grows upward from the inspector and takes only the height it needs**, which is
        // what [scrollAreaAbove] exists for. Given a fixed rectangle instead, a two-line article on
        // iron was two lines of text adrift in a box the height of the screen — which reads as a
        // panel that failed to load rather than as a short answer. Carbon takes part in three
        // reactions and comes out of a fourth, so the same box has to be able to fill the column.
        //
        // ⚠️ **A long article covers the stockpile, deliberately** (Stu). The reference is a thing
        // you opened on purpose and the stockpile is a thing you glance at; when the two want the
        // same pixels the one being read wins. It is drawn after, so it simply does.
        val maxBody = bottom - margin - headH
        // Nothing left to show it in — a very short window, or an inspector reading a busy machine.
        // Better no reference than a scroll area of negative height, which draws as a sliver.
        if (maxBody < MIN_REFERENCE_HEIGHT_DP * density) return
        // ⚠️ **Opaque, both halves.** The body used to be `0x000000C0` because it only ever had the
        // starfield behind it; over the stockpile that translucency put two columns of numbers on
        // the same pixels and neither could be read.
        val bodyTop = scrollAreaAbove(
            "wiki", x, bottom, width, maxBody, rowHeight = rowH, background = 0x0B0E14FFL,
        ) {
            abundanceSection(species)

            val parts = compositionOf(species)
            if (parts.isNotEmpty()) {
                section("wiki-made-of", "COMPOSITION", open = true) {
                    for (part in parts) {
                        val percent = part.partsPerThousand / 10
                        speciesRow(
                            controller,
                            part.element,
                            "${percent.toString().padStart(3)}%",
                            "x${part.atoms}",
                        )
                    }
                }
            }

            reactionSection(controller, "wiki-uses", "TAKES PART IN", reactionsConsuming(species))
            reactionSection(controller, "wiki-from", "MADE BY", reactionsProducing(species))
        }
        // Drawn after the body because it is placed against it: an area that sizes itself cannot say
        // where its top is until it has drawn. Nothing overlaps, so the order is only an order.
        scrollArea(
            "wiki-head", x, bodyTop - headH, width, headH,
            rowHeight = rowH, background = 0x121722FFL, block = head,
        )
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
        if (!species.occursNaturally) {
            text("never loose in rock", 0xE0A93AFFL)
            text("it has to be made", 0x9A9A9AFFL)
        } else {
            section("wiki-abundance", "FOUND IN ROCK", open = true) {
                keyValue("SHARE", abundanceOf(species), 0x9A9A9AFFL, 0x9ED0B0FFL)
                keyValue(
                    "RANK",
                    "${abundanceRank(species)} of ${Species.NATURAL.size}",
                    0x9A9A9AFFL,
                    0x9AA4B4FFL,
                )
                text("by mass, of a reference rock", 0x7A7A7AFFL)
            }
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
    private fun PanelBuilder.storageControls(controller: OutofspaceController, storage: Storage) {
        val grid = controller.state.grid
        val store = bufferTile(grid, storage, storage.center, BufferRole.Inside)
        val held = store?.let { controller.state.buffers.resourceAt(it) }
        val filter = storage.filter

        if (filter == null) {
            val dominant = held?.dominant
            if (dominant == null) {
                keyValue("TAKES", "ANYTHING", 0x9A9A9AFFL, 0x9ED0B0FFL)
                button("LOCK PURITY TO ${SpeciesFilter.MAX_PERCENT}%", 0x2E5A6BFFL) {
                    controller.lockStoragePercent(storage, SpeciesFilter.MAX_PERCENT)
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
                    controller.lockStoragePercent(storage, SpeciesFilter.PERCENTS.reversed().firstOrNull {
                        (it ?: 0) <= currentPercent }
                    )
                    controller.lockStorageSpecies(storage, dominant)
                }
            }
        } else {
            button(
                listOf(
                    "LOCKED TO " to null,
                    (filter.species?.name?.uppercase() ?: "PURITY") to filter.species?.let { speciesColor(it) },
                ),
                0x2E5A6BFFL,
            ) { controller.toggleStorageFilterSpecies(storage) }
            if (filter.minPercent == null) {
                button("ANY PURITY", 0x2E5A6BFFL) {
                    controller.cycleStorageFilterPercent(storage, 1)
                }
            } else {
                clauseRow(
                    lhs = "AT LEAST",
                    cmp = "${filter.minPercent}%",
                    rhs = "pure",
                    onLhs = { controller.cycleStorageFilterPercent(storage, -1) },
                    onCmp = { controller.cycleStorageFilterPercent(storage, -1) },
                    onRhs = { controller.cycleStorageFilterPercent(storage, 1) },
                )
            }
        }

        row {
            button(
                "AUTO-LOCK ${if (storage.autoLock) "ON" else "OFF"}",
                if (storage.autoLock) 0x6EE08AFFL else 0xE0A93AFFL,
                weight = 1f) {
                controller.toggleStorageAutoLock(storage)
            }
            gap()
            button(
                "AUTO-UNLOCK ${if (storage.autoUnlock) "ON" else "OFF"}",
                if (storage.autoUnlock) 0x6EE08AFFL else 0xE0A93AFFL,
                weight = 1f) {
                controller.toggleStorageAutoUnlock(storage)
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
        text("tap a dial to raise it  ·  wraps around", 0x7A7A7AFFL)
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
                text("WSAD moves  ·  QE turns  ·  press F to fly", 0x9A9A9AFFL)
                // What the motor makes of the stick right now, which is the readout that turns a
                // badly placed engine from a mystery into something a player can see is idle.
                // Read off the machine and never recomputed here — see [Thruster.firing]. On a
                // ship whose engines were throttled against each other to keep a burn straight, the
                // single-motor answer is the wrong number and it is wrong in the flattering
                // direction.
                keyValue("FIRING", "${machine.firing.coerceAtLeast(0) / 10}%", 0x9A9A9AFFL, 0xE0A93AFFL)
            }
            ThrusterControl.Wire -> text("driven by its WIRING, below", 0x9A9A9AFFL)
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
            text("held in FLIGHT mode  ·  press F to switch", 0x9A9A9AFFL)
        }

        if (machine is Sensor) {
            val watched = grid.neighbour(tile, machine.facing)
            val target = if (watched != TileIndex.NONE) controller.state.machineCovering(watched) else null
            text("watching: ${target?.kind?.label ?: "(nothing)"}", 0x9A9A9AFFL)
            row {
                text(
                    "ON WHEN ",
                    0x9A9A9AFFL,
                )
                button(if(machine.threshold < 0) "<" else ">", 0x2E5A6BFFL) {
                    controller.invertSensorThreshold(tile)
                }
                button("${machine.threshold.absoluteValue/10}%", 0x2E5A6BFFL) {
                    controller.cycleSensorThreshold(tile, 1)
                }
                text(
                    " FOR ",
                    0x9A9A9AFFL,
                )
                val delaying = machine.delayedFor < machine.delay && machine.delayedFor > 0
                val delayedForSeconds = seconds(machine.delayedFor.toLong(), controller.cfg)
                val delaySeconds = machine.delay * controller.cfg.secondsPerTick
                val label = if (delaying) "$delayedForSeconds/$delaySeconds" else "$delaySeconds"
                val color = if (delaying) 0xE0A93AFFL else 0x6EE08AFFL
                button(label, color) {
                    controller.cycleSensorDelay(tile, 1)
                }
                text(
                    " SECONDS",
                    0x9A9A9AFFL,
                )
            }
            row {
                text(
                    "OFF WHEN ${if(machine.threshold < 0) ">= " else if(machine.threshold > 0) "<= " else ""}${machine.threshold.absoluteValue/10}% FOR",
                    0x9A9A9AFFL,
                )
                val releasing = machine.releasedFor < machine.release && machine.releasedFor > 0
                val releasedForSeconds = seconds(machine.releasedFor.toLong(), controller.cfg)
                val releaseSeconds = machine.release * controller.cfg.secondsPerTick
                val label = if (releasing) "$releasedForSeconds/$releaseSeconds" else "$releaseSeconds"
                val color = if (releasing) 0xE0A93AFFL else 0x6EE08AFFL
                button(label, color) {
                    controller.cycleSensorRelease(tile, 1)
                }
                text(
                    " SECONDS",
                    0x9A9A9AFFL,
                )
            }
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
                text("nothing has passed through yet", 0x9A9A9AFFL)
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
        val on = machine.wiring.isOn(action, controller.state.signals.at(tile))
        keyValue(action.label, if (on) "ON" else "OFF", 0x9A9A9AFFL, if (on) 0x6ED09AFFL else 0xE05A4AFFL)

        if (triggers.isEmpty()) {
            text("(never runs  ·  no terms)", 0xE05A4AFFL)
        } else {
            for ((slot, trigger) in triggers.withIndex()) {
                clauseRow(
                    lhs = if (slot == 0) "WHEN " + trigger.source.label else "PLUS " + trigger.source.label,
                    cmp = "x",
                    rhs = if (trigger.negated) "IS OFF" else "IS ON",
                    onLhs = { controller.cycleTriggerSource(tile, action, slot, 1) },
                    onCmp = { controller.wire(tile, action, slot, null) },
                    onRhs = { controller.toggleTriggerNegated(tile, action, slot) },
                )
            }
        }
        button("+ ADD TERM", 0x2E5A6BFFL) {
            controller.wire(tile, action, triggers.size, Trigger(SignalSource.Wire))
        }
        text("tap source or ON-OFF to change, x to delete", 0x7A7A7AFFL)
        text(if (wired) "WIRE reads circuit ${controller.state.networks[tile]}" else "WIRE reads 0  ·  no wire under this tile", 0x7A7A7AFFL)
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

    private fun time(ticks: Long, config: OutofspaceConfig): String {
        val sign = if (ticks < 0L) "-" else ""
        val ms = (if (ticks < 0L) -ticks else ticks) * 1000 / config.ticksPerSecond
        return when {
            ms < 1000L -> "$sign${ms} ms"
            ms < 60_000L -> "$sign${ms / 1000}.${(ms % 1000) / 100} sec"
            else -> "$sign${ms / 60_000L}:${(ms % 60_000L) / 1000}"
        }
    }

    private fun seconds(ticks: Long, config: OutofspaceConfig): String {
        val sign = if (ticks < 0L) "-" else ""
        val ms = (if (ticks < 0L) -ticks else ticks) * 1000 / config.ticksPerSecond
        return "$sign${ms / 1000}.${(ms % 1000) / 100}"
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
         * Below this width the menu is a full-width panel off the bottom edge; above it, a centred
         * popover. It is the width at which a popover stops being meaningfully narrower than the
         * screen it floats on.
         */
        const val NARROW_MAX_DP: Float = 900f

        /** How wide the menu popover is on a screen with room for one. */
        const val SHEET_WIDTH_DP: Float = 460f

        /**
         * How wide the session panel is, whatever is in it — see `OutofspaceHud.sessionPanel`.
         *
         * Set by its longest row, which is the menu button's own label; everything else is shorter
         * and the flight rows are deliberately kept under it.
         */
        const val SESSION_WIDTH_DP: Float = 400f

        /** How wide the readouts popover is: a column of key/value rows, not a row of buttons. */
        const val READOUTS_WIDTH_DP: Float = 560f

        /** One row of it, and the title bar above them — the two figures its height is built from. */
        const val SHEET_ROW_DP: Float = 26f
        const val SHEET_TITLE_DP: Float = 56f

        const val MATERIAL_COLUMN_WIDTH_DP: Float = 300f

        const val MIN_REFERENCE_WIDTH_DP: Float = 460f

        /** Below this much room under the head, in dp, the article's body is not drawn at all. */
        const val MIN_REFERENCE_HEIGHT_DP: Float = 80f
    }
}
