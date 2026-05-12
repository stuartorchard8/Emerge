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
        // Random low byte = 0 -> delta = -128. Without saturation, Int.MIN_VALUE - 128 wraps positive.
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
    fun mutated_delta_distribution_is_symmetric_around_zero() {
        // Over many mutation samples on a mid-range gene, the average delta should be close
        // to zero. The pre-fix formula `(rand ushr 8) - 128` was skewed positive with mean
        // around +8 million (24-bit unsigned magnitude). This regresses that bug.
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
        // With deltas uniform in [-128, 127], expected mean is -0.5; |observed mean| should
        // be well under |10| at 10k samples, far below the +8e6 the old formula produced.
        assertTrue(kotlin.math.abs(mean) < 10.0, "mean delta $mean is not centred near zero")
    }

    @Test
    fun mutated_delta_stays_in_byte_range() {
        // Each per-field delta must be in [-128, 127] inclusive. Verify by feeding a known
        // RNG sequence and checking the gene-by-gene change.
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
            assertTrue(d in -128..127, "delta $d out of [-128, 127]")
        }
    }
}
