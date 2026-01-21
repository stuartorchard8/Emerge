package org.emerge.net.api

actual class AtomicRef<T> actual constructor(initial: T) {
    private var v: T = initial

    actual fun get(): T = v

    actual fun set(value: T) {
        v = value
    }
}

