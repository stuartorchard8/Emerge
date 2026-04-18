package org.emerge.sim.core.ecs

/**
 * Thin abstraction over a platform-specific worker pool used by [runParallel] to
 * dispatch the systems of an [PhaseConcurrency.Isolated] phase across threads.
 *
 *  - JVM/Android: backed by a work-stealing [java.util.concurrent.ExecutorService].
 *  - JS: no-op; tasks execute synchronously on the calling thread because the JS
 *    runtime is single-threaded within the event loop.
 *
 * Hosts construct one executor per simulation loop and reuse it every tick. The
 * executor owns real resources (JVM threads), so [close] must be called when the
 * simulation is torn down.
 *
 * [invokeAll] is a blocking call: it submits every task, waits for all of them
 * to finish, and rethrows the first task's exception (unwrapped). Callers can
 * rely on "after [invokeAll] returns, all writes are visible on this thread."
 */
expect class ParallelExecutor() {
    /**
     * Number of worker threads the pool is willing to run concurrently. Used by
     * data-parallel helpers (e.g. partitioning an inner loop over entity pairs)
     * to size their chunking. On JVM/Android this is the pool's parallelism
     * (defaults to `Runtime.getRuntime().availableProcessors()`); on JS it's 1.
     */
    val parallelism: Int

    /**
     * Submits every task to the pool, blocks the calling thread until all tasks
     * complete, and rethrows the first exception encountered (if any).
     *
     * Tasks run in an unspecified order and may run concurrently. They must not
     * capture mutable state that other tasks in the same call also mutate — the
     * whole point of using this with isolated phases is that each fork carries
     * its own write-log and sees a frozen read view.
     */
    fun invokeAll(tasks: List<() -> Unit>)

    /** Releases the underlying pool. Idempotent; safe to call more than once. */
    fun close()
}
