package org.emerge.sim.sync.platform

/**
 * Minimal cross-platform "sleep" primitive.
 *
 * Used only to avoid hot-spinning in polling loops. On JS this is a no-op.
 */
internal expect fun sleepMillis(ms: Long)

