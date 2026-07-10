package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the determinism fix: the reducer must depend only on (state, inputs), never on
 * wall-clock time. Maturity / gestation now read the deterministic [SimState.tick] clock
 * instead of `Clock.System.now()`, so two runs from the same initial state with identical
 * inputs must produce byte-for-byte identical state. Run far enough past the 600-tick
 * maturity threshold that the formerly-wall-clock-gated AI-launch and reproduction paths fire.
 */
class DrocketsDeterminismTest {

    private val cfg = DrocketsConfig()
    private val ticks = 680 // past the 600-tick (10 s) maturity threshold

    private fun run(): SimState {
        val reducer = DrocketsReducer()
        var s = createDrocketsInitialState()
        val inputs = mapOf(PlayerId(0) to DrocketsInput)
        repeat(ticks) { s = reducer.reduce(cfg, s, inputs) }
        return s
    }

    @Test
    fun reducerIsDeterministicAcrossRuns() {
        val a = run()
        val b = run()
        assertEquals(ticks.toLong(), a.tick, "sim clock should advance one per reduce")
        assertEquals(a.tick, b.tick)
        assertEquals(a.randomSeed, b.randomSeed, "PRNG state diverged")
        // Full component-store equality: every entity + component must match bit-for-bit.
        assertEquals(a.components, b.components, "component stores diverged across identical runs")
        // Sanity: the run actually exercised maturity-gated behaviour (drockets left the
        // surface — some are no longer WALKING — and/or reproduction occurred).
        val phases = a.components.getTable<DrocketStateComponent>().asMap().values.map { it.phase }.toSet()
        assertTrue(phases.size > 1 || phases.singleOrNull() != DrocketPhase.WALKING,
            "expected maturity-gated AI transitions to have fired; phases=$phases")
    }
}
