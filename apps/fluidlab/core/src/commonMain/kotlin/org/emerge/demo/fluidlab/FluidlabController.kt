package org.emerge.demo.fluidlab

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TickStepper

/**
 * Owns the running simulation and the boundary between real time and sim time.
 *
 * Hosts (desktop / Android / web / the agent harness) all talk to this and nothing deeper.
 *
 * Frame time is *accumulated*, not integrated: the sim only ever advances by whole
 * [FluidlabConfig.secondsPerTick] steps, so a 144 Hz machine and a 30 Hz phone reach identical
 * states. A variable timestep would make the physics frame-rate-dependent and every determinism
 * guarantee downstream would quietly stop being true — which matters more here than in most games,
 * because the thing being observed *is* the physics.
 */
class FluidlabController(
    val cfg: FluidlabConfig = FluidlabConfig(),
    initial: FluidlabState = FluidlabState.sealedRoom(cfg),
) {
    private val stepper = TickStepper(cfg, initial, FluidlabReducer)

    private val localPlayer = PlayerId(0)

    private var accumulator = 0f
    private val pending = ArrayList<FluidlabEdit>()

    var paused: Boolean = false

    /** Sim speed multiplier — 2f runs two ticks per tick's worth of real time. */
    var speed: Float = 1f

    val state: FluidlabState get() = stepper.state
    val tick: Long get() = stepper.state.tick

    // ── Edits ────────────────────────────────────────────────────────────────────
    // All queued, never applied directly: an edit is an input to the next tick, which is what keeps
    // the reducer the only thing that ever changes the world.

    fun setWall(tile: Int, present: Boolean) { pending.add(FluidlabEdit.SetWall(tile, present)) }

    fun inject(tile: Int, species: Species, mass: Long, kelvin: Int = AMBIENT_KELVIN) {
        pending.add(FluidlabEdit.Inject(tile, species, mass, kelvin))
    }

    fun evacuate(tile: Int) { pending.add(FluidlabEdit.Evacuate(tile)) }

    fun heat(tile: Int, energy: Long) { pending.add(FluidlabEdit.Heat(tile, energy)) }

    /**
     * Advances the sim by [deltaSeconds] of real time and returns the state to draw.
     *
     * [maxTicksPerFrame] is the spiral-of-death guard: if a frame takes longer than the ticks it owes,
     * catching up fully would make the next frame longer still. Dropping the surplus makes the sim run
     * slow under load, which is recoverable; the alternative is a freeze.
     */
    fun tick(deltaSeconds: Float, maxTicksPerFrame: Int = 8): FluidlabState {
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
            // Edits made while paused still land, so the world reacts to a click when it is stopped —
            // which is most of how a lab gets used: stop it, poke it, single-step, look.
            stepper.step(mapOf(localPlayer to takeInput()))
        }
        return stepper.state
    }

    /** Advances exactly [count] ticks regardless of wall time. The harness and tests run on this. */
    fun stepTicks(count: Int): FluidlabState {
        repeat(count) { stepper.step(mapOf(localPlayer to takeInput())) }
        return stepper.state
    }

    private fun takeInput(): FluidlabInput {
        if (pending.isEmpty()) return FluidlabInput.EMPTY
        val input = FluidlabInput(edits = pending.toList())
        pending.clear()
        return input
    }

    fun reset(newState: FluidlabState = FluidlabState.sealedRoom(cfg)) {
        pending.clear()
        accumulator = 0f
        stepper.reset(newState, org.emerge.sim.core.Tick(0))
    }
}
