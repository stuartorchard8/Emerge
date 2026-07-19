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
    fun faster() { targetTps = (targetTps * 2).coerceAtMost(MAX_TPS) }
    fun slower() { targetTps = (targetTps / 2).coerceAtLeast(MIN_TPS) }

    /** Whether the SLOW / FAST controls should be offered. SLOW bottoms out at [MIN_TPS]. FAST tops out at
     *  [MAX_TPS] and is also withheld while the world is falling short of the target (achieved < half target):
     *  raising the ceiling then just wastes spin, and the auto-drop would claw it back anyway. Paused counts as
     *  "not behind" so the player can preset a higher speed before resuming. */
    fun canSlower(): Boolean = targetTps > MIN_TPS
    fun canFaster(): Boolean = targetTps < MAX_TPS && (paused || actualTps >= targetTps / 2.0)

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
                // Auto-drop an over-ambitious ceiling: if the world is realizing two full ladder rungs (or more)
                // below the target, lower the target to two increments (×4) above the realized rate's rung — so
                // it hovers just above what the world can actually do instead of spinning for a rate it can't
                // hit. Only ever lowers (raising is the FAST button); measured over a real running window here,
                // so it never fires while paused (that branch continues above).
                val ceiling = autoDropTarget(actualTps, targetTps)
                if (ceiling != targetTps) { targetTps = ceiling; nextTickNs = now }
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
                if (sleepNs > 0) waitUntil(nextTickNs, sleepNs)
                // Behind: run flat out to repay the debt. Only give up once we're a long way back (the world
                // is genuinely too heavy, or the target just changed) — resyncing forgives the missed ticks,
                // so doing it eagerly costs rate outright. The bound is wall-clock, not slices, because what
                // puts us here is a wall-clock event (a GC pause): a 10ms hitch is 82 slices at 8192 TPS but
                // two thirds of one at 64, and a slice-based bound would forgive the former while banking a
                // second-long catch-up burst for the latter.
                else if (-sleepNs > MAX_LAG_NS) nextTickNs = System.nanoTime()
            } else {
                nextTickNs = now                     // keep the deadline live for a switch back to throttled
            }
        }
    }

    /**
     * Waits until [deadlineNs] — by spinning above [SPIN_ABOVE_TPS], parking at or below it.
     *
     * A thread that parks for most of every slice reads as an idle core, so a scaling governor drops the
     * clock and each tick costs ~3x what it does free-running (measured: 55us -> 150us at 2048). The sim then
     * can't fit tick+sleep into the slice and lands 12-20% under target — while UNLIMITED, which never
     * sleeps, holds full speed on the same world. Spinning keeps the core resident: tick cost stays at its
     * free-running 55us across every rung.
     *
     * It has to be the *whole* wait, not a short spin before the deadline: what the governor responds to is
     * duty cycle, so a 150us tail on a 977us slice still leaves the core ~70% idle and still downclocks
     * (measured: no better than a pure park). Hence the [SPIN_ABOVE_TPS] floor — spinning costs a core, and
     * the slow rungs sleep away most of their slice. They can afford to: their slices are long enough to
     * absorb a downclocked tick and still hit target.
     */
    private fun waitUntil(deadlineNs: Long, sleepNs: Long) {
        if (targetTps <= SPIN_ABOVE_TPS) { LockSupport.parkNanos(sleepNs); return }
        while (System.nanoTime() < deadlineNs) Thread.onSpinWait()
    }

    companion object {
        const val UNLIMITED = Int.MAX_VALUE
        const val REALTIME_TPS = 64
        const val MIN_TPS = 1                      // SLOW floor
        const val MAX_TPS = 65_536                 // FAST ceiling (doubling stops here)

        /** Largest power-of-two ladder rung ≤ [v], floored at [MIN_TPS] (the ladder never dips below 1). */
        fun floorPow2(v: Double): Int {
            if (v < MIN_TPS) return MIN_TPS
            var r = MIN_TPS
            while (r <= MAX_TPS / 2 && r * 2 <= v) r *= 2
            return r
        }

        /** Pure auto-drop rule (see the call site): the new target given a realized [actual] and
         *  [currentTarget] — two rungs (×4) above [actual]'s ladder rung, but only when that's lower than the
         *  current target (never raises). e.g. target 256 realizing < 64 → 128. Extracted so it's testable. */
        fun autoDropTarget(actual: Double, currentTarget: Int): Int {
            val ceiling = (floorPow2(actual) * 4).coerceIn(MIN_TPS, MAX_TPS)
            return if (ceiling < currentTarget) ceiling else currentTarget
        }
        private const val PUBLISH_INTERVAL_NS = 1_000_000_000L / 100   // publish ≤100 Hz (display cadence)
        private const val TPS_WINDOW_NS = 500_000_000L                 // 0.5 s trailing window for actualTps
        private const val MAX_LAG_NS = 25_000_000L                     // lag past this → resync, don't catch up
        private const val SPIN_ABOVE_TPS = 512                         // ≤ this → park (spinning costs a core)
    }
}
