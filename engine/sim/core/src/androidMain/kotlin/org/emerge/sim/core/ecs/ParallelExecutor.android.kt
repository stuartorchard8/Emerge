package org.emerge.sim.core.ecs

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

actual class ParallelExecutor actual constructor() {
    private val pool: ExecutorService = Executors.newWorkStealingPool()

    actual val parallelism: Int = Runtime.getRuntime().availableProcessors()

    actual fun invokeAll(tasks: List<() -> Unit>) {
        if (tasks.isEmpty()) return
        if (tasks.size == 1) {
            tasks[0].invoke()
            return
        }
        val futures = tasks.map { task -> pool.submit(task) }
        var first: Throwable? = null
        for (future in futures) {
            try {
                future.get()
            } catch (e: ExecutionException) {
                if (first == null) first = e.cause ?: e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (first == null) first = e
            }
        }
        if (first != null) throw first
    }

    actual fun close() {
        pool.shutdown()
    }
}
