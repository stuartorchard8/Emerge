package org.emerge.sim.core.ecs

/**
 * JS actual: a no-op lock. Browser/JS execution is single-threaded within a single
 * event-loop turn, so there is nothing to serialise; the lock methods still exist
 * so the common code can call them unconditionally.
 */
actual class ReentrantLock actual constructor() {
    actual fun lock() {}
    actual fun unlock() {}
}
