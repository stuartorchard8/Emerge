package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.CytoWorldConfig
import org.emerge.demo.cyto.sim.Distribution
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.sim.core.EntityId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * The guarantee is pinned in three parts, none of them timing-calibrated:
 *  - [draw_threadWritesNeverAcquireStepLock] — the contract itself, asserted by holding `stepLock` open and
 *    checking every draw-thread write still completes. Binary; the scheduler has no say in the outcome.
 *  - [aQueuedEditReachesTheWorld] — the queue isn't a void: an edit lands on both drain paths (paused, via
 *    `publish`, which is how a player actually edits; and ticking, via `stepOnce`).
 *  - [anEditIssuedAgainstAHotSimLoopStillLands] — and it lands against a sim looping flat out, which is
 *    [CytoSimDriver]'s UNLIMITED rung (`while (running) stepOnce()` plus a publish at display cadence,
 *    emulated here because the real driver lives in the desktop module behind GL/LWJGL deps).
 */
class CytoEditLatencyTest {

    /**
     * How long to wait for a draw-thread write that must not block. This is **not** a latency budget being
     * calibrated — the write either takes `stepLock` (and then waits forever, because the test holds it
     * open) or doesn't (and then returns in microseconds). Nothing lands in between, so the number only has
     * to be far enough above "a loaded machine schedules a thread" to never fire spuriously.
     */
    private val neverBlocksTimeoutMs = 10_000L

    /** A populated world, so a tick costs something and the queue drains realistically. Seeded directly
     *  (not grown) to keep the test fast and deterministic. */
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

    /**
     * **The contract, asserted directly.** The sim thread holds `stepLock` for the whole of a tick, so the
     * rule for every frequent draw-thread write is simply: never acquire it. Here a helper thread takes
     * `stepLock` and *keeps* it — the pathological case, a tick that never ends — and each write is then run
     * on its own thread. A write that queues through `inputLock` completes immediately; a write that takes
     * `stepLock` cannot complete at all. The outcome is binary and the scheduler has no say in it.
     *
     * This replaces a proxy metric (sim ticks elapsed inside one `setHeldGene`, budget 2) that measured the
     * right thing indirectly and could not distinguish a real regression from the editing thread merely
     * being descheduled under full-suite load. Both looked like "3 ticks / 1.3ms". It cost ~40 minutes to
     * tell apart once, and the load failures had trained the reflex to call it a flake — which nearly let a
     * genuine regression through. Its own doc recorded two prior recalibrations; there is nothing left here
     * to recalibrate.
     *
     * Every write the UI issues at interactive rates is covered, not just gene edits — they all share the
     * one rule, and a new one that breaks it is named by the failure message.
     */
    @Test fun draw_threadWritesNeverAcquireStepLock() {
        CytoWorldConfig.applyFrom(heavyWorld)
        val controller = CytoController(scenario = heavyWorld)

        val founder = controller.agentCells().firstOrNull { cell ->
            controller.focus(EntityId(cell.id))
            !controller.heldGenome().isNullOrEmpty()
        }
        checkNotNull(founder) { "the scenario must seed a cell with a genome for this test to mean anything" }
        val genome = controller.heldGenome()!!
        assertTrue(genome.isNotEmpty())

        // A tick that never ends. Nothing may wait on it.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread({ controller.underStepLockForTest { held.countDown(); release.await() } }, "step-lock-holder")
            .apply { isDaemon = true; start() }
        assertTrue(held.await(5, TimeUnit.SECONDS), "the helper thread never acquired stepLock")

        val writes = listOf<Pair<String, () -> Unit>>(
            "setHeldGene" to { controller.setHeldGene(0, genome[0]) },
            "appendHeldGene" to { controller.appendHeldGene(genome[0]) },
            "duplicateHeldGene" to { controller.duplicateHeldGene(0) },
            "deleteHeldGene" to { controller.deleteHeldGene(0) },
            "addHeldGenes" to { controller.addHeldGenes(genome) },
            "reorderHeldGeneInGroup" to { controller.reorderHeldGeneInGroup(0, 0) },
            "cycleMutationRate" to { controller.cycleMutationRate() },
            "spawn" to { controller.spawn(0f, 0f, CellType.Collector) },
            "tap" to { controller.tap(0f, 0f, TouchMode.Delete, CellType.Collector) },
        )
        val blocked = ArrayList<String>()
        try {
            for ((name, write) in writes) {
                val done = CountDownLatch(1)
                Thread({ write(); done.countDown() }, "draw-$name").apply { isDaemon = true }.start()
                if (!done.await(neverBlocksTimeoutMs, TimeUnit.MILLISECONDS)) blocked += name
            }
        } finally {
            release.countDown()
            holder.join(5_000)
        }

        assertTrue(
            blocked.isEmpty(),
            "these draw-thread writes did not complete while the sim thread held stepLock, so they take it: " +
                "$blocked. The desktop host issues them from a GLFW callback, so taking stepLock stalls " +
                "glfwPollEvents and freezes the window for as long as the sim keeps barging back in — with " +
                "the world running away underneath it. Queue the write through inputLock (pendingWorldEdits) " +
                "and let the sim thread apply it at the top of a tick.",
        )
    }

    /**
     * The companion property: the queue must still drain while the sim is running flat out. A write that is
     * merely *enqueued* satisfies the lock contract trivially, so this pins that a real edit issued against a
     * hot sim loop actually lands in the world — the same UNLIMITED loop the desktop driver runs.
     */
    @Test fun anEditIssuedAgainstAHotSimLoopStillLands() {
        CytoWorldConfig.applyFrom(heavyWorld)
        val controller = CytoController(scenario = heavyWorld)
        val founder = controller.agentCells().firstOrNull { cell ->
            controller.focus(EntityId(cell.id))
            (controller.heldGenome()?.size ?: 0) >= 2
        }
        checkNotNull(founder) { "need a founder with >=2 genes to swap one for another" }
        val genome = controller.heldGenome()!!
        assertTrue(genome[0] != genome[1], "the two genes must differ for the read-back to prove anything")

        val stop = AtomicBoolean(false)
        val ticks = AtomicLong()
        val sim = Thread({
            var lastPublish = System.nanoTime()
            while (!stop.get()) {
                controller.stepOnce()
                ticks.incrementAndGet()
                val now = System.nanoTime()
                if (now - lastPublish >= 10_000_000L) { controller.publish(); lastPublish = now }
            }
        }, "test-sim").apply { isDaemon = true; start() }
        try {
            Thread.sleep(150)
            assertTrue(ticks.get() > 0, "the sim thread must be ticking for this test to mean anything")
            controller.setHeldGene(0, genome[1])
            // The sim drains at the top of a tick; it is ticking, so this is a matter of microseconds.
            val deadline = System.nanoTime() + 10_000_000_000L
            while (controller.heldGenome()?.getOrNull(0) != genome[1] && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(genome[1], controller.heldGenome()!![0], "an edit issued against a running sim must land in the world")
        } finally {
            stop.set(true)
            sim.join(2000)
        }
    }
}
