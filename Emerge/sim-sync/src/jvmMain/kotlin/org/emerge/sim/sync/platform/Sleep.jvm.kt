package org.emerge.sim.sync.platform

internal actual fun sleepMillis(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (_: Throwable) {
        // ignore
    }
}

