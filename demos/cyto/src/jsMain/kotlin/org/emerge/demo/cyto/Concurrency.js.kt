package org.emerge.demo.cyto

// JS is single-threaded (the sim and draw share the event loop), so no locking is needed.
internal actual fun <R> withLock(lock: Any, block: () -> R): R = block()
