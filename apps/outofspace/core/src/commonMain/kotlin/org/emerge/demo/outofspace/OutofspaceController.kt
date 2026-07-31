package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.VesselState
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
    var brush: MachineKind = MachineKind.Belt
    var brushFacing: Direction = Direction.Right

    val state: VesselState get() = stepper.state
    val tick: Long get() = stepper.state.tick

    fun place(index: Int) = pending.add(Edit.Place(index, brush, brushFacing))
    fun rotate(index: Int) = pending.add(Edit.Rotate(index))
    fun remove(index: Int) = pending.add(Edit.Remove(index))

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
        pending.clear()
        accumulator = 0f
        stepper.reset(newState, Tick(0))
    }
}
