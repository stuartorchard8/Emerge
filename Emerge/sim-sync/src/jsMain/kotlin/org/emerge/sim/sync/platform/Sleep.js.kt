package org.emerge.sim.sync.platform

internal actual fun sleepMillis(ms: Long) {
    // JS builds cannot block the event loop; this is only used to avoid hot-spins.
    // In practice, JS targets in this repo are not running the authoritative TCP host.
}

