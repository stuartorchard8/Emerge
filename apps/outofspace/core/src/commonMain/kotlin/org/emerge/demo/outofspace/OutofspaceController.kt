package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Sensor
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
    initial: VesselState = starterVessel(OutofspaceConfig().grid),
) {
    private val stepper = TickStepper(cfg, initial, OutofspaceReducer)
    private val localPlayer = PlayerId(0)

    private var accumulator = 0f
    private val pending = ArrayList<Edit>()

    var paused: Boolean = false
    var speed: Float = 1f

    /** What the player is about to place, and which way it will face. */
    var brush: MachineKind = MachineKind.Rail
    var brushFacing: Direction = Direction.Right

    /**
     * Build or wire. Two tools rather than a modifier key, because wiring is a mode you stay in for
     * a while and a held key is a poor way to express that — and there is no modifier key on a phone.
     */
    var tool: Tool = Tool.Build

    /** Which overlay the world is being viewed through. A view preference, so it lives here. */
    var overlay: Overlay = Overlay.None

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
        val grid = cfg.grid
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

    fun setChannel(index: Int, channel: Channel) = pending.add(Edit.SetChannel(index, channel))

    /** Cycles a trigger's channel; the constant is included so a term can be pinned on. */
    fun cycleTriggerChannel(index: Int, action: Action, slot: Int, delta: Int) {
        val current = state.machineCovering(index)?.wiring?.triggers(action)?.getOrNull(slot) ?: return
        val all = Channel.ALL
        val next = all[((all.indexOf(current.channel) + delta) % all.size + all.size) % all.size]
        wire(index, action, slot, current.copy(channel = next))
    }

    /** Cycles a trigger's weight through [WEIGHT_LADDER] — a ladder beats a slider on a touchscreen. */
    fun cycleTriggerWeight(index: Int, action: Action, slot: Int, delta: Int) {
        val current = state.machineCovering(index)?.wiring?.triggers(action)?.getOrNull(slot) ?: return
        val at = WEIGHT_LADDER.indexOf(current.weightPermille).let { if (it < 0) 0 else it }
        val next = WEIGHT_LADDER[((at + delta) % WEIGHT_LADDER.size + WEIGHT_LADDER.size) % WEIGHT_LADDER.size]
        wire(index, action, slot, current.copy(weightPermille = next))
    }

    /** Retunes whatever broadcasts on this tile — a gauge in the track, or a sensor on the deck. */
    fun cycleSensorChannel(index: Int, delta: Int) {
        // Track first, matching the edit's own order: a gauge sits on top of whatever it crosses.
        val current = state.railAt(index)?.channel
            ?: when (val m = state.machineCovering(index)) {
                is Sensor -> m.channel
                else -> return
            }
        val all = Channel.EMITTABLE
        val next = all[((all.indexOf(current) + delta) % all.size + all.size) % all.size]
        setChannel(index, next)
    }

    fun rotate(index: Int) = pending.add(Edit.Rotate(index))
    fun remove(index: Int) {
        if (index == selected) selected = -1
        pending.add(Edit.Remove(index))
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
                stepper.step(mapOf(localPlayer to takeInput()))
                accumulator -= cfg.secondsPerTick
                steps++
            }
            if (steps == maxTicksPerFrame) accumulator = 0f
        } else if (pending.isNotEmpty()) {
            // Edits still land while paused, so the world reacts to a click when it is stopped.
            stepper.step(mapOf(localPlayer to takeInput()))
        }
        return stepper.state
    }

    private fun takeInput(): OutofspaceInput {
        if (pending.isEmpty()) return OutofspaceInput.EMPTY
        val input = OutofspaceInput(pending.toList())
        pending.clear()
        return input
    }

    /** Replaces the world — what "new game" and "load" will call. */
    fun reset(newState: VesselState = starterVessel(cfg.grid)) {
        selected = -1
        pending.clear()
        accumulator = 0f
        stepper.reset(newState, Tick(0))
    }
}
