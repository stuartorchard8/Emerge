package org.emerge.demo.template

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimInput
import org.emerge.sim.core.SimReducer

/**
 * The simulation. Replace all of this with your game — it exists to show the shape the engine
 * expects, not because bouncing discs are interesting.
 *
 * The contract that matters is [SimReducer]: `reduce(cfg, state, inputs) -> state` must be a pure
 * function. Nothing outside it may mutate the state, and it must not read wall-clock time, platform
 * `Random`, or anything else that differs between two machines. Hold that line and you get replay,
 * save/load, headless tests, and lockstep multiplayer for free; break it and all four break at once.
 *
 * This state is a `List<Body>` of immutable values because that reads clearly. Real worlds here
 * (Cyto, Scavengers) hold thousands to millions of entities and use the engine's structure-of-arrays
 * ECS (`org.emerge.sim.core.ecs.soa`) instead — parallel `FloatArray`s mutated in place, which the
 * reducer contract still permits as long as the mutation is confined to the tick. Start with the
 * list; move when you have measured that you need to.
 */
data class TemplateConfig(
    /** Side length of the square, toroidal world. World coordinates run over `[-size/2, +size/2)`. */
    val worldSize: Float = 2f,
    /** Fixed timestep. The sim always advances in whole ticks of this length, never by frame delta. */
    val secondsPerTick: Float = 1f / 60f,
    /** Hard cap, so a stuck finger on the spawn button can't grow the world without bound. */
    val maxBodies: Int = 4000,
    /** How sharply velocities curve each tick — the only thing making the demo world move. */
    val swirlPerTick: Float = 0.004f,
)

/** One simulated thing. */
data class Body(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val radius: Float,
    /** 0..1, straight through to the renderer's palette. */
    val hue: Float,
)

/**
 * Everything one player asks of one tick. Inputs are values, not callbacks: they are what a network
 * transport serialises and what a replay file records.
 */
data class TemplateInput(
    val spawns: List<Pair<Float, Float>> = emptyList(),
    val clear: Boolean = false,
) : SimInput {
    companion object {
        val EMPTY = TemplateInput()
    }
}

/** The whole world at one instant. */
data class TemplateState(
    val bodies: List<Body>,
    val tick: Long,
    /** PRNG state carried in the snapshot — never seed from the platform, that desyncs peers. */
    val randomSeed: Long,
) {
    companion object {
        /** A world with [count] bodies scattered by the deterministic PRNG from [seed]. */
        fun initial(cfg: TemplateConfig, count: Int = 120, seed: Long = 0x5EEDL): TemplateState {
            var s = seed
            val bodies = ArrayList<Body>(count)
            repeat(count) {
                val (nx, s1) = nextUnit(s); s = s1
                val (ny, s2) = nextUnit(s); s = s2
                val (nvx, s3) = nextUnit(s); s = s3
                val (nvy, s4) = nextUnit(s); s = s4
                val (nh, s5) = nextUnit(s); s = s5
                bodies.add(
                    Body(
                        x = (nx - 0.5f) * cfg.worldSize,
                        y = (ny - 0.5f) * cfg.worldSize,
                        vx = (nvx - 0.5f) * 0.4f,
                        vy = (nvy - 0.5f) * 0.4f,
                        radius = 0.012f,
                        hue = nh,
                    ),
                )
            }
            return TemplateState(bodies, tick = 0, randomSeed = s)
        }
    }
}

/**
 * The reducer. Every rule of the game lives in here or in something it calls.
 *
 * Note how the per-player inputs are folded in **sorted by [PlayerId]** rather than in map order:
 * two peers must apply the same inputs in the same order or their worlds diverge on the first tick
 * where two players act at once.
 */
object TemplateReducer : SimReducer<TemplateConfig, TemplateState, TemplateInput> {

    override fun reduce(
        cfg: TemplateConfig,
        state: TemplateState,
        inputs: Map<PlayerId, TemplateInput>,
    ): TemplateState {
        val ordered = inputs.entries.sortedBy { it.key.value }
        if (ordered.any { it.value.clear }) {
            return state.copy(bodies = emptyList(), tick = state.tick + 1)
        }

        val dt = cfg.secondsPerTick
        val half = cfg.worldSize * 0.5f
        val moved = ArrayList<Body>(state.bodies.size + 8)
        for (b in state.bodies) {
            // A slow rotation of the velocity vector, so the world visibly does something. Small-angle
            // rotation: (vx, vy) turned by `swirl` radians, normalised back to its original speed.
            val a = cfg.swirlPerTick
            val vx = b.vx - b.vy * a
            val vy = b.vy + b.vx * a
            moved.add(b.copy(x = wrap(b.x + vx * dt, half), y = wrap(b.y + vy * dt, half), vx = vx, vy = vy))
        }

        var seed = state.randomSeed
        for ((_, input) in ordered) {
            for ((sx, sy) in input.spawns) {
                if (moved.size >= cfg.maxBodies) break
                val (nvx, s1) = nextUnit(seed); seed = s1
                val (nvy, s2) = nextUnit(seed); seed = s2
                val (nh, s3) = nextUnit(seed); seed = s3
                moved.add(
                    Body(
                        x = wrap(sx, half),
                        y = wrap(sy, half),
                        vx = (nvx - 0.5f) * 0.4f,
                        vy = (nvy - 0.5f) * 0.4f,
                        radius = 0.012f,
                        hue = nh,
                    ),
                )
            }
        }

        return TemplateState(bodies = moved, tick = state.tick + 1, randomSeed = seed)
    }

    /**
     * Applies a delta snapshot from the host. Full-snapshot demos just take the delta wholesale;
     * a demo that sends partial state merges it here.
     */
    override fun patchState(state: TemplateState, delta: TemplateState): TemplateState = delta
}

/** Toroidal wrap into `[-half, +half)`. */
fun wrap(v: Float, half: Float): Float {
    val size = half * 2f
    var r = v
    while (r >= half) r -= size
    while (r < -half) r += size
    return r
}

/**
 * Shortest signed distance from 0 to [d] on a torus of [size] — use this for *every* difference
 * between two world positions (rendering offsets, distance checks, steering). Plain subtraction is
 * wrong near the seam and the bug it causes only shows up at the edges of the world.
 */
fun wrapDelta(d: Float, size: Float): Float {
    val half = size * 0.5f
    var r = d
    while (r > half) r -= size
    while (r < -half) r += size
    return r
}

/**
 * SplitMix64 — a deterministic PRNG whose state is one `Long`, so it serialises with the snapshot.
 * Returns the value in `[0, 1)` plus the next seed; the caller threads the seed through, which keeps
 * the reducer pure.
 */
fun nextUnit(seed: Long): Pair<Float, Long> {
    var z = seed + -0x61c8864680b583ebL
    val next = z
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    // Top 24 bits → a float in [0,1): enough precision for a Float, and never negative.
    return ((z ushr 40).toFloat() / (1 shl 24).toFloat()) to next
}
