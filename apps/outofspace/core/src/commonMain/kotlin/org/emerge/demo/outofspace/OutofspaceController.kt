package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.InputKey
import org.emerge.demo.outofspace.world.KeyInput
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.WEIGHT_LADDER
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper

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

    /** What the player is about to place, and which way it will face. */
    var brush: MachineKind = MachineKind.Rail
    var brushFacing: Direction = Direction.Right

    /** What a left-click does — see [Tool]. */
    var tool: Tool = Tool.Build

    /** Which layer the delete tool takes off. Only read while [tool] is [Tool.Delete]. */
    var deleteLayer: DeleteLayer = DeleteLayer.Top

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
    var injectTile: Int = -1

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

    /** The machine the wiring panel is editing, or -1. Cleared whenever it stops being a machine. */
    var selected: Int = -1
        private set

    val state: VesselState get() = stepper.state
    val tick: Long get() = stepper.state.tick

    fun place(index: Int) = pending.add(Edit.Place(index, brush, brushFacing))

    /**
     * The tile the current drag last reached, or -1 when nothing is being dragged.
     *
     * Conduit is laid by dragging, and connection follows the gesture rather than the geometry: two
     * runs can touch without joining, so a line is only a line where the player actually drew one.
     */
    private var dragFrom: Int = -1

    /** Left-click behaviour, which depends on the tool. */
    fun apply(index: Int) {
        when (tool) {
            Tool.Build -> {
                place(index)
                if (brush.conduit != null) dragFrom = index
            }
            // Resolve to the machine's own tile, so clicking any part of a five-tile furnace
            // selects the furnace rather than nothing.
            Tool.Wire -> selected = state.occupancy[index]
            Tool.Delete -> remove(index)
            // Nothing: the bellows is a *hold*, so it is driven by [injectTile] and a click that
            // pushed one edit here would inject twice on the tick the button went down.
            Tool.Inject, Tool.InjectWater -> {}
        }
    }

    /**
     * Continues a conduit drag to [index], laying and joining every tile along the way.
     *
     * The path is stepped out rather than trusting the pointer to visit every tile: a fast drag skips
     * tiles, and a run with a hole in it is not a run. Horizontal first, then vertical — an L, which
     * is both what the player drew if they dragged along an axis and a predictable answer if they
     * did not.
     */
    fun dragTo(index: Int) {
        if (dragFrom < 0 || index == dragFrom || tool != Tool.Build) return
        val grid = state.grid
        if (index !in 0 until grid.size) return
        var at = dragFrom
        while (at != index) {
            val dir = when {
                grid.xOf(at) != grid.xOf(index) ->
                    if (grid.xOf(index) > grid.xOf(at)) Direction.Right else Direction.Left
                else -> if (grid.yOf(index) > grid.yOf(at)) Direction.Down else Direction.Up
            }
            val next = grid.neighbour(at, dir)
            if (next < 0) break
            place(next)
            pending.add(Edit.Lay(at, next, brush.conduit ?: Conduit.Rail))
            at = next
        }
        dragFrom = at
    }

    /** Ends a conduit drag. The next click starts a new one, unjoined to this. */
    fun endDrag() {
        dragFrom = -1
    }

    fun wire(index: Int, action: Action, slot: Int, trigger: Trigger?) =
        pending.add(Edit.Wire(index, action, slot, trigger))

    /** Binds a button to [key]. */
    fun bindKey(index: Int, key: InputKey) = pending.add(Edit.BindKey(index, key))

    /** Cycles which key a button answers to. */
    fun cycleInputKey(index: Int, delta: Int) {
        val current = state.machineCovering(index) as? KeyInput ?: return
        val all = InputKey.ALL
        val next = all[((all.indexOf(current.key) + delta) % all.size + all.size) % all.size]
        bindKey(index, next)
    }

    /** Cycles a trigger between the constant and the wire under the machine. */
    fun cycleTriggerSource(index: Int, action: Action, slot: Int, delta: Int) {
        val current = state.machineCovering(index)?.wiring?.triggers(action)?.getOrNull(slot) ?: return
        val all = SignalSource.ALL
        val next = all[((all.indexOf(current.source) + delta) % all.size + all.size) % all.size]
        wire(index, action, slot, current.copy(source = next))
    }

    /** Cycles a trigger's weight through [WEIGHT_LADDER] — a ladder beats a slider on a touchscreen. */
    fun cycleTriggerWeight(index: Int, action: Action, slot: Int, delta: Int) {
        val current = state.machineCovering(index)?.wiring?.triggers(action)?.getOrNull(slot) ?: return
        val at = WEIGHT_LADDER.indexOf(current.weightPermille).let { if (it < 0) 0 else it }
        val next = WEIGHT_LADDER[((at + delta) % WEIGHT_LADDER.size + WEIGHT_LADDER.size) % WEIGHT_LADDER.size]
        wire(index, action, slot, current.copy(weightPermille = next))
    }

    /** Drops a rock centred on ([x], [y]) — the stand-in for capture, see [Edit.DropRock]. */
    fun dropRock(x: Float, y: Float) = pending.add(Edit.DropRock(x, y))

    fun rotate(index: Int) = pending.add(Edit.Rotate(index))

    /**
     * Queues the grid back to the ship plus its pad. [followFrame] carries the selection across it.
     */
    fun fit() = pending.add(Edit.Fit)

    /** Takes [deleteLayer] off a tile. Named explicitly by callers that mean a specific layer. */
    fun remove(index: Int, layer: DeleteLayer = deleteLayer) {
        if (index == selected) selected = -1
        pending.add(Edit.Remove(index, layer))
    }

    fun cycleBrush(delta: Int) {
        val all = MachineKind.ALL
        brush = all[((all.indexOf(brush) + delta) % all.size + all.size) % all.size]
    }

    fun rotateBrush() {
        brushFacing = brushFacing.clockwise
    }

    /**
     * How far the clock has got through the tick that has not happened yet, 0 to 1.
     *
     * This is the whole of what makes the world move smoothly at four ticks a second: the sim's
     * state is a series of stills, and this says how far between two of them the frame is. Paused
     * reads 1 rather than freezing mid-step — a stopped world should show where things actually
     * are, not where they were going.
     */
    val tickAlpha: Float
        get() = if (paused) 1f else (accumulator / cfg.secondsPerTick).coerceIn(0f, 1f)

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
        injectTile = move.reindex(injectTile)
        dragFrom = move.reindex(dragFrom)
    }

    private fun takeInput(): OutofspaceInput {
        // The engine fires on every tick it is held, so it is added here rather than queued — see
        // [thrustX]. It goes on the *end*, after this tick's builds, which is the order the reducer
        // wants anyway: the impulse is worked out against the mass the edits leave behind.
        val firing = thrustX != 0 || thrustY != 0
        val injecting = injectTile >= 0
        val held = if (mode == Mode.Flight) heldKeys else 0
        if (pending.isEmpty() && !firing && !injecting && held == 0) return OutofspaceInput.EMPTY
        val edits = ArrayList<Edit>(pending)
        // Before the thrust for no reason beyond a fixed order, and after this tick's builds so a
        // tile that was walled off a moment ago is walled off for this breath too.
        if (injecting) edits.add(
            if (tool == Tool.InjectWater) Edit.Inject(injectTile, Edit.WATER_INJECT_GRAMS, water = true)
            else Edit.Inject(injectTile),
        )
        if (firing) edits.add(Edit.Thrust(thrustX, thrustY))
        pending.clear()
        return OutofspaceInput(edits, held)
    }

    /** Replaces the world — what "new game" and "load" will call. */
    fun reset(newState: VesselState = starterVessel(cfg.initialGrid)) {
        selected = -1
        pending.clear()
        thrustX = 0
        thrustY = 0
        heldKeys = 0
        injectTile = -1
        accumulator = 0f
        stepper.reset(newState, Tick(0))
        frame.reset(newState)
    }
}
