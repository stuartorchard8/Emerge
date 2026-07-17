package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.CytoWorldConfig
import org.emerge.demo.cyto.sim.Distribution
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.sim.core.EntityId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A gene edit is issued from the **draw thread** while the **sim thread** runs the world. It must not block
 * that thread for any meaningful time — the desktop host issues edits from inside a GLFW mouse callback, so
 * a blocked edit stalls `glfwPollEvents`, and the window stops polling and swapping until it returns. The
 * sim never stops, so the freeze ends with the world hundreds/thousands of ticks further on.
 *
 * That is a real bug Stu hit: "modify genes while the tick speed is high and the whole sim grinds to a halt;
 * wait long enough and it comes back, having run thousands of ticks in the background". The cause was
 * [CytoController.editHeldGenome] taking `stepLock` — the lock the sim thread holds for the WHOLE of
 * [CytoController.stepOnce]. At a high tick rate the sim releases and re-acquires it in a tight loop, and
 * `withLock` is `synchronized` on the JVM: a **non-fair**, barging monitor. The hot sim thread wins the race
 * back in over and over, and the editing thread starves.
 *
 * This test pins the guarantee that fixes it: edits go through the input queue (`inputLock`, drained by the
 * sim thread at the top of a tick), so the editing thread never waits on a tick at all.
 *
 * It emulates [CytoSimDriver]'s UNLIMITED rung — literally `while (running) stepOnce()` plus a publish at
 * display cadence — rather than driving the real driver, which lives in the desktop module (GL/LWJGL deps).
 * That loop IS the driver at UNLIMITED, and it's the shape that starves an edit.
 *
 * Failure mode if this regresses: worst-case latency jumps from ~microseconds to hundreds of ms or seconds,
 * and `ticksDuringWorstBlock` shows the world running away underneath the frozen UI.
 */
class CytoEditLatencyTest {

    /**
     * The assertion is **ticks elapsed inside one edit call**, not wall-clock. That is the actual contract —
     * "an edit never waits for a tick to finish" — and unlike a millisecond budget it doesn't depend on the
     * machine, and can't be faked by a GC pause (a stop-the-world collection freezes the sim thread too, so
     * ticks cannot advance during one). Wall-clock is measured and printed, but only as commentary.
     *
     * 1, not 0: the sim may legitimately complete a tick that was already in flight while we enqueue.
     * Measured pre-fix: 5-14 heavy ticks (and 72 on a light world) per block — this fails unambiguously.
     */
    private val maxTicksInsideAnEdit = 1L
    private val edits = 200

    /**
     * A *populated* world, not the 1-cell default: what the editing thread waits for is one tick's lock hold,
     * so the world has to be heavy enough for a tick to cost something — as Stu's does when he hits this. On
     * the single-founder default the whole effect lands right at the budget and the test flakes. Seeded
     * directly (not grown) to keep the test fast and deterministic.
     */
    private val heavyWorld = CytoScenario(
        name = "edit-latency",
        founders = listOf(FounderSpec(CellType.Collector, 200)),
        distribution = Distribution.Scattered,
    )

    /**
     * The latency guarantee is worthless on its own — an edit queued into the void would satisfy it perfectly.
     * So pin the other half: a queued edit actually reaches the world, on both drain paths.
     *
     * [CytoController.publish] is the one that matters for a **paused** world: the sim thread stops calling
     * [CytoController.stepOnce] but keeps publishing, so that is the only thing that lands an edit made while
     * paused — which is exactly how a player edits genes.
     */
    @Test fun aQueuedEditReachesTheWorld() {
        CytoWorldConfig.applyFrom(heavyWorld)
        val controller = CytoController(scenario = heavyWorld)
        val founder = controller.agentCells().firstOrNull { cell ->
            controller.focus(EntityId(cell.id))   // also freezes mutation on it, so read-back is stable
            (controller.heldGenome()?.size ?: 0) >= 2
        }
        checkNotNull(founder) { "need a founder with >=2 genes to swap one for another" }
        val genome = controller.heldGenome()!!
        assertTrue(genome[0] != genome[1], "the two genes must differ for the read-back to prove anything")

        // Paused: no step will ever run, so publish() has to be what applies it.
        controller.setHeldGene(0, genome[1])
        controller.publish()
        assertEquals(genome[1], controller.heldGenome()!![0], "a gene edit made while paused must apply on publish")

        // And the ticking path.
        controller.setHeldGene(0, genome[0])
        controller.stepOnce()
        controller.publish()
        assertEquals(genome[0], controller.heldGenome()!![0], "a gene edit must apply when the sim steps")
    }

    @Test fun editingAGenomeNeverBlocksBehindTheSimThread() {
        CytoWorldConfig.applyFrom(heavyWorld)
        val controller = CytoController(scenario = heavyWorld)

        // Select a founder that actually has genes — the edit path needs a held cell with a genome.
        val founder = controller.agentCells().firstOrNull { cell ->
            controller.focus(EntityId(cell.id))
            !controller.heldGenome().isNullOrEmpty()
        }
        checkNotNull(founder) { "the default scenario must seed a cell with a genome for this test to mean anything" }
        val genome = controller.heldGenome()!!
        assertTrue(genome.isNotEmpty())

        val stop = AtomicBoolean(false)
        val ticks = AtomicLong()
        // CytoSimDriver's UNLIMITED loop: tick flat out, publish at display cadence, never sleep.
        val sim = Thread({
            var lastPublish = System.nanoTime()
            while (!stop.get()) {
                controller.stepOnce()
                ticks.incrementAndGet()
                val now = System.nanoTime()
                if (now - lastPublish >= 10_000_000L) { controller.publish(); lastPublish = now }
            }
        }, "test-sim").apply { isDaemon = true }
        sim.start()

        // Let the sim get hot and start barging before we measure.
        Thread.sleep(150)
        assertTrue(ticks.get() > 0, "the sim thread must be ticking for this test to mean anything")

        var worstTicks = 0L
        var worstNs = 0L
        repeat(edits) { i ->
            val ticksBefore = ticks.get()
            val t0 = System.nanoTime()
            // Re-set gene 0 to itself: the exact draw-thread edit path, no Gene construction needed.
            controller.setHeldGene(0, genome[0])
            val dtNs = System.nanoTime() - t0
            val ticksInside = ticks.get() - ticksBefore
            if (ticksInside > worstTicks) worstTicks = ticksInside
            if (dtNs > worstNs) worstNs = dtNs
            if (i % 20 == 0) Thread.sleep(1)   // ~a frame apart, like a user clicking
        }
        stop.set(true)
        sim.join(2000)

        println(
            "[edit-latency] worst %d ticks elapsed inside one edit (budget %d); worst wall-clock %.1fms over %d edits; %d ticks total"
                .format(worstTicks, maxTicksInsideAnEdit, worstNs / 1_000_000.0, edits, ticks.get())
        )
        // The edit must not have waited for the sim: if whole ticks completed while we were inside
        // setHeldGene, we were parked on stepLock behind them.
        assertTrue(
            worstTicks <= maxTicksInsideAnEdit,
            ("a gene edit waited for %d sim ticks to complete (%.1fms). The desktop host issues edits from a " +
                "GLFW callback, so that is a %.1fms window freeze with the world running away underneath it. " +
                "Edits must not take stepLock; queue them through inputLock and let the sim thread apply them.")
                .format(worstTicks, worstNs / 1_000_000.0, worstNs / 1_000_000.0)
        )
    }
}
