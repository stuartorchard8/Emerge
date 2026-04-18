package org.emerge.sim.core.ecs

/**
 * JS actual: executes every task inline on the calling thread. The JS runtime has
 * no shared-memory threads in common-KMP code, so "parallel" dispatch collapses to
 * sequential-by-registration-order execution — bit-identical to [runSequential] on
 * an isolated phase.
 */
actual class ParallelExecutor actual constructor() {
    actual fun invokeAll(tasks: List<() -> Unit>) {
        for (task in tasks) task()
    }

    actual fun close() {}
}
