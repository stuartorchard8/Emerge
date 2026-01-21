package org.emerge.net.api

/**
 * Minimal multiplatform atomic reference.
 *
 * - JVM/Android: backed by `java.util.concurrent.atomic.AtomicReference`
 * - JS: single-threaded `var`
 */
expect class AtomicRef<T>(initial: T) {
    fun get(): T
    fun set(value: T)
}

