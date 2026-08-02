package org.emerge.sim.core.ecs

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

actual class ParallelExecutor actual constructor() {
    private val threadIdx = AtomicInteger(0)
    private val factory = ThreadFactory { r: Runnable ->
        Thread(r, "emerge-pool-${threadIdx.getAndIncrement()}").apply { isDaemon = true }
    }
    private val pool: ExecutorService = ThreadPoolExecutor(
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().availableProcessors() * 2,
        60L, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(),
        factory
    )

    actual val parallelism: Int = Runtime.getRuntime().availableProcessors()

    actual fun invokeAll(tasks: List<() -> Unit>) {
        if (tasks.isEmpty()) return
        if (tasks.size == 1) {
            tasks[0].invoke()
            return
        }
        val jvmTasks: List<Callable<Unit>> = tasks.map { task ->
            object : Callable<Unit> {
                override fun call() { task.invoke(); null }
            }
        }
        var first: Throwable? = null
        try {
            val futures: List<Future<Unit>> = pool.invokeAll(jvmTasks)
            for (future in futures) {
                try { future.get() } catch (e: ExecutionException) { if (first == null) first = e.cause ?: e } catch (e: InterruptedException) { Thread.currentThread().interrupt(); if (first == null) first = e }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            first = e
        }
        if (first != null) throw first
    }

    actual fun close() {
        pool.shutdown()
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow()
        } catch (_: InterruptedException) { pool.shutdownNow(); Thread.currentThread().interrupt() }
    }
}
