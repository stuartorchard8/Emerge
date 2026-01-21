package org.emerge.net.api

import java.util.concurrent.atomic.AtomicReference

actual class AtomicRef<T> actual constructor(initial: T) {
    private val ref = AtomicReference(initial)

    actual fun get(): T = ref.get()

    actual fun set(value: T) {
        ref.set(value)
    }
}

