package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Furnace
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.Stockpile
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.POSITIVE_WEIGHT_LADDER
import org.emerge.demo.outofspace.world.TICK_LADDER
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.canStand
import org.emerge.demo.outofspace.world.Docking
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.machine.DockingPort
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.DirectedDeckMachine
import org.emerge.demo.outofspace.world.MachineSettings
import org.emerge.demo.outofspace.world.aimed
import org.emerge.demo.outofspace.world.toMachineSettings
import org.emerge.demo.outofspace.world.withSettings
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.starterWorld
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper
import org.emerge.sim.core.ecs.PipelineProfiler
import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * Owns the running world and the boundary between real time and sim time.
 *
 * Hosts talk to this and nothing deeper: hand it a frame delta, get back the state to draw. Frame
 * time is *accumulated*, never integrated — the sim only advances in whole ticks, so a 144 Hz desktop
 * and a 30 Hz phone reach identical worlds, and every determinism guarantee downstream stays true.
 */
class OutofspaceController(
    val cfg: OutofspaceConfig = OutofspaceConfig(),
    initial: VesselState = starterWorld(OutofspaceConfig().initialGrid),
) {
    private val stepper = TickStepper(cfg, initial, OutofspaceReducer)
    private val localPlayer = PlayerId(0)

    private var accumulator = 0f
    private val pending = ArrayList<Edit>()

    /** Tracks the grid growing under the indices held below — see [followFrame]. */
    private val frame = FrameShift(initial)

    /**
     * Whether the world is stopped.
     *
     * ⛔ **A paused game still runs its loop.** The ticks it runs are *frozen* ones — see
     * [OutofspaceReducer.freeze] — which apply the player's edits, advance the clock, and do nothing
     * else whatsoever. Two things fall out of that and both are the reason for it:
     *
     * A half-finished animation **finishes**. Every interpolation in the game measures against the
     * clock (see [org.emerge.demo.outofspace.world.Cadence]), so a clock that keeps moving lets a
     * packet complete the step it was part-way through and an overlay complete its fade, and then
     * settle — instead of freezing mid-slide, which is what a stopped world used to look like.
     *
     * The world can be **edited without time passing**. Placing a ghost or marking a machine for
     * demolition used to force a whole live tick, because that was the only way an edit could reach
     * the world; a click while stopped moved the game on by a tick of physics. Now it does not.
     */
    var paused: Boolean = false

    /**
     * The game-speed dial — how much sim time a second of real time buys.
     *
     * ⚠️ **It applies while [paused] too**, which is not the contradiction it sounds like. What a
     * pause stops is the passes; what the dial sets is how fast the clock turns, and the clock is
     * still turning. An animation half-way through when the player stopped the game was proceeding
     * at this rate, and it should go on proceeding at it: settle at a flat 1× instead and a world
     * paused at 0.25× visibly *speeds up* as it comes to rest, while one paused at 4× drags.
     */
    var speed: Float = 1f

    /** Steps the dial one notch along [SPEEDS], clamped at both ends. */
    fun nudgeSpeed(faster: Boolean) {
        val at = SPEEDS.indexOfFirst { it >= speed - 0.001f }.let { if (it < 0) SPEEDS.lastIndex else it }
        speed = SPEEDS[(at + if (faster) 1 else -1).coerceIn(SPEEDS.indices)]
    }

    /**
     * Ticks in which the world actually moved — what a player means by "tick".
     *
     * ⚠️ **Not `state.tick`**, which counts frozen ticks too because everything is stamped against
     * it and it must never go backwards or stand still. Left as the readout, it would climb while
     * the game was paused, which reads as time passing in a stopped world — the exact impression
     * frozen ticks exist to avoid.
     */
    var livedTicks: Long = initial.tick
        private set

    /** Optional profiler for per-phase tick analysis. Null unless [enableProfiling] is called. */
    var profiler: PipelineProfiler? = null

    /**
     * The settings the brush is stamping out, or null when it is stamping out a plain one.
     *
     * ⛔ **Picked up off the world, never assembled by hand** — see [grab]. This is what makes a
     * second furnace *the same* furnace as the first: its setpoint, its dwell, its wiring and the
     * way it faces, carried on the cursor from the one the player already tuned. It is the whole of
     * what the old C/V clipboard did, except that it is now the same act as choosing what to build,
     * so there is nothing to remember to paste afterwards.
     *
     * ⚠️ **Always agrees with [brush], and the setter is what makes that true.** A [MachineSettings]
     * belongs to a [DeckMachineKind] — a furnace's dwell means nothing to a pump — so picking a
     * different building out of the palette drops it. Changing the *material* does not, and must
     * not: "the same machine, in titanium" is the exact thing this is for.
     */
    var stamped: MachineSettings? = null
        private set

    /**
     * What the player is about to place, or **null when they have not chosen** — see [Tool.Build].
     *
     * ⛔ **Null is a state the player is meant to be in**, not an absence to be defaulted away. It is
     * the build tool with its palette open and nothing picked out of it yet, which is where ESC
     * leaves them on the way out of a placement and where C leaves them when there was nothing under
     * the inspector to copy. A click in that state reads a tile exactly as the inspector would —
     * see [apply] — so the palette is somewhere you can stand and look around rather than a mode you
     * have to leave to ask a question.
     */
    var brush: Brush? = null
        set(value) {
            // The settings are a *kind's* settings, so they cannot survive a change of kind. Dropped
            // here rather than at each of the several call sites, because every one of them would
            // have to remember and the one that forgot would paste a furnace's dwell into a pump.
            if ((value as? Brush.Building)?.kind != stamped?.kind) stamped = null
            field = value
        }

    var brushFacing: Direction = Direction.Right

    /**
     * What everything placed from now on is to be built out of, or null for each kind's default.
     *
     * ⛔ **On the controller and not on [brush], though [Brush] can carry one.** `Brush.ALL` is a
     * list of prototypes and both the build menu's selected-highlight (`option == brush`) and
     * [cycleBrush]'s `indexOf` compare against them, so a brush cannot carry one — it is a shape and
     * the shape is what the menu is a menu of. The substance is stamped onto the [Edit] at the point
     * of use.
     *
     * ⚠️ **Sticky until changed, deliberately.** A material is a decision about a batch of building,
     * not about one click; it survives changing brush, changing facing and switching between
     * clicking and dragging. It is *not* saved, because it is a state of the player rather than of
     * the vessel.
     *
     * ⛔ **Null means the player has not chosen and so cannot build** — it does not mean "whatever
     * the game thinks a rail is usually made of", because there is no such thing. Every path that
     * raises an edit checks it, and declining is the honest answer: with nothing loose aboard there
     * is nothing to build *out of*, and a ghost laid anyway would be a site no delivery could ever
     * satisfy.
     */
    var buildMaterial: Species? = null

    /**
     * What a left-click does — see [Tool].
     *
     * Defaults to [Tool.Inspect]: the tool that changes nothing is the one a player should be
     * holding when they have not chosen, and it is the only one that answers "what is this?".
     */
    var tool: Tool = Tool.Inspect

    /** Which layer the delete tool takes off. Only read while [tool] is [Tool.Delete]. */
    var deleteLayer: DeleteLayer = DeleteLayer.Top

    /**
     * Which conduit the cut tool severs. Only read while [tool] is [Tool.Cut].
     *
     * ⚠️ One layer, deliberately. Rail and wire share a tile — they are the one pair that may — so a
     * cut that took both would quietly break a signal network while the player was tidying track.
     */
    var cutConduit: Conduit = Conduit.Rail

    /** Which overlay the world is being viewed through. A view preference, so it lives here. */
    var overlay: Overlay = Overlay.None

    /**
     * Which way the debug engine is being held, −1, 0 or 1 per axis — see [Edit.Thrust].
     *
     * **Held state rather than queued edits**, and that is the difference between a throttle and a
     * trigger. A host sets this when a key goes down and clears it when the key comes up; the
     * controller turns it into one [Edit.Thrust] per tick for as long as it is held, so the burn
     * lasts exactly as long as the finger and is the same length whether the display is running at
     * 30 Hz or 144. Pushing an edit per key *event* would give one tick of thrust per press, and
     * pushing one per frame would make a fast machine a faster ship.
     */
    var thrustX: Int = 0
    var thrustY: Int = 0

    /**
     * The tile the debug bellows is being held over, or -1 — see [Edit.Inject].
     *
     * Held state for [thrustX]'s reason, and it carries a *tile* rather than a boolean because the
     * thing being held is a pointer: a host sets this to whatever is under the cursor each frame, so
     * dragging while injecting lays gas along the drag. One edit per tick regardless, so a 144 Hz
     * machine and a 30 Hz one fill a room at the same rate.
     */
    var injectTile: TileIndex = TileIndex.NONE

    /**
     * Which keys the pilot is holding, as an [InputKey] bitmask — held state, for [thrustX]'s reason
     * exactly, except that here it is a *level* and not a per-tick event. It is passed straight
     * through to the reducer rather than turned into edits; see [OutofspaceInput.heldKeys].
     *
     * Only read in [Mode.Flight]. In [Mode.Build] the same physical keys pan the camera and pick
     * brushes, which is why the mode exists at all.
     */
    var heldKeys: Int = 0

    /**
     * Build or fly.
     *
     * The keyboard is not big enough for both, and that is not a UI problem to be designed around —
     * it is the honest shape of the thing. While you are building, WASD pans and the number row picks
     * a brush; while you are flying, those same keys have to reach the buttons you built. A vessel
     * that could be edited mid-burn would also need every edit to be safe mid-burn, which is a much
     * larger promise than this needs to make.
     */
    var mode: Mode = Mode.Build
        set(value) {
            // Letting go of everything on the way out, so a key held as the mode flips does not stay
            // held forever in a mode that cannot see it come up.
            if (value != field) heldKeys = 0
            field = value
        }

    /**
     * The machine the machine panels are looking at, or -1. Cleared whenever it stops being a machine.
     *
     * ⚠️ **Not the wiring panel's private business, though it used to be.** Selection was set only
     * under [Tool.Wire], which was fine while wiring was the only thing a selected machine was *for*.
     * It stopped being fine the moment a second panel wanted one: the storage lock stands down while
     * the wire tool is out — as it must, or two panels fight over the same corner — so it could never
     * appear at all. Built, saved, tested, and unreachable. See [select].
     */
    var selected: TileIndex = TileIndex.NONE
        private set

    val state: VesselState get() = stepper.state
    val tick: Long get() = stepper.state.tick

    /**
     * Puts the current brush on [tile], out of the current material — **or settles onto whatever is
     * already standing there, if it is the same kind of thing.**
     *
     * ⛔ **Does nothing at all when no material is chosen, or no brush.** See [buildMaterial]: an
     * edit with no substance is not a placement, and see [brush]: the palette with nothing picked
     * out of it is a state the player stands in rather than one that places a default.
     *
     * ### Clicking a building you already have
     *
     * A stamped brush laid over a building of its own kind does not demolish it and does not refuse:
     * it **hands over the settings and nothing else**, which is the paste half of the old clipboard
     * arriving as the natural consequence of the copy half rather than as a second key to remember.
     * The material is emphatically not part of it — that machine is made of what it is made of, and
     * a click that quietly recast a titanium furnace in iron because the cursor was carrying iron
     * would be a demolition wearing a placement's clothes.
     *
     * ⚠️ **The facing comes off the cursor, not off the stamp**, and that is what makes this a way of
     * *turning* things: R turns the brush, the click hands the new facing over, and a machine the
     * player is standing in front of swings round. Turning by copying is a strange sentence, but the
     * gesture is exactly the one a player already knows — pick up, aim, click — and it means the
     * rotate key does the same thing to a thing on the deck as it does to a thing on the cursor.
     */
    fun place(tile: TileIndex) {
        val material = buildMaterial ?: return
        val brush = brush ?: return
        stampOnto(tile, brush)?.let { pending.add(it); return }
        // The stamp rides on the placement rather than following it — see [Edit.Place.settings]. It
        // is passed unaimed: the edit already carries the facing, and the reducer is where the two
        // are reconciled, so there is exactly one place that can get it wrong.
        pending.add(Edit.Place(tile, brush, brushFacing, material, stamped))
    }

    /**
     * The edit a stamped click on [tile] would raise instead of a placement, or null if this click is
     * an ordinary placement after all.
     *
     * Shared by [place] and [planAt] on purpose: the cursor draws a settings hand-over differently
     * from a build, and the two must never disagree about which of them is about to happen — the
     * same argument [BuildPlan.allowed] is held to.
     */
    private fun stampOnto(tile: TileIndex, brush: Brush): Edit.ReplaceDeckMachine? {
        val settings = stamped ?: return null
        if (brush !is Brush.Building) return null
        val standing = state.machineCovering(tile) ?: return null
        if (standing.kind != settings.kind) return null
        // ⚠️ **The machine's own anchor, not the tile the pointer is over.** The reducer resolves
        // either through `originAt`, so this changes nothing about what happens — but it is also
        // what the *cursor* is drawn from, and there it changes everything: a hand-over is aimed at
        // an object rather than at a square, so the preview belongs over the whole of the machine it
        // is about to re-tune. Drawn off the pointer instead, a click on a warehouse's top-left
        // corner previewed a warehouse hanging off the corner of the one already there, which reads
        // as an overlapping placement — the one thing this click is not.
        return Edit.ReplaceDeckMachine(standing.center, standing.withSettings(settings.aimed(brushFacing)))
    }

    /**
     * What a click on [tile] would put down, for the renderer to draw under the cursor — see
     * [BuildPlan].
     *
     * Null when there is nothing to preview: another tool is out, the player is flying, or the
     * pointer is off the grid. **Not** null when the placement would be refused — a refusal is the
     * thing most worth showing, and it is shown by [BuildPlan.allowed] rather than by an absence.
     *
     * ⚠️ **Asked once a frame, and answers off the settled state.** It reads the world and changes
     * nothing, so a host may call it as often as it likes; what it must not do is call it and then
     * assume the answer still holds a tick later, because by then the player may have built
     * something. The reducer asks the same question again at the moment it matters.
     */
    fun planAt(tile: TileIndex): BuildPlan? {
        if (mode != Mode.Build || tool != Tool.Build || tile == TileIndex.NONE) return null
        val brush = brush ?: return null
        // ⚠️ **Asked first, because it is a different answer and not a softer one.** A stamped brush
        // over a machine of its own kind would fail `canStand` — something is standing there, which
        // is the point — and drawing that as a refusal would tell the player the exact opposite of
        // what the click is about to do.
        // ⛔ **`it.tile`, not `tile`** — the edit has already resolved the pointer onto the machine's
        // anchor, and the preview snaps there with it. See [stampOnto].
        stampOnto(tile, brush)?.let { return BuildPlan(it.tile, brush, brushFacing, allowed = true, settingsOnly = true) }
        val allowed = buildMaterial != null && when (brush) {
            // Track goes anywhere there is grid: the layers no longer exclude each other, and a run
            // drawn over a run it already has is a no-op rather than a mistake — see `layConduit`.
            is Brush.Run -> true
            is Brush.Building -> state.canStand(brush.kind, tile, brushFacing)
        }
        return BuildPlan(tile, brush, brushFacing, allowed)
    }

    /**
     * The tile the current drag last reached, or -1 when nothing is being dragged.
     *
     * Conduit is laid by dragging, and connection follows the gesture rather than the geometry: two
     * runs can touch without joining, so a line is only a line where the player actually drew one.
     */
    private var dragFrom: TileIndex = TileIndex.NONE

    /**
     * Points the machine panels at whatever is under [tile], or at nothing if that is bare deck.
     *
     * Resolves through [VesselState.occupancy] so that clicking any part of a five-tile furnace
     * selects the furnace rather than nothing — a machine is selected by touching it anywhere, which
     * is the only rule a player would guess.
     */
    fun select(tile: TileIndex) {
        selected = state.occupancy[tile]
    }

    /**
     * The exact tile the inspector was pointed at, or -1.
     *
     * ⚠️ **Not [selected], and the difference is the whole of the cycling rule.** Selection resolves
     * through occupancy, so every tile of a nine-tile furnace answers with the furnace's centre —
     * which is right for the machine panels and useless here, because two clicks on two *different*
     * squares of that furnace would then look like two clicks on the same place and step the layer.
     * This is where the finger actually went.
     */
    var inspectTile: TileIndex = TileIndex.NONE
        private set

    /** Which layer of [inspectTile] the inspector is reading. Meaningless while [inspectTile] is -1. */
    var inspectLayer: InspectLayer = InspectLayer.Atmosphere
        private set

    /**
     * Points the inspector at [tile], or steps to its next readable layer if it is already there.
     *
     * A first click reads the topmost layer that has anything to say — the building if there is one,
     * failing that the track, and in the end the air, which is always readable. Clicking again
     * peels down through the rest and wraps. A tile with only one layer is therefore stable under
     * repeated clicks, which is what a player clicking twice by accident expects.
     */
    fun inspect(tile: TileIndex) {
        val layers = inspectableLayers(state, tile)
        if (layers.isEmpty()) {
            inspectTile = TileIndex.NONE
            return
        }
        val at = if (tile == inspectTile) layers.indexOf(inspectLayer) else -1
        inspectTile = tile
        // -1 covers both a new tile and a layer that has since stopped existing under the old one
        // (the belt was deleted, the room was sealed): either way, start at the top again.
        inspectLayer = if (at < 0) layers[0] else layers[wrap(at + 1, layers.size)]
    }

    /**
     * The species the reference panel is open on, or null when it is shut.
     *
     * ⛔ **Not a fact about the world, and deliberately not saved.** Which article the player is
     * reading is the same kind of thing as which inspector section they have folded — see
     * `OutofspaceHud.section`. It lives on the controller rather than in the HUD only because the
     * *history* below has rules worth testing without a renderer.
     */
    var wikiSpecies: Species? = null
        private set

    /**
     * Where the reader has been, oldest first, and where in it they are standing.
     *
     * A plain stack with a cursor, which is what a browser's back and forward are: [back] and
     * [forward] move the cursor, and opening something new from anywhere but the end **truncates**
     * the tail. Anything else and forward would walk into a branch the player never took.
     */
    private val wikiHistory = ArrayList<Species>()
    private var wikiCursor = -1

    /** Opens the reference panel on [species], pushing it onto the history. */
    fun openWiki(species: Species) {
        // A repeat of what is already showing is not a navigation. Clicking IRON in the article's
        // own composition rows twice would otherwise fill the history with the same page, and back
        // would then appear to do nothing several times over.
        if (wikiSpecies == species) return
        while (wikiHistory.size > wikiCursor + 1) wikiHistory.removeAt(wikiHistory.size - 1)
        wikiHistory.add(species)
        wikiCursor = wikiHistory.size - 1
        wikiSpecies = species
    }

    /**
     * Shuts the reference panel, **and forgets where it has been**.
     *
     * The history is a trail through one sitting's reading, not a record of everything the player
     * has ever looked up. Keeping it across a close would mean a panel that opens with a live back
     * button pointing at something read half an hour ago.
     */
    fun closeWiki() {
        wikiSpecies = null
        wikiHistory.clear()
        wikiCursor = -1
    }

    val canWikiBack: Boolean get() = wikiCursor > 0
    val canWikiForward: Boolean get() = wikiCursor in 0 until wikiHistory.size - 1

    fun wikiBack() {
        if (!canWikiBack) return
        wikiCursor--
        wikiSpecies = wikiHistory[wikiCursor]
    }

    fun wikiForward() {
        if (!canWikiForward) return
        wikiCursor++
        wikiSpecies = wikiHistory[wikiCursor]
    }

    /** Points the inspector straight at one layer, for a panel's own layer buttons and for scripts. */
    fun inspect(tile: TileIndex, layer: InspectLayer) {
        if (tile == TileIndex.NONE) { inspectTile = TileIndex.NONE; return }
        inspectTile = tile
        inspectLayer = layer
    }

    /**
     * Left-click behaviour, which depends on the tool.
     *
     * ⚠️ **Selection happens whatever the tool is**, and then the tool does its own work. Every panel
     * that shows the settings of one machine needs a way to say *which* machine, and hanging that off
     * a single tool meant a panel could only exist for that tool — which is precisely how the storage
     * lock came to be unreachable. Clicking bare deck selects nothing, so a click away dismisses.
     */
    fun apply(tile: TileIndex) {
        select(tile)
        when (tool) {
            // ⛔ **With nothing picked out of the palette, a click *reads* the tile.** The palette
            // stays open — this is not a slip back into the inspector, it is the build tool with
            // its hands empty — and the two things a player does at that moment are the same thing:
            // "what is this?" and "give me one of those" (C, see [grab]) both start with a click on
            // the machine. A click that did nothing at all would make the palette a room with the
            // lights off.
            Tool.Build -> if (brush == null) inspect(tile) else {
                place(tile)
                if (brush is Brush.Run) dragFrom = tile
            }
            Tool.Inspect -> inspect(tile)
            Tool.Delete -> removeAt(tile)
            // Calling a mark off drags for the same reason making one does: a player condemns a
            // stretch of belt in one stroke, and having to click every tile of it back is the tool
            // being harder to use than the mistake it exists to undo.
            Tool.Cancel -> { cancelAt(tile); dragFrom = tile }
            // A drag, like building, and the exact inverse of it: what the stroke severs is the
            // *edges* it draws, so a click on its own has nothing to cut and only arms the drag.
            Tool.Cut -> dragFrom = tile
            // Nothing: the bellows is a *hold*, so it is driven by [injectTile] and a click that
            // pushed one edit here would inject twice on the tick the button went down.
            Tool.Inject, Tool.InjectWater -> {}
        }
    }

    /**
     * Continues a conduit drag to [tile], laying and joining every tile along the way.
     *
     * The path is stepped out rather than trusting the pointer to visit every tile: a fast drag skips
     * tiles, and a run with a hole in it is not a run. Horizontal first, then vertical — an L, which
     * is both what the player drew if they dragged along an axis and a predictable answer if they
     * did not.
     */
    fun dragTo(tile: TileIndex) {
        if (dragFrom == TileIndex.NONE || tile == dragFrom) return
        if (tool != Tool.Build && tool != Tool.Cut && tool != Tool.Cancel) return
        val grid = state.grid
        if (tile == TileIndex.NONE) return
        var at = dragFrom
        while (at != tile) {
            val dir = when {
                grid.xOf(at) != grid.xOf(tile) ->
                    if (grid.xOf(tile) > grid.xOf(at)) Direction.Right else Direction.Left
                else -> if (grid.yOf(tile) > grid.yOf(at)) Direction.Down else Direction.Up
            }
            val next = grid.neighbour(at, dir)
            if (next == TileIndex.NONE) break
            // ⚠️ **Cutting steps the path for the same reason laying does, and needs it more.** A run
            // with a hole in it is not a run; a run with one tile the drag skipped over is still a
            // run, and looks cut.
            if (tool == Tool.Cut) {
                cutAt(at, dir)
            } else if (tool == Tool.Cancel) {
                // The tile *entered*, not the one left: the click that armed the drag already called
                // off `dragFrom`, and cancelling it a second time would be a wasted edit on every
                // step of every stroke.
                cancelAt(next)
            } else {
                // Same refusal as [place], and it has to be here too: a drag is its own edit and
                // does not go through the brush. An empty palette is one of them now — a drag with
                // nothing picked lays nothing, exactly as the click it started with placed nothing.
                val material = buildMaterial ?: return
                val held = brush ?: return
                place(next)
                pending.add(
                    Edit.Lay(at, next, (held as? Brush.Run)?.conduit ?: Conduit.Rail, material),
                )
            }
            at = next
        }
        dragFrom = at
    }

    /**
     * Severs the join between [tile] and its neighbour in [dir] on [conduit], leaving both tiles and
     * every one of their *other* joins exactly where they are.
     *
     * ⛔ **A cut is an anti-edge, not an isolation.** The stroke is read the same way laying reads
     * it — the edges the player drew, and only those — so cutting along the middle of a junction
     * parts the two runs the stroke stepped between and leaves the third arm joined.
     */
    fun cutAt(tile: TileIndex, dir: Direction, conduit: Conduit = cutConduit) {
        if (tile == TileIndex.NONE) return
        val next = state.grid.neighbour(tile, dir)
        if (next != TileIndex.NONE) pending.add(Edit.Cut(tile, next, conduit))
    }

    /** Ends a conduit drag. The next click starts a new one, unjoined to this. */
    fun endDrag() {
        dragFrom = TileIndex.NONE
    }

    fun wire(tile: TileIndex, action: Action, slot: Int, trigger: Trigger?) =
        pending.add(Edit.Wire(tile, action, slot, trigger))

    /** Binds a button to [key]. */
    fun bindKey(tile: TileIndex, key: InputKey) = pending.add(Edit.BindKey(tile, key))

    /** Locks a warehouse onto what it holds most of, or unlocks it — see [Edit.LockStoragePercent]. */
    fun lockStoragePercent(storage: Storage, minPercent: Int?) = pending.add(Edit.LockStoragePercent(storage.center, minPercent))

    // ── Trade ────────────────────────────────────────────────────────────────

    fun dock(port: DockingPort) = pending.add(Edit.Dock(port.center))
    fun undock() = pending.add(Edit.Undock)
    fun setDockedThrust(allowed: Boolean) = pending.add(Edit.SetDockedThrust(allowed))

    /**
     * One press of `>` or `<` on [species] — see [DockingPort.nudged], where the rule lives.
     *
     * ⚠️ **The transitions are on the machine, not here.** They are a fact about what the book can
     * say rather than about how the player says it, and putting them here would have meant the
     * counter and any other caller could disagree about what `<<` then `>` means.
     */
    fun nudge(port: DockingPort, species: Species, by: Long) = retune(port.nudged(species, by))

    /** The same press on the ore row, which has no buy side. */
    fun nudgeOre(port: DockingPort, by: Long) = retune(port.nudgedOre(by))

    /** `>>` on [species]: sell it with no bound, or stand down. */
    fun toggleSellForever(port: DockingPort, species: Species) =
        retune(port.unbounded(species, -DockingPort.ENDLESS))

    /** `<<` on [species]: keep the ship supplied with it, or stand down. */
    fun toggleBuyForever(port: DockingPort, species: Species) =
        retune(port.unbounded(species, DockingPort.ENDLESS))

    /** `>>` on the ore row. */
    fun toggleSellOreForever(port: DockingPort) = retune(port.unboundedOre())

    private fun retune(port: DockingPort) =
        pending.add(Edit.TuneDockingPort(port.center, orders = port.orders, ore = port.ore))



    /** Whether the port at [tile] is lined up with a berth it could take right now. */
    fun berthInReach(port: DockingPort): Boolean {
        val s = state
        if (s.assembly.isHeld(port.center.index) || s.berth != null) return false
        for (body in s.bodies) {
            val economy = body.station ?: continue
            for (i in economy.docks.indices) {
                if (Docking.canDock(s.grid, port, s.pose, body, i)) return true
            }
        }
        return false
    }

    /** The station the vessel is berthed at, or null. */
    val dockedStation: RigidBody?
        get() = state.berth?.let { weld -> state.memberBody(weld.childId) }
    fun lockStorageSpecies(storage: Storage, species: Species?) = pending.add(Edit.LockStorageSpecies(storage.center, species))
    fun toggleStorageAutoLock(storage: Storage) = pending.add(Edit.TuneStorage(
        storage.center,
        storage.filter?.species,
        storage.filter?.minPercent,
        !storage.autoLock,
        storage.autoUnlock,
    ))
    fun toggleStorageAutoUnlock(storage: Storage) = pending.add(Edit.TuneStorage(
        storage.center,
        storage.filter?.species,
        storage.filter?.minPercent,
        storage.autoLock,
        !storage.autoUnlock,
    ))

    /**
     * Takes the build tool out, holding a copy of **whatever layer of whatever tile the inspector is
     * reading** — material, settings, facing and all. What **C** does.
     *
     * ### The one gesture
     *
     * This replaced a clipboard: **C** captured a machine's settings and **V** stamped them onto
     * another one, which is two keys, an invisible holding pen, and a rule ("only onto the same kind
     * of machine") that could only be discovered by breaking it. C keeps the key it had and does the
     * whole job with it. Every automation game the player has already played spells the same idea as
     * one key that hands you the thing you are pointing at, so that is what this is: point at a
     * furnace, press C, and you are holding a furnace —
     * tuned the way that one is, made of what that one is made of, aimed the way that one is aimed.
     * Putting it down somewhere empty builds one. Putting it down on another furnace tunes *that*
     * one — see [place]. There is nothing else to learn.
     *
     * ⚠️ **It reads the inspector's layer, not the tile.** A tile is not one thing, and the inspector
     * has already made the player say which of its things they mean — see [InspectLayer]. So C on
     * the DECK layer hands over the building and B on the RAIL layer hands over a length of track in
     * the metal that track is made of, and neither has to guess.
     *
     * With nothing under the inspector it still takes the build tool out, with the palette empty:
     * "build something" is what the key means even when there is nothing to copy, and a key that did
     * nothing at all would read as broken. Returns whether anything was actually picked up.
     */
    fun grab(): Boolean {
        tool = Tool.Build
        val tile = inspectTile
        if (tile == TileIndex.NONE) return false
        val conduit = when (inspectLayer) {
            InspectLayer.Deck -> {
                val machine = state.machineCovering(tile) ?: return false
                // ⚠️ **Through the setter, and before the settings are written.** Assigning the brush
                // is what clears a stamp left over from the last thing grabbed; doing it afterwards
                // would drop the settings this call just took.
                brush = Brush.Building(machine.kind)
                stamped = machine.toMachineSettings()
                buildMaterial = state.deck.materialOf(machine)
                (machine as? DirectedDeckMachine)?.let { brushFacing = it.facing }
                return true
            }
            InspectLayer.Rail -> Conduit.Rail
            InspectLayer.Pipe -> Conduit.Pipe
            InspectLayer.Wire -> Conduit.Signal
            InspectLayer.Power -> Conduit.Power
            // There is no brush for a room. The air is the one layer the inspector always offers,
            // so this is the case a player reaches by pressing C on bare deck, and the honest answer
            // is the empty palette they are now holding.
            InspectLayer.Atmosphere -> return false
        }
        val material = state.conduits.materialAt(conduit, tile) ?: return false
        brush = Brush.Run(conduit)
        buildMaterial = material
        return true
    }

    /**
     * Steps one rung out of wherever the player is standing, or reports that they are already at the
     * top — see [Tool] and [brush] for what the rungs are.
     *
     * ### One key, one ladder
     *
     * ESC used to mean "clear the selection", which is one useful thing out of the five or six a
     * player wants when they press it. What they actually mean is *back*: out of the thing I am
     * holding, out of the tile I was reading, out of the game. Those are nested, so they are a
     * ladder and not a list, and the whole of this method is saying which rung is below which:
     *
     * 1. holding a brush (or a destructive tool) — put it down
     * 2. the build palette, open and empty — close it, back to reading
     * 3. a tile under the inspector — stop reading it
     * 4. nothing — *this returns false*, and the caller opens the menu
     *
     * ⚠️ **Rung 1 lands on rung 3, not rung 2**, when what is being put down is DELETE, CANCEL or
     * CUT: those are not places you were building from, so stepping out of them into a build palette
     * would be a rung the player never climbed. The tile they were pointed at is kept, because it is
     * what they were looking at the whole time.
     *
     * ⛔ **The menu is not this method's business.** It is a sheet, it dims the screen, and it is the
     * one rung that is view state rather than tool state — so this reports the ladder is exhausted
     * and lets whoever owns the sheets open it. See `OutofspaceHud.escape`.
     */
    fun escape(): Boolean {
        // Flight is not on the ladder — it is the other half of the game, and the way out of it is
        // the same key because there is always a way out. Taken first so a pilot never has to press
        // it twice.
        if (mode == Mode.Flight) { mode = Mode.Build; return true }
        when (tool) {
            Tool.Build -> {
                // Put the brush down but keep the palette open: the player asked to stop placing
                // *this*, which is not the same as asking to stop building.
                if (brush != null) { brush = null; return true }
                tool = Tool.Inspect
                return true
            }
            // Every tool that is not reading the world is one rung: down to the inspector, still
            // pointed at whatever tile it was pointed at.
            Tool.Delete, Tool.Cancel, Tool.Cut, Tool.Inject, Tool.InjectWater -> {
                tool = Tool.Inspect
                return true
            }
            Tool.Inspect -> {
                if (inspectTile == TileIndex.NONE && selected == TileIndex.NONE) return false
                select(TileIndex.NONE)
                inspect(TileIndex.NONE)
                return true
            }
        }
    }

    /**
     * Steps a locked warehouse's threshold through [SpeciesFilter.PERCENTS], wrapping.
     *
     * Only meaningful once locked: an unlocked tank has no threshold to move, and the panel offers
     * the lock button instead.
     */
    fun cycleStorageFilterPercent(storage: Storage, delta: Int) {
        val filter = storage.filter ?: return
        val all = SpeciesFilter.PERCENTS
        val at = all.indexOf(filter.minPercent).let { if (it < 0) all.indexOf(SpeciesFilter.MAX_PERCENT) else it }
        lockStoragePercent(storage, all[((at + delta) % all.size + all.size) % all.size])
    }
    fun toggleStorageFilterSpecies(storage: Storage) {
        val filter = storage.filter ?: return
        // Whatever it is holding most of. A tank with nothing in it has no
        // dominant species and so cannot be locked — the panel says as much
        // rather than this failing quietly, but it must also be true here:
        // the edit queue is not the only way in.
        val store = bufferTile(state.grid, storage, storage.center, BufferRole.Inside)
        val held = store?.let { state.buffers.resourceAt(it) }
        // ⚠️ **Re-locking clears the species requirement, leaving any purity requirement untouched.
        lockStorageSpecies(storage, if (filter.species == null) held?.dominant else null )
    }

    /**
     * Steps a decomposer's setpoint through [Furnace.SETPOINTS], wrapping.
     *
     * Wrapping, and one direction per tap, because that is what the storage threshold does and a
     * second interaction idiom for the same shape of choice would be one to learn for no reason.
     */
    fun cycleDecomposerTemperature(tile: TileIndex, delta: Int) {
        val m = state.machineCovering(tile) as? Furnace ?: return
        val all = Furnace.SETPOINTS
        // `indexOf` misses a setpoint that came from a save written before this ladder existed, or
        // by hand. Falling to the nearest rung at or below it keeps the tap meaningful instead of
        // silently jumping to the coldest.
        val at = all.indexOf(m.setTemperature).let { if (it >= 0) it else all.indexOfLast { rung -> rung <= m.setTemperature }.coerceAtLeast(0) }
        pending.add(Edit.TuneDecomposer(tile, all[wrap(at + delta, all.size)], m.dwellTicks))
    }

    /** Turns the autopilot on or off — see [org.emerge.demo.outofspace.world.Sas]. */
    fun toggleSas() = pending.add(Edit.SetSas(!state.sas))

    /**
     * Switches a thruster between flying the ship and answering its wire — see [ThrusterControl].
     *
     * Reads the current mode off the machine and sends the *other* one as an absolute value, so the
     * edit says what the player asked for rather than "whatever the opposite turns out to be".
     */
    fun toggleThrusterControl(tile: TileIndex) {
        val m = state.machineCovering(tile) as? Thruster ?: return
        pending.add(Edit.SetThrusterControl(tile, m.control.next))
    }

    /** Steps a decomposer's residence time through [Furnace.DWELLS], wrapping. */
    fun cycleDecomposerDwell(tile: TileIndex, delta: Int) {
        val m = state.machineCovering(tile) as? Furnace ?: return
        val all = Furnace.DWELLS
        val at = all.indexOf(m.dwellTicks).let { if (it >= 0) it else all.indexOfLast { rung -> rung <= m.dwellTicks }.coerceAtLeast(0) }
        pending.add(Edit.TuneDecomposer(tile, m.setTemperature, all[wrap(at + delta, all.size)]))
    }

    /** Index [i] brought into `0 until size`, for negative deltas as well as positive ones. */
    private fun wrap(i: Int, size: Int): Int = ((i % size) + size) % size

    /** Cycles which key a button answers to. */
    fun cycleInputKey(tile: TileIndex, delta: Int) {
        // On the deck now, like every other transmitter. Asking the machine list would find
        // nothing and the key would simply stop cycling, with no error to say why.
        val current = state.machineCovering(tile) as? WireButton ?: return
        val all = InputKey.ALL
        val next = all[((all.indexOf(current.key) + delta) % all.size + all.size) % all.size]
        bindKey(tile, next)
    }

    /** Cycles a trigger between the constant and the wire under the machine. */
    fun cycleTriggerSource(tile: TileIndex, action: Action, slot: Int, delta: Int) {
        val current = state.machineCovering(tile)?.wiring?.triggers(action)?.getOrNull(slot)
        val currentDeck = state.machineCovering(tile)?.wiring?.triggers(action)?.getOrNull(slot)
        val all = SignalSource.ALL

        if (current != null) {
            val next = all[((all.indexOf(current.source) + delta) % all.size + all.size) % all.size]
            wire(tile, action, slot, current.copy(source = next))
        }
        if (currentDeck != null) {
            val next = all[((all.indexOf(currentDeck.source) + delta) % all.size + all.size) % all.size]
            wire(tile, action, slot, currentDeck.copy(source = next))
        }
    }

    /**
     * Flips a trigger between counting for and counting against.
     *
     * What used to be a ladder of weights is one bit now — see [Trigger]. A toggle needs no `delta`
     * and no wrapping, which is the whole of what the simplification bought the UI.
     */
    fun toggleTriggerNegated(tile: TileIndex, action: Action, slot: Int) {
        val current = state.machineCovering(tile)?.wiring?.triggers(action)?.getOrNull(slot) ?: return
        wire(tile, action, slot, current.copy(negated = !current.negated))
    }

    fun invertSensorThreshold(tile: TileIndex) {
        val m = state.machineCovering(tile) as? Sensor ?: return
        pending.add(Edit.TuneSensor(tile, -m.threshold, m.delay, m.release))
    }

    fun cycleSensorThreshold(tile: TileIndex, delta: Int) {
        val m = state.machineCovering(tile) as? Sensor ?: return

        val sign = if (m.threshold.sign == 0) 1 else m.threshold.sign
        val at = POSITIVE_WEIGHT_LADDER.indexOf(m.threshold.absoluteValue).let { if (it < 0) 0 else it }
        val next = POSITIVE_WEIGHT_LADDER[((at + delta) % POSITIVE_WEIGHT_LADDER.size + POSITIVE_WEIGHT_LADDER.size) % POSITIVE_WEIGHT_LADDER.size]
        pending.add(Edit.TuneSensor(tile, next*sign, m.delay, m.release))
    }

    fun cycleSensorDelay(tile: TileIndex, delta: Int) {
        val m = state.machineCovering(tile) as? Sensor ?: return

        val at = TICK_LADDER.indexOf(m.delay).let { if (it < 0) 0 else it }
        val next = TICK_LADDER[((at + delta) % TICK_LADDER.size + TICK_LADDER.size) % TICK_LADDER.size]
        pending.add(Edit.TuneSensor(tile, m.threshold, next, m.release))
    }

    fun cycleSensorRelease(tile: TileIndex, delta: Int) {
        val m = state.machineCovering(tile) as? Sensor ?: return

        val at = TICK_LADDER.indexOf(m.release).let { if (it < 0) 0 else it }
        val next = TICK_LADDER[((at + delta) % TICK_LADDER.size + TICK_LADDER.size) % TICK_LADDER.size]
        pending.add(Edit.TuneSensor(tile, m.threshold, m.delay, next))
    }

    /** Drops a rock centred on ([x], [y]) — the stand-in for capture, see [Edit.DropRock]. */
    fun dropRock(x: Float, y: Float) = pending.add(Edit.DropRock(x, y))

    fun rotate(tile: TileIndex) = pending.add(Edit.Rotate(tile))

    /**
     * Queues the grid back to the ship plus its pad. [followFrame] carries the selection across it.
     */
    fun fit() = pending.add(Edit.Fit)

    /** Calls off a deconstruction on every layer of a tile — see [Edit.Cancel]. */
    fun cancelAt(tile: TileIndex) = pending.add(Edit.Cancel(tile))

    /** Takes [deleteLayer] off a tile. Named explicitly by callers that mean a specific layer. */
    fun removeAt(tile: TileIndex, layer: DeleteLayer = deleteLayer) {
        if (tile == selected) selected = TileIndex.NONE
        pending.add(Edit.Remove(tile, layer))
    }

    /**
     * What a tool's own key does: **take it out, or aim it one notch further if it is already out.**
     *
     * ### A key each, instead of a lottery
     *
     * The tools used to be reached by cycling — one key stepped through all seven of them and a
     * second stepped through whichever sub-target the current one happened to have. That is one key
     * to learn and a lottery to use: reaching CUT from BUILD was four presses, and the count changed
     * every time a tool was added. Now each tool has a key, and the same key aims it.
     *
     * ⛔ **The opening press does not advance the aim, and that is the whole of the rule.** Advance
     * on the way in and DELETE could never be left on TOP, CUT could never be left on RAIL, and —
     * worst — BUILD could never be left holding nothing, which is a state the ESC ladder puts the
     * player in and a state a click reads a tile from. Open, then cycle, keeps every state reachable
     * from the keyboard; the cost is one extra press to reach the second rung, which is what the old
     * scheme cost anyway once the tool was out.
     *
     * ⚠️ **[Tool.Cancel] has no aim**, so its key simply takes it out and a second press does
     * nothing. That is not a gap to be filled: calling off a deconstruction is blind on purpose —
     * see [Edit.Cancel] — so there is nothing to point it at.
     */
    fun reachFor(want: Tool) {
        if (tool != want) return openTool(want)
        when (want) {
            Tool.Build -> cycleBrush(1)
            Tool.Delete -> cycleDeleteLayer(1)
            Tool.Cut -> cycleCutConduit(1)
            // The bellows and the tap are two tools rather than one with a switch, so the debug key
            // steps between them exactly as a sub-target would. They have no aim of their own.
            Tool.Inject -> tool = Tool.InjectWater
            Tool.InjectWater -> tool = Tool.Inject
            // Nothing to aim: see this method's note.
            Tool.Cancel, Tool.Inspect -> {}
        }
    }

    /**
     * Takes [want] out **without aiming it** — the opening half of [reachFor], on its own.
     *
     * Separate because two callers want the opening and not the cycle: [reachFor] itself when the
     * tool is not already out, and the number row, which names the brush it wants and would be
     * undone by a palette step on the way in. Idempotent, so a caller that does not know which tool
     * is out can say what it wants and get it.
     */
    fun openTool(want: Tool) {
        tool = want
        // ⚠️ **Only here, and not in [grab].** Arriving at BUILD with nothing to build *out of* is
        // the one opening that leaves the player unable to act, so the material is filled in for
        // them — see [pickDefaultMaterial]. A copy brings a material of its own and must keep it.
        if (want == Tool.Build) pickDefaultMaterial()
    }

    /**
     * Fills in what to build out of, **only when nothing is chosen** — the most of anything the
     * network can actually deliver.
     *
     * ⛔ **Null only.** A material the player has picked is respected for as long as they leave it
     * picked, even when there is none of it aboard: laying a ghost in a metal nobody has yet is a
     * legitimate thing to do and it simply waits. Overwriting that because a tool was reopened would
     * quietly retract a decision.
     *
     * ⚠️ **Heaviest loose first**, which is what [Stockpile.buildableSpecies] already answers — what
     * is in tanks, in buffers, on belts, or in something already marked to come apart. Not fabric:
     * that would name a metal the player has not freed and a site built from it would never finish.
     *
     * ⚠️ **Sweeps the world**, so it is called on a keypress and never from a getter or a per-frame
     * path — see [VesselState.stockpile].
     */
    fun pickDefaultMaterial() {
        if (buildMaterial != null) return
        buildMaterial = materialsOffered().firstOrNull()
    }

    /**
     * What the material picker is offering, in the order it lists them: everything loose aboard
     * heaviest first, and then, in creative, the allowance that is not aboard already.
     *
     * ⛔ **One list, because there are two ways to reach it** — the `E` key and the picker's own
     * column — and a key that could reach a material the panel does not show would leave the picker
     * highlighting nothing. Same argument [SPEEDS] is one list by.
     */
    fun materialsOffered(stock: Stockpile = state.stockpile): List<Species> =
        stock.buildableSpecies + creativeMaterials(stock)

    /**
     * The creative allowance that is not aboard already — the picker's second section.
     *
     * Empty outside creative. Held here rather than in the HUD so that the panel's second section
     * and [materialsOffered]'s tail cannot come to disagree about what is in it.
     */
    fun creativeMaterials(stock: Stockpile = state.stockpile): List<Species> =
        if (!state.creative) emptyList()
        else Stockpile.CREATIVE_MATERIALS.filter { it !in stock.buildableSpecies }

    /**
     * Steps the material along the picker's own list — what `E` does.
     *
     * ⚠️ **A key of its own rather than a rung in some tool's cycle**, because a material is not a
     * property of a tool: it is a standing choice about a batch of building that survives changing
     * brush, changing facing and changing tool entirely. See [buildMaterial].
     *
     * A material the player has chosen but has none of is not in the list, so cycling from it starts
     * at the top rather than nowhere — the same answer [cycleBrush] gives from an empty palette.
     */
    fun cycleMaterial(delta: Int) {
        val all = materialsOffered()
        if (all.isEmpty()) return
        val at = buildMaterial?.let { all.indexOf(it) } ?: -1
        buildMaterial = if (at < 0) all[if (delta >= 0) 0 else all.lastIndex]
        else all[((at + delta) % all.size + all.size) % all.size]
    }

    /** Steps which layer the delete tool takes off — what `X` does once DELETE is out. */
    fun cycleDeleteLayer(delta: Int) {
        val all = DeleteLayer.entries
        deleteLayer = all[wrap(all.indexOf(deleteLayer) + delta, all.size)]
    }

    /** Steps which network the cut tool severs — what `Q` does once CUT is out. */
    fun cycleCutConduit(delta: Int) {
        val all = Tool.CUTTABLE
        cutConduit = all[wrap(all.indexOf(cutConduit) + delta, all.size)]
    }

    /**
     * Steps along the palette, and **picks the first entry when nothing is held** — an empty palette
     * is where the key is most likely to be pressed, and wrapping from "nothing" to the far end
     * would make the first press the one that behaves differently from all the rest.
     */
    fun cycleBrush(delta: Int) {
        val all = Brush.ALL
        val at = brush?.let { all.indexOf(it) } ?: return run { brush = all[if (delta >= 0) 0 else all.lastIndex] }
        brush = all[((at + delta) % all.size + all.size) % all.size]
    }

    fun rotateBrush() {
        brushFacing = brushFacing.clockwise
    }

    /**
     * The frame's position on the sim's clock, in **fractional ticks since the world began**.
     *
     * This is the whole of what makes the world move smoothly at sub-sixty ticks a second: the sim's
     * state is a series of stills, and this says how far between two of them the frame is. Anything
     * that interpolates asks a [org.emerge.demo.outofspace.world.Cadence] how far along it is, and a
     * cadence measures against this.
     *
     * ⚠️ **`tick - 1` is the reducer's tick, and that is the point.** The reducer is scheduled
     * against `state.tick` and hands back a state numbered one higher, so the step that just landed
     * ran at `tick - 1`; a pass that fired during it stamps that number. Line the two up anywhere
     * else and every interpolation in the game is a tick out.
     *
     * ⛔ **It does not wrap, and it must not.** It used to be taken modulo the tick rate, which the
     * renderer then took modulo a subsystem period — an arrangement that is only ever right when
     * the period divides the tick rate *and* the subsystem fires on tick zero of it. Neither is
     * true any more. Unwrapped there is nothing to line up: the difference between now and a stamp
     * is just a number of ticks. `Double` rather than `Float` because unwrapped it has to stay
     * exact for the life of a session, and a `Float`'s mantissa runs out after about three days.
     */
    val simTime: Double
        get() = (tick - 1).toDouble() + accumulator.toDouble() * cfg.ticksPerSecond

    /**
     * Advances by [deltaSeconds] of real time and returns the state to draw.
     *
     * [maxTicksPerFrame] is the spiral-of-death guard: catching up fully after a long frame makes
     * the next frame longer still. Dropping the surplus runs the sim slow under load, which is
     * recoverable; the alternative is a freeze.
     */
    fun tick(deltaSeconds: Float, maxTicksPerFrame: Int = 8): VesselState {
        // ⛔ **The loop runs whether or not the game is stopped**, and [paused] decides only what
        // kind of tick it runs. It used to branch here: a running game stepped, and a stopped one
        // stepped anyway if an edit happened to be waiting — a whole tick of physics for a click.
        // See [paused] for what the two consequences of this are and why they are the point.
        accumulator += deltaSeconds.coerceIn(0f, 0.25f) * speed
        var steps = 0
        while (accumulator >= cfg.secondsPerTick && steps < maxTicksPerFrame) {
            if (paused) stepFrozen() else stepOnce()
            accumulator -= cfg.secondsPerTick
            steps++
        }
        if (steps == maxTicksPerFrame) accumulator = 0f
        return stepper.state
    }

    /**
     * Advances **exactly one tick**, applying whatever edits are pending — real time not involved.
     *
     * The clock for anything that is not a window. [tick] is about turning frame deltas into ticks,
     * which is a question only a host with a display has; a test or the agent harness wants to say
     * "one tick" and get one tick, with no accumulator to leave a fraction behind. Both go through
     * this, so a scripted world and a played one advance by the same call.
     */
    fun stepOnce(): VesselState {
        stepper.profiler = profiler
        stepper.step(mapOf(localPlayer to takeInput()))
        livedTicks++
        followFrame()
        return stepper.state
    }

    /**
     * Advances the clock by exactly one tick and lets the player's edits land, and does nothing else
     * — see [OutofspaceReducer.freeze]. What a paused game runs.
     *
     * Outside the stepper because a frozen tick is not the reducer's `reduce` and there is nothing
     * to profile: it is one edit pass and a great many blocks declining to run.
     */
    private fun stepFrozen(): VesselState {
        val next = OutofspaceReducer.freeze(cfg, stepper.state, mapOf(localPlayer to takeInput()))
        stepper.reset(next, Tick(stepper.tick.value + 1))
        followFrame()
        return stepper.state
    }

    /**
     * Keeps the indices this controller is holding pointing at the tiles they were pointing at, in
     * case the tick grew the grid underneath them.
     *
     * Every one of these is `y * width + x`, so all three go wrong whenever the *width* changes —
     * including on a far-side growth, where the origin has not moved and nothing looks like it
     * happened. That is why this reindexes through [Move] rather than adding an offset.
     */
    private fun followFrame() {
        val move = frame.advance(stepper.state)
        if (!move.moved) return
        selected = move.reindex(selected)
        // The inspector is pinned to a *tile*, so a grid that grows under it moves it exactly as it
        // moves the selection — otherwise the panel would carry on describing the same index, which
        // after a width change is a different place entirely.
        inspectTile = move.reindex(inspectTile)
        injectTile = move.reindex(injectTile)
        dragFrom = move.reindex(dragFrom)
    }

    private fun takeInput(): OutofspaceInput {
        // The engine fires on every tick it is held, so it is added here rather than queued — see
        // [thrustX]. It goes on the *end*, after this tick's builds, which is the order the reducer
        // wants anyway: the impulse is worked out against the mass the edits leave behind.
        val firing = thrustX != 0 || thrustY != 0
        val injecting = injectTile != TileIndex.NONE
        val held = if (mode == Mode.Flight) heldKeys else 0
        if (pending.isEmpty() && !firing && !injecting && held == 0) return OutofspaceInput.EMPTY
        val edits = ArrayList<Edit>(pending)
        // Before the thrust for no reason beyond a fixed order, and after this tick's builds so a
        // tile that was walled off a moment ago is walled off for this breath too.
        if (injecting) edits.add(
            if (tool == Tool.InjectWater) Edit.Inject(injectTile, Edit.WATER_INJECT_MASS, water = true)
            else Edit.Inject(injectTile),
        )
        if (firing) edits.add(Edit.Thrust(thrustX, thrustY))
        pending.clear()
        return OutofspaceInput(edits, held)
    }

    /**
     * Which world this is, counted from the first — bumped by every [reset].
     *
     * ⚠️ **For anything holding a memory *about* a world rather than a copy of one.** The HUD keeps
     * a few — whether the ship was berthed last frame is one — and a memory like that is nonsense
     * across a load: the world it described is gone. There is nothing in the state itself that says
     * so, because a loaded world is a perfectly ordinary world and a tick counter comes back with
     * whatever the file said, so the fact is stated here instead of inferred there.
     */
    var worldSerial: Int = 0
        private set

    /**
     * Whether the world was **already berthed when it was handed over** — see [reset].
     *
     * ⛔ **Not `state.berth != null`, and the difference is exactly what this exists for.** A world
     * can be handed over flying free and be berthed before anybody draws it: the agent harness's
     * `berth` puts the station in place and clamps on in one breath, and any host that steps the
     * world before its first frame can do the same. The question the HUD is asking is whether the
     * berth came out of a file or was flown to, and only the handover can answer it — by the first
     * frame the two look identical.
     */
    var arrivedBerthed: Boolean = initial.berth != null
        private set

    /** Replaces the world — what "new game" and "load" will call. */
    fun reset(newState: VesselState = starterWorld(cfg.initialGrid)) {
        worldSerial++
        arrivedBerthed = newState.berth != null
        selected = TileIndex.NONE
        inspectTile = TileIndex.NONE
        closeWiki()
        pending.clear()
        thrustX = 0
        thrustY = 0
        heldKeys = 0
        injectTile = TileIndex.NONE
        accumulator = 0f
        stepper.reset(newState, Tick(0))
        livedTicks = newState.tick
        frame.reset(newState)
    }
    companion object {
        /**
         * The speeds the dial offers, slowest first.
         *
         * ⛔ **One list, because there are two ways to reach it** — the buttons in the HUD and the
         * `[` / `]` keys — and a keyboard that could reach a rate the buttons could not show would
         * leave the panel highlighting nothing while the world ran at a speed nobody chose. The
         * old keys halved and doubled freely up to 16x and did exactly that.
         */
        val SPEEDS: List<Float> = listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)
    }

}
