package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import java.util.concurrent.locks.LockSupport

/**
 * Runs the Cyto simulation on its **own daemon thread**, decoupled from the GL draw loop. The sim thread
 * loops [CytoController.stepOnce] at a target tick rate and publishes a fresh snapshot
 * ([CytoController.publish]) at display cadence (~60 Hz); the draw thread reads the latest snapshot via
 * [CytoController.latestFrame] at vsync, so rendering stays smooth (pan/zoom/inspect) no matter how heavy
 * or fast the sim runs — and the sim can run flat out for true throughput measurement.
 *
 * Speed is a target ticks-per-second, changed by halving/doubling ([slower]/[faster]) over a fixed ladder
 * with an [UNLIMITED] top rung (run as fast as the world allows). [targetTps] is a *ceiling*: a world too
 * heavy to reach it just runs slower ([actualTps] < target → "falling behind"), which the readout flags.
 */
class CytoSimDriver(private val controller: CytoController) {

    @Volatile var paused = false; private set
    /** Target ticks/second, or [UNLIMITED]. 64 = real-time (matches [CytoController.STEP] = 1/64 s). */
    @Volatile var targetTps = REALTIME_TPS; private set
    /** Measured ticks/second over a short trailing window (what the sim actually achieves). */
    @Volatile var actualTps = 0.0; private set

    private var running = false
    private val thread = Thread({ loop() }, "cyto-sim").apply { isDaemon = true }

    fun start() { running = true; thread.start() }
    fun stop() { running = false; LockSupport.unpark(thread) }

    fun togglePause() { paused = !paused }
    fun setPaused(p: Boolean) { paused = p }
    fun faster() { targetTps = if (targetTps == UNLIMITED) UNLIMITED else (targetTps * 2).let { if (it > MAX_TPS) UNLIMITED else it } }
    fun slower() { targetTps = if (targetTps == UNLIMITED) MAX_TPS else (targetTps / 2).coerceAtLeast(MIN_TPS) }

    /** True when throttled and the achieved rate is well short of the target (the world is the bottleneck). */
    fun behind(): Boolean = targetTps != UNLIMITED && !paused && actualTps < targetTps * 0.9

    /** Human label for the readout, e.g. "256/512 TPS" or "1830 TPS (max)". */
    fun status(): String {
        val a = actualTps.toInt()
        return when {
            paused -> "paused"
            targetTps == UNLIMITED -> "$a TPS (max)"
            else -> "$a/$targetTps TPS"
        }
    }

    private fun loop() {
        var windowStartNs = System.nanoTime()
        var ticksInWindow = 0
        var lastPublishNs = windowStartNs
        var nextTickNs = windowStartNs
        while (running) {
            if (paused) {
                actualTps = 0.0
                controller.publish()                 // reflect any just-applied load/edit while paused
                LockSupport.parkNanos(PUBLISH_INTERVAL_NS)
                windowStartNs = System.nanoTime(); ticksInWindow = 0
                nextTickNs = windowStartNs           // no catch-up burst for time spent paused
                continue
            }
            controller.stepOnce()
            ticksInWindow++

            val now = System.nanoTime()
            if (now - lastPublishNs >= PUBLISH_INTERVAL_NS) {
                controller.publish()
                lastPublishNs = now
            }
            if (now - windowStartNs >= TPS_WINDOW_NS) {
                actualTps = ticksInWindow * 1e9 / (now - windowStartNs)
                windowStartNs = now; ticksInWindow = 0
            }
            // Throttle to the target rate (unless unlimited) against an absolute deadline that advances by
            // exactly one slice per tick. parkNanos overshoots by a fixed ~60us; sleeping off "the rest of
            // this tick" instead would pay that overshoot every tick and cap the rate well under target
            // (2048 -> ~1800). Carrying the deadline makes the overshoot a constant phase offset, not a
            // rate error: each park is shortened by however late the last one woke.
            if (targetTps != UNLIMITED) {
                val sliceNs = 1_000_000_000L / targetTps
                nextTickNs += sliceNs
                val sleepNs = nextTickNs - System.nanoTime()
                if (sleepNs > 0) LockSupport.parkNanos(sleepNs)
                // Too heavy to keep up (or the target just changed): resync rather than bank a burst of
                // catch-up ticks to be run back-to-back later.
                else if (-sleepNs > sliceNs * MAX_LAG_SLICES) nextTickNs = System.nanoTime()
            } else {
                nextTickNs = now                     // keep the deadline live for a switch back to throttled
            }
        }
    }

    companion object {
        const val UNLIMITED = Int.MAX_VALUE
        const val REALTIME_TPS = 64
        private const val MIN_TPS = 4
        private const val MAX_TPS = 8192           // doubling past this → UNLIMITED
        private const val PUBLISH_INTERVAL_NS = 1_000_000_000L / 100   // publish ≤100 Hz (display cadence)
        private const val TPS_WINDOW_NS = 500_000_000L                 // 0.5 s trailing window for actualTps
        private const val MAX_LAG_SLICES = 4                           // lag past this → resync, don't catch up
    }
}
