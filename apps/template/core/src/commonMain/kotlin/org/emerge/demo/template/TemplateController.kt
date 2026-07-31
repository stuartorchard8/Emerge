package org.emerge.demo.template

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TickStepper

/**
 * Owns the running simulation and the boundary between real time and sim time.
 *
 * Hosts (desktop / Android / web) all talk to this and nothing deeper: they hand it a frame delta,
 * it hands back the state to draw. That is what lets three platforms share one game — see the
 * `TemplateMain`, `TemplateAndroidView` and `TemplateWeb` hosts, none of which knows a rule.
 *
 * Frame time is *accumulated*, not integrated: the sim only ever advances by whole
 * [TemplateConfig.secondsPerTick] steps, so a 144 Hz machine and a 30 Hz phone reach identical
 * states. A variable timestep would make the physics frame-rate-dependent and every determinism
 * guarantee downstream would quietly stop being true.
 */
class TemplateController(
    val cfg: TemplateConfig = TemplateConfig(),
    initial: TemplateState = TemplateState.initial(TemplateConfig()),
) {
    private val stepper = TickStepper(cfg, initial, TemplateReducer)

    /** Local player. A single-player host is just the degenerate case of the multiplayer one. */
    private val localPlayer = PlayerId(0)

    private var accumulator = 0f
    private val pendingSpawns = ArrayList<Pair<Float, Float>>()
    private var pendingClear = false

    var paused: Boolean = false

    /** Sim speed multiplier — 2f runs two ticks per tick's worth of real time. */
    var speed: Float = 1f

    val state: TemplateState get() = stepper.state
    val tick: Long get() = stepper.state.tick
    val bodyCount: Int get() = stepper.state.bodies.size

    /** Queues a body at a world position. Applied on the next tick, as an input — never a direct write. */
    fun spawnAt(worldX: Float, worldY: Float) {
        pendingSpawns.add(worldX to worldY)
    }

    /** Queues "empty the world", applied on the next tick. */
    fun clear() {
        pendingClear = true
    }

    /**
     * Advances the sim by [deltaSeconds] of real time and returns the state to draw.
     *
     * [maxTicksPerFrame] is the spiral-of-death guard: if a frame takes longer than the ticks it owes,
     * catching up fully would make the next frame longer still. Dropping the surplus makes the sim run
     * slow under load, which is recoverable; the alternative is a freeze.
     */
    fun tick(deltaSeconds: Float, maxTicksPerFrame: Int = 8): TemplateState {
        if (!paused) {
            accumulator += deltaSeconds.coerceIn(0f, 0.25f) * speed
            var steps = 0
            while (accumulator >= cfg.secondsPerTick && steps < maxTicksPerFrame) {
                stepper.step(mapOf(localPlayer to takeInput()))
                accumulator -= cfg.secondsPerTick
                steps++
            }
            if (steps == maxTicksPerFrame) accumulator = 0f
        } else if (pendingSpawns.isNotEmpty() || pendingClear) {
            // Edits made while paused still land, so the world reacts to a tap when it is stopped.
            stepper.step(mapOf(localPlayer to takeInput()))
        }
        return stepper.state
    }

    /** Drains the queued input into one tick's worth of [TemplateInput]. */
    private fun takeInput(): TemplateInput {
        if (pendingSpawns.isEmpty() && !pendingClear) return TemplateInput.EMPTY
        val input = TemplateInput(spawns = pendingSpawns.toList(), clear = pendingClear)
        pendingSpawns.clear()
        pendingClear = false
        return input
    }

    /** Replaces the world — the hook a "load save" or "new game" menu action calls. */
    fun reset(newState: TemplateState = TemplateState.initial(cfg)) {
        pendingSpawns.clear()
        pendingClear = false
        accumulator = 0f
        stepper.reset(newState, org.emerge.sim.core.Tick(0))
    }

    /** The body nearest [worldX],[worldY] within [radius] world units, or null. */
    fun bodyAt(worldX: Float, worldY: Float, radius: Float = 0.05f): Body? {
        var best: Body? = null
        var bestSq = radius * radius
        for (b in stepper.state.bodies) {
            val dx = wrapDelta(b.x - worldX, cfg.worldSize)
            val dy = wrapDelta(b.y - worldY, cfg.worldSize)
            val d = dx * dx + dy * dy
            if (d < bestSq) { bestSq = d; best = b }
        }
        return best
    }
}
