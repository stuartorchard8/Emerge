package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Save/load (and, by the same serialization, lockstep Welcome/Resync) must preserve the full
 * deterministic state so a sim continued after a round-trip stays bit-identical to one that was
 * never serialized. The PRNG [SimState.randomSeed] and the sim clock [SimState.tick] are
 * synced scalars the component codec doesn't carry, so the snapshot codec must.
 */
class DrocketsSaveDeterminismTest {

    private val cfg = DrocketsConfig()
    private val inputs = mapOf(PlayerId(0) to DrocketsInput)

    private fun advance(state: SimState, ticks: Int): SimState {
        val reducer = DrocketsReducer()
        var s = state
        repeat(ticks) { s = reducer.reduce(cfg, s, inputs) }
        return s
    }

    @Test
    fun saveRoundTripPreservesRandomSeedAndTick() {
        val state = advance(createDrocketsInitialState(), 680)
        assertTrue(state.randomSeed != 0L, "scenario should have drawn RNG so the seed is non-zero")
        assertEquals(680L, state.tick)

        val snapshot = DrocketsSnapshot(Tick(state.tick), state, DrocketLineageState.EMPTY)
        val decoded = DrocketsSaveCodec.decode(DrocketsSaveCodec.encode(snapshot))

        assertEquals(state.randomSeed, decoded.state.randomSeed, "randomSeed lost across save round-trip")
        assertEquals(state.tick, decoded.state.tick, "tick lost across save round-trip")
    }

    /**
     * A loaded snapshot must continue fully deterministically: two independent loads of the same
     * bytes, advanced identically, stay byte-identical. (This is the save/load guarantee — note
     * it is deliberately weaker than lockstep Welcome, which must also match a *never-saved*
     * peer's future and therefore cannot strip the transient particle entities the save path
     * drops, since that perturbs entity-id allocation.)
     */
    @Test
    fun loadedSnapshotContinuesDeterministically() {
        val state = advance(createDrocketsInitialState(), 680)
        val bytes = DrocketsSaveCodec.encode(DrocketsSnapshot(Tick(state.tick), state, DrocketLineageState.EMPTY))
        val a = advance(DrocketsSaveCodec.decode(bytes).state, 60)
        val b = advance(DrocketsSaveCodec.decode(bytes).state, 60)
        assertEquals(a.randomSeed, b.randomSeed)
        val ea = DrocketsSaveCodec.encode(DrocketsSnapshot(Tick(a.tick), a, DrocketLineageState.EMPTY))
        val eb = DrocketsSaveCodec.encode(DrocketsSnapshot(Tick(b.tick), b, DrocketLineageState.EMPTY))
        assertTrue(ea.contentEquals(eb), "two loads of the same snapshot diverged")
    }
}
