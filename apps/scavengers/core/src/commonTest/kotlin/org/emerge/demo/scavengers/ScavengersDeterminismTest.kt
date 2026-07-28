package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lockstep floor for Scavengers: the two properties that make host↔client sync sound.
 *
 * 1. [reducerIsDeterministicAcrossRuns] — the reducer is a pure function of (state, inputs): two
 *    runs from the same initial state advance byte-for-byte together. If this breaks, every client
 *    silently desyncs from the [ScavengersHeadlessHostController] host, so it is the first thing the
 *    dev-cycle gate should catch.
 *
 * 2. [stateCodecRoundTripIsWireStable] — the on-the-wire snapshot is a fixed point:
 *    `encode(decode(encode(s))) == encode(s)`. A late-joining client rebuilds its world from that
 *    snapshot, so the decoded state must re-encode identically or the joiner starts out of sync.
 *
 * We drive the *playerless* initial state ([spawnHostPlayer] = false) on purpose: that is the exact
 * state the dedicated server boots from, 50 deterministically-seeded planets under gravity with no
 * `Random` in the setup. Player/crash-path coverage (which currently spawns via `Random.Default`)
 * is a follow-up once spawns can be seeded deterministically.
 */
class ScavengersDeterminismTest {

    private val cfg = ScavengersConfig()
    private val ticks = 300
    private val noInputs = emptyMap<PlayerId, ScavengersInput>()

    private fun run(): ScavengersState {
        val reducer = ScavengersReducer()
        var s = createDefaultInitialState(gameMode = GameMode.CO_OP, spawnHostPlayer = false)
        repeat(ticks) { s = reducer.reduce(cfg, s, noInputs) }
        return s
    }

    @Test
    fun reducerIsDeterministicAcrossRuns() {
        val a = run()
        val b = run()
        // NB: Scavengers' reducer does not advance SimState.tick — the LockstepHost tracks the frame
        // number externally — so we assert the two runs agree, not any particular value.
        assertEquals(a.core.tick, b.core.tick, "sim clock diverged across identical runs")
        assertEquals(a.core.randomSeed, b.core.randomSeed, "PRNG state diverged across identical runs")
        assertEquals(a.core.components, b.core.components, "component stores diverged across identical runs")
        // Liveness: the planets actually moved, so the run exercised the physics pipeline rather than
        // comparing two untouched initial states.
        val initial = createDefaultInitialState(gameMode = GameMode.CO_OP, spawnHostPlayer = false)
        assertTrue(a.core.components != initial.core.components, "expected 300 ticks of orbital motion to change state")
    }

    @Test
    fun stateCodecRoundTripIsWireStable() {
        val codec = ScavengersCodecs.stateCodec
        val reducer = ScavengersReducer()
        var s = createDefaultInitialState(gameMode = GameMode.CO_OP, spawnHostPlayer = false)
        repeat(ticks / 2) { s = reducer.reduce(cfg, s, noInputs) }

        val wire = codec.encode(s)
        val reencoded = codec.encode(codec.decode(wire))
        assertTrue(wire.contentEquals(reencoded), "state codec is not a wire-stable fixed point: a decoded snapshot re-encodes differently")
    }
}
