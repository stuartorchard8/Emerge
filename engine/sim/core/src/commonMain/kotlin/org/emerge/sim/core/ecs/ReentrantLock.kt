package org.emerge.sim.core.ecs

/**
 * Minimal reentrant lock primitive for serialising access to root-builder shared
 * resources — entity id allocation, the PRNG seed, the respawn queue — when forks
 * dispatch across threads. JVM/Android use the platform [java.util.concurrent.locks.ReentrantLock];
 * JS has a no-op implementation because the runtime is single-threaded.
 *
 * Use via the [withLock] extension:
 * ```
 * val result = lock.withLock { criticalSection() }
 * ```
 *
 * The lock is reentrant, so a system that already holds it can call back into a
 * helper that re-acquires without deadlocking.
 */
expect class ReentrantLock() {
    fun lock()
    fun unlock()
}

/**
 * Runs [block] while holding [this] lock. The lock is always released via `finally`
 * even if [block] throws.
 */
inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
