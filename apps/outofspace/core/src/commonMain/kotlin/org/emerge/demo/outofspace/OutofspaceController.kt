package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.WEIGHT_LADDER
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper

/** Which mouse-click means what. */
enum class Tool(val label: String) { Build("BUILD"), Wire("WIRE") }

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
    var brush: MachineKind = MachineKind.Belt
    var brushFacing: Direction = Direction.Right

    /**
     * Build or wire. Two tools rather than a modifier key, because wiring is a mode you stay in for
     * a while and a held key is a poor way to express that — and there is no modifier key on a phone.
     */
    var tool: Tool = Tool.Build

    /** The machine the wiring panel is editing, or -1. Cleared whenever it stops being a machine. */
    var selected: Int = -1
        private set

    val state: VesselState get() = stepper.state
    val tick: Long get() = stepper.state.tick

    fun place(index: Int) = pending.add(Edit.Place(index, brush, brushFacing))

    /** Left-click behaviour, which depends on the tool. */
    fun apply(index: Int) {
        when (tool) {
            Tool.Build -> place(index)
            Tool.Wire -> selected = if (state[index] == null) -1 else index
        }
    }

    fun wire(index: Int, action: Action, slot: Int, trigger: Trigger?) =
        pending.add(Edit.Wire(index, action, slot, trigger))

    fun setChannel(index: Int, channel: Channel) = pending.add(Edit.SetChannel(index, channel))

    /** Cycles a trigger's channel; the constant is included so a term can be pinned on. */
    fun cycleTriggerChannel(index: Int, action: Action, slot: Int, delta: Int) {
        val current = state[index]?.wiring?.triggers(action)?.getOrNull(slot) ?: return
        val all = Channel.ALL
        val next = all[((all.indexOf(current.channel) + delta) % all.size + all.size) % all.size]
        wire(index, action, slot, current.copy(channel = next))
    }

    /** Cycles a trigger's weight through [WEIGHT_LADDER] — a ladder beats a slider on a touchscreen. */
    fun cycleTriggerWeight(index: Int, action: Action, slot: Int, delta: Int) {
        val current = state[index]?.wiring?.triggers(action)?.getOrNull(slot) ?: return
        val at = WEIGHT_LADDER.indexOf(current.weightPermille).let { if (it < 0) 0 else it }
        val next = WEIGHT_LADDER[((at + delta) % WEIGHT_LADDER.size + WEIGHT_LADDER.size) % WEIGHT_LADDER.size]
        wire(index, action, slot, current.copy(weightPermille = next))
    }

    fun cycleSensorChannel(index: Int, delta: Int) {
        val sensor = state[index] as? Sensor ?: return
        val all = Channel.EMITTABLE
        val next = all[((all.indexOf(sensor.channel) + delta) % all.size + all.size) % all.size]
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
