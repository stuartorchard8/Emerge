package org.emerge.demo.cyto

/**
 * Cross-platform mutual exclusion for [CytoController]'s threaded desktop host (sim thread + draw thread):
 * a real monitor on the JVM/Android, a no-op on single-threaded JS. Kept tiny and demo-local so the
 * engine stays threading-agnostic.
 */
internal expect fun <R> withLock(lock: Any, block: () -> R): R
