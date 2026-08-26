package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.WEIGHT_LADDER
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.toMachineSettings
import org.emerge.demo.outofspace.world.withSettings
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper
import org.emerge.sim.core.ecs.PipelineProfiler

/**
 * Owns the running world and the boundary between real time and sim time.
 *
 * Hosts talk to this and nothing deeper: hand it a frame delta, get back the state to draw. Frame
 * time is *accumulated*, never integrated — the sim only advances in whole ticks, so a 144 Hz desktop
 * and a 30 Hz phone reach identical worlds, and every determinism guarantee downstream stays true.
 */
class OutofspaceController(
    val cfg: OutofspaceConfig = OutofspaceConfig(),
    initial: VesselState = starterVessel(OutofspaceConfig().initialGrid),
) {
    private val stepper = TickStepper(cfg, initial, OutofspaceReducer)
    private val localPlayer = PlayerId(0)

    private var accumulator = 0f
    private val pending = ArrayList<Edit>()

    /** Tracks the grid growing under the indices held below — see [followFrame]. */
    private val frame = FrameShift(initial)

    var paused: Boolean = false
    var speed: Float = 1f

    /** Optional profiler for per-phase tick analysis. Null unless [enableProfiling] is called. */
    var profiler: PipelineProfiler? = null

    /** What the player is about to place, and which way it will face. */
    var brush: Brush = Brush.Run(Conduit.Rail)
    var brushFacing: Direction = Direction.Right

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

    /** Clipboard status: one-shot message shown in the HUD, cleared after being read. */
    var clipboardStatus: String = ""
        private set

    val state: VesselState get() = stepper.state
    val tick: Long get() = stepper.state.tick

    fun place(tile: TileIndex) { pending.add(Edit.Place(tile, brush, brushFacing)) }

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
            Tool.Build -> {
                place(tile)
                if (brush is Brush.Run) dragFrom = tile
            }
            Tool.Inspect -> inspect(tile)
            Tool.Delete -> removeAt(tile)
            Tool.Cancel -> cancelAt(tile)
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
        if (tool != Tool.Build && tool != Tool.Cut) return
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
            } else {
                place(next)
                pending.add(Edit.Lay(at, next, (brush as? Brush.Run)?.conduit ?: Conduit.Rail))
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
    fun lockStoragePercent(tile: TileIndex, minPercent: Int?) = pending.add(Edit.LockStoragePercent(tile, minPercent))
    fun lockStorageSpecies(tile: TileIndex, species: Species?) = pending.add(Edit.LockStorageSpecies(tile, species))

    /**
     * Copies the settings from the machine at [tile] to the internal clipboard.
     *
     * Pressing **C** on a machine calls this. The clipboard holds one machine's settings at a time;
     * pasting overwrites it. Returns a status message for the HUD.
     */
    fun copySettings(tile: TileIndex): String {
        val machine = state.machineCovering(tile) ?: run {
            clipboardStatus = "no machine there"
            return clipboardStatus
        }
        SettingsClipboard.copy(machine.toMachineSettings())
        clipboardStatus = "copied ${machine.kind.label}"
        return clipboardStatus
    }

    /**
     * Pastes the clipboard settings onto the machine at [tile].
     *
     * Pressing **V** on a machine calls this. Only settings that both machines share are applied.
     * Returns a status message for the HUD.
     */
    fun pasteSettings(tile: TileIndex): String {
        val settings = SettingsClipboard.contents ?: run {
            clipboardStatus = "nothing copied"
            return clipboardStatus
        }
        val target = state.machineCovering(tile) ?: run {
            clipboardStatus = "no machine there"
            return clipboardStatus
        }
        // Only paste if the target is the same kind of machine
        if (target.kind != settings.kind) {
            clipboardStatus = "wrong machine type"
            return clipboardStatus
        }
        val replaced = target.withSettings(settings)
        pending.add(Edit.ReplaceDeckMachine(tile, replaced))
        clipboardStatus = "pasted ${settings.kind.label}"
        return clipboardStatus
    }

    /**
     * Steps a locked warehouse's threshold through [SpeciesFilter.PERCENTS], wrapping.
     *
     * Only meaningful once locked: an unlocked tank has no threshold to move, and the panel offers
     * the lock button instead.
     */
    fun cycleStorageFilterPercent(tile: TileIndex, delta: Int) {
        val current = state.machineCovering(tile) as? Storage ?: return
        val filter = current.filter ?: return
        val all = SpeciesFilter.PERCENTS
        val at = all.indexOf(filter.minPercent).let { if (it < 0) all.indexOf(SpeciesFilter.MAX_PERCENT) else it }
        lockStoragePercent(tile, all[((at + delta) % all.size + all.size) % all.size])
    }
    fun toggleStorageFilterSpecies(tile: TileIndex) {
        val current = state.machineCovering(tile) as? Storage ?: return
        val filter = current.filter ?: return
        // Whatever it is holding most of. A tank with nothing in it has no
        // dominant species and so cannot be locked — the panel says as much
        // rather than this failing quietly, but it must also be true here:
        // the edit queue is not the only way in.
        val store = bufferTile(state.grid, current, tile, BufferRole.Inside)
        val held = store?.let { state.buffers.resourceAt(it) }
        // ⚠️ **Re-locking clears the species requirement, leaving any purity requirement untouched.
        lockStorageSpecies(tile, if (filter.species == null) held?.dominant else null )
    }

    /**
     * Steps a decomposer's setpoint through [ThermalDecomposer.SETPOINTS], wrapping.
     *
     * Wrapping, and one direction per tap, because that is what the storage threshold does and a
     * second interaction idiom for the same shape of choice would be one to learn for no reason.
     */
    fun cycleDecomposerTemperature(tile: TileIndex, delta: Int) {
        val m = state.machineCovering(tile) as? ThermalDecomposer ?: return
        val all = ThermalDecomposer.SETPOINTS
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

    /** Steps a decomposer's residence time through [ThermalDecomposer.DWELLS], wrapping. */
    fun cycleDecomposerDwell(tile: TileIndex, delta: Int) {
        val m = state.machineCovering(tile) as? ThermalDecomposer ?: return
        val all = ThermalDecomposer.DWELLS
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

    /** Cycles a trigger's weight through [WEIGHT_LADDER] — a ladder beats a slider on a touchscreen. */
    fun cycleTriggerWeight(tile: TileIndex, action: Action, slot: Int, delta: Int) {
        val current = state.machineCovering(tile)?.wiring?.triggers(action)?.getOrNull(slot)
        val currentDeck = state.machineCovering(tile)?.wiring?.triggers(action)?.getOrNull(slot)

        if (current != null) {
            val at = WEIGHT_LADDER.indexOf(current.weightPermille).let { if (it < 0) 0 else it }
            val next = WEIGHT_LADDER[((at + delta) % WEIGHT_LADDER.size + WEIGHT_LADDER.size) % WEIGHT_LADDER.size]
            wire(tile, action, slot, current.copy(weightPermille = next))
        }
        if (currentDeck != null) {
            val at = WEIGHT_LADDER.indexOf(currentDeck.weightPermille).let { if (it < 0) 0 else it }
            val next = WEIGHT_LADDER[((at + delta) % WEIGHT_LADDER.size + WEIGHT_LADDER.size) % WEIGHT_LADDER.size]
            wire(tile, action, slot, currentDeck.copy(weightPermille = next))
        }
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

    fun cycleBrush(delta: Int) {
        val all = Brush.ALL
        brush = all[((all.indexOf(brush) + delta) % all.size + all.size) % all.size]
    }

    fun rotateBrush() {
        brushFacing = brushFacing.clockwise
    }

    /**
     * How far the clock has got through the next N ticks that have not happened yet, 0 to [OutofspaceConfig.ticksPerSecond].
     *
     * This is the whole of what makes the world move smoothly at sub-sixty ticks a second: the sim's
     * state is a series of stills, and this says how far between two of them the frame is.
     */
    val tickAlpha: Float
        get() = (((tick-1)%cfg.ticksPerSecond).toFloat() + (accumulator*cfg.ticksPerSecond))

    /**
     * Advances by [deltaSeconds] of real time and returns the state to draw.
     *
     * [maxTicksPerFrame] is the spiral-of-death guard: catching up fully after a long frame makes
     * the next frame longer still. Dropping the surplus runs the sim slow under load, which is
     * recoverable; the alternative is a freeze.
     */
    fun tick(deltaSeconds: Float, maxTicksPerFrame: Int = 8): VesselState {
        if (!paused) {
            accumulator += deltaSeconds.coerceIn(0f, 0.25f) * speed
            var steps = 0
            while (accumulator >= cfg.secondsPerTick && steps < maxTicksPerFrame) {
                stepOnce()
                accumulator -= cfg.secondsPerTick
                steps++
            }
            if (steps == maxTicksPerFrame) accumulator = 0f
        } else if (pending.isNotEmpty()) {
            // Edits still land while paused, so the world reacts to a click when it is stopped.
            stepOnce()
        }
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

    /** Replaces the world — what "new game" and "load" will call. */
    fun reset(newState: VesselState = starterVessel(cfg.initialGrid)) {
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
        frame.reset(newState)
    }
}
