package org.emerge.demo.cyto.sim.soa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the property the parallel write-back depends on: [MutationRng] gives cells with ADJACENT entity
 * ids (spatially clustered clones, which is the common case) fully decorrelated mutation streams — not the
 * near-identical mutations a naive `seed = base xor entityId` combine would produce. If this ever regresses
 * to a weak mixer, same-genome neighbours would mutate in lockstep (spatial banding).
 */
class MutationRngTest {
    private val base = 0x9E3779B97F4A7C15uL.toLong()
    private val tick = 12_345L

    private fun sequence(entityId: Int, draws: Int = 24, until: Int = 1_000, atTick: Long = tick): List<Int> {
        val rng = MutationRng()
        rng.seed(base, entityId, atTick)
        return List(draws) { rng.nextInt(until) }
    }

    @Test
    fun adjacentEntityIdsProduceDistinctStreams() {
        // 64 consecutive ids (a clonal cluster occupies a contiguous id range): every full draw-sequence
        // must be unique. Weak low-bit mixing would collide neighbours here.
        val seqs = (1_000..1_063).map { sequence(it) }
        assertEquals(seqs.size, seqs.toSet().size, "adjacent entity ids produced duplicate mutation streams")
    }

    @Test
    fun adjacentEntityIdsDifferOnTheVeryFirstDraw() {
        // The first draw is what a rare (1/rateDenom) mutation actually samples, so decorrelation must show
        // up immediately, not only after many steps. Over 200 neighbours, first-draws of nextInt(1_000_000)
        // should be almost all distinct (birthday expectation ~0.02 collisions).
        val firsts = (5_000..5_199).map { sequence(it, draws = 1, until = 1_000_000).first() }
        assertTrue(firsts.toSet().size >= 198, "first draws of adjacent ids are too clustered: ${firsts.toSet().size}/200 distinct")
    }

    @Test
    fun deterministicForSameKey() {
        // Same (base, entityId, tick) ⇒ identical stream, on any thread/run — the reproducibility the golden
        // gate and save/load continuity rely on.
        assertEquals(sequence(4_242), sequence(4_242))
    }

    @Test
    fun differentTicksDecorrelateTheSameCell() {
        // The same cell across consecutive ticks must not repeat its draws (else mutation would be periodic).
        assertTrue(sequence(777, atTick = tick) != sequence(777, atTick = tick + 1),
            "same cell drew identical sequences on adjacent ticks")
    }
}
