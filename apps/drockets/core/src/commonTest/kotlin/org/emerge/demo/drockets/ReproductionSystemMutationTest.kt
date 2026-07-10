package org.emerge.demo.drockets

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReproductionSystemMutationTest {
    @Test
    fun saturatingAdd_saturates_positive_overflow_at_int_max() {
        assertEquals(Int.MAX_VALUE, saturatingAdd(Int.MAX_VALUE, 1))
        assertEquals(Int.MAX_VALUE, saturatingAdd(Int.MAX_VALUE - 50, 100))
        assertEquals(Int.MAX_VALUE, saturatingAdd(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun saturatingAdd_saturates_negative_overflow_at_int_min() {
        assertEquals(Int.MIN_VALUE, saturatingAdd(Int.MIN_VALUE, -1))
        assertEquals(Int.MIN_VALUE, saturatingAdd(Int.MIN_VALUE + 50, -100))
        assertEquals(Int.MIN_VALUE, saturatingAdd(Int.MIN_VALUE, Int.MIN_VALUE))
    }

    @Test
    fun saturatingAdd_passes_through_when_no_overflow() {
        assertEquals(0, saturatingAdd(0, 0))
        assertEquals(100, saturatingAdd(50, 50))
        assertEquals(-100, saturatingAdd(-50, -50))
        assertEquals(Int.MAX_VALUE - 1, saturatingAdd(Int.MAX_VALUE - 100, 99))
    }

    @Test
    fun mutated_saturates_at_int_max_under_positive_deltas() {
        // Force the largest possible positive delta: random low byte = 255 -> delta = 127.
        // Starting from Int.MAX_VALUE, addition would wrap to Int.MIN_VALUE without saturation.
        val maxOut = Genome(
            aiWalkMinTicks = Int.MAX_VALUE,
            aiWalkMaxTicks = Int.MAX_VALUE,
            aiChargeTicks = Int.MAX_VALUE,
            aiFuelTicks = Int.MAX_VALUE,
            aiSpin = Int.MAX_VALUE,
            aiThrust = Int.MAX_VALUE,
            bodyColor = HsvColorGene(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            fireColor = HsvColorGene(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
        )
        val mutated = maxOut.mutated { 0xFF }
        assertEquals(Int.MAX_VALUE, mutated.aiWalkMinTicks)
        assertEquals(Int.MAX_VALUE, mutated.aiWalkMaxTicks)
        assertEquals(Int.MAX_VALUE, mutated.aiChargeTicks)
        assertEquals(Int.MAX_VALUE, mutated.aiFuelTicks)
        assertEquals(Int.MAX_VALUE, mutated.aiSpin)
        assertEquals(Int.MAX_VALUE, mutated.aiThrust)
        assertEquals(HsvColorGene(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE), mutated.bodyColor)
        assertEquals(HsvColorGene(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE), mutated.fireColor)
    }

    @Test
    fun mutated_saturates_at_int_min_under_negative_deltas() {
        // Random low byte = 0 -> raw = -128 -> delta = -127 (after the +1 zero-mean nudge).
        // Without saturation, Int.MIN_VALUE - 127 wraps to a large positive value.
        val minOut = Genome(
            aiWalkMinTicks = Int.MIN_VALUE,
            aiWalkMaxTicks = Int.MIN_VALUE,
            aiChargeTicks = Int.MIN_VALUE,
            aiFuelTicks = Int.MIN_VALUE,
            aiSpin = Int.MIN_VALUE,
            aiThrust = Int.MIN_VALUE,
            bodyColor = HsvColorGene(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE),
            fireColor = HsvColorGene(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE),
        )
        val mutated = minOut.mutated { 0x00 }
        assertEquals(Int.MIN_VALUE, mutated.aiWalkMinTicks)
        assertEquals(Int.MIN_VALUE, mutated.aiThrust)
        assertEquals(HsvColorGene(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE), mutated.bodyColor)
        assertEquals(HsvColorGene(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE), mutated.fireColor)
    }

    @Test
    fun mutated_delta_distribution_has_mean_zero() {
        // Over many mutation samples on a mid-range gene, the mean delta should be very
        // close to zero. The pre-fix formula `(rand ushr 8) - 128` had mean ≈ +8 million
        // (24-bit unsigned magnitude); even the corrected `(rand and 0xFF) - 128` had
        // mean -0.5 over 256 outcomes. The current "add 1 to negatives" form yields
        // mean exactly 0 in the limit.
        val rng = Random(42)
        val genome = Genome(
            aiWalkMinTicks = 0,
            aiWalkMaxTicks = 0,
            aiChargeTicks = 0,
            aiFuelTicks = 0,
            aiSpin = 0,
            aiThrust = 0,
            bodyColor = HsvColorGene(0, 0, 0),
            fireColor = HsvColorGene(0, 0, 0),
        )

        val samples = 10_000
        var sum = 0L
        repeat(samples) {
            // Each mutated() call samples 12 deltas (one per gene field). Sum aiWalkMinTicks
            // post-mutation as the running per-call delta; starting raw is 0, so this is the
            // delta itself.
            val m = genome.mutated { rng.nextInt() }
            sum += m.aiWalkMinTicks
        }
        val mean = sum.toDouble() / samples.toDouble()
        // Tight bound: 10k samples × max-delta-magnitude 127 caps single-trial drift at
        // ~127/sqrt(10000) = 1.27, so a |mean| under 5 is comfortable headroom.
        assertTrue(kotlin.math.abs(mean) < 5.0, "mean delta $mean is not centred at zero")
    }

    @Test
    fun mutated_exhaustive_byte_sweep_sums_to_zero() {
        // Stronger property than the statistical mean test: feed all 256 possible
        // low-byte values to the per-field mutator and assert their deltas sum to exactly
        // zero (so mean over a full byte-sweep is exactly 0).
        val genome = Genome(
            aiWalkMinTicks = 0, aiWalkMaxTicks = 0, aiChargeTicks = 0, aiFuelTicks = 0,
            aiSpin = 0, aiThrust = 0,
            bodyColor = HsvColorGene(0, 0, 0),
            fireColor = HsvColorGene(0, 0, 0),
        )
        var sum = 0
        var zeroCount = 0
        for (b in 0..255) {
            // Only the aiWalkMinTicks slot consumes the next-random byte; subsequent slots
            // would consume more randoms in real use. Use a one-shot RNG that yields b.
            val once = intArrayOf(b)
            val mutated = genome.mutated { val v = once[0]; once[0] = 0; v }
            sum += mutated.aiWalkMinTicks
            if (mutated.aiWalkMinTicks == 0) zeroCount++
        }
        assertEquals(0, sum, "byte-sweep delta sum")
        assertEquals(2, zeroCount, "0 should appear twice in a full byte sweep (once from raw=0, once from raw=-1->0)")
    }

    @Test
    fun mutated_delta_stays_in_byte_range() {
        // Each per-field delta must land in [-127, 127] after the zero-mean nudge.
        val genome = Genome(
            aiWalkMinTicks = 0, aiWalkMaxTicks = 0, aiChargeTicks = 0, aiFuelTicks = 0,
            aiSpin = 0, aiThrust = 0,
            bodyColor = HsvColorGene(0, 0, 0),
            fireColor = HsvColorGene(0, 0, 0),
        )
        val rng = Random(7)
        val mutated = genome.mutated { rng.nextInt() }
        val deltas = listOf(
            mutated.aiWalkMinTicks, mutated.aiWalkMaxTicks, mutated.aiChargeTicks,
            mutated.aiFuelTicks, mutated.aiSpin, mutated.aiThrust,
            mutated.bodyColor.rawH, mutated.bodyColor.rawS, mutated.bodyColor.rawV,
            mutated.fireColor.rawH, mutated.fireColor.rawS, mutated.fireColor.rawV,
        )
        for (d in deltas) {
            assertTrue(d in -127..127, "delta $d out of [-127, 127]")
        }
    }
}
