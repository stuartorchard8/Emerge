package org.emerge.demo.cyto

internal actual fun <R> withLock(lock: Any, block: () -> R): R = synchronized(lock, block)
