package org.emerge.demo.cyto.sim

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The matter overlay tallies the field from the **draw thread** while the **sim thread** keeps mutating it,
 * with no lock between them (see [CytoMatterField]'s KDoc). That is only sound while every buffer the tally
 * writes belongs to the tallying thread.
 *
 * It regressed once: the field owned the channel arrays and refilled them from `maintain()`, and the refill
 * zeroed each channel before re-accumulating it — so a frame that scanned mid-refill saw a blanked field,
 * and the overlay flashed. The single-threaded tests all passed throughout, because a tear needs a
 * concurrent reader to see it.
 *
 * So: hammer a tally against a live sim and assert every frame is a plausible one. Reading a *column* under
 * the writer is expected and fine (a texel is one int — worst case, one texel one tick stale); a frame that
 * has lost most of the field is not. Reintroduce shared destination buffers and this fails.
 */
class CytoMatterFieldConcurrencyTest {
    private val AB = SpeciesRegistry.id("rg")

    @Test fun tallyingWhileTheSimMutatesNeverSeesABlankedField() {
        val f = CytoMatterField.seededUniform(10)
        val n = f.resolution * f.resolution

        // What a quiescent field tallies — the bar every concurrent frame has to clear.
        val baseR = IntArray(n); val baseG = IntArray(n); val baseB = IntArray(n)
        f.tallyChannels(baseR, baseG, baseB)
        val quiescent = baseR.sumOf { it.toLong() } + baseG.sumOf { it.toLong() } + baseB.sumOf { it.toLong() }
        assertTrue(quiescent > 0, "a seeded field must tally something for this test to mean anything")

        val stop = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        // The sim: decay rewrites every column in place, deposits churn them further.
        val sim = Thread {
            var i = 0
            while (!stop.get()) {
                f.maintain(decayPeriod = 4)
                f.deposit(0f, 0f, 0.3f, AB, 500)
                i++
            }
        }
        sim.start()
        try {
            // The draw thread: tally into buffers we own, and check each "frame" is whole. Decay only ever
            // moves atoms between species (conservation is exact and covered elsewhere), and deposit only
            // adds, so the channel total can never legitimately sag — half is a wide margin around any
            // per-texel staleness, and a torn frame lands near zero.
            val chR = IntArray(n); val chG = IntArray(n); val chB = IntArray(n)
            repeat(2000) { frame ->
                f.tallyChannels(chR, chG, chB)
                val total = chR.sumOf { it.toLong() } + chG.sumOf { it.toLong() } + chB.sumOf { it.toLong() }
                if (total < quiescent / 2) {
                    failure.compareAndSet(null, "frame $frame tallied $total atoms, quiescent field has $quiescent")
                }
            }
        } finally {
            stop.set(true)
            sim.join(5_000)
        }
        assertTrue(failure.get() == null, "the overlay saw a torn field: ${failure.get()}")
    }
}
