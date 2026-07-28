package org.emerge.demo.scavengers

/**
 * Cross-platform "how should the demo start?" settings.
 *
 * This is intentionally tiny and UI-agnostic so desktop + Android can share the same
 * launch semantics (mode, networking target, and preferred render backend).
 */
data class LaunchSettings(
    val mode: LaunchMode = LaunchMode.JOIN_IMPULSE,
    val gameMode: GameMode = GameMode.CO_OP,
    // former, the dedicated host on the LAN (see tools/HOST_SETUP.md).
    val hostIp: String = "192.168.1.141",
    val port: Int = 7777,
)

enum class LaunchMode {
    LOCAL,
    HOST,
    HEADLESS_HOST,

    /**
     * Full-reducer lockstep join: the client re-simulates forces locally and only inputs cross
     * the wire, so client and host must agree bit-for-bit.
     *
     * That holds JVM-to-JVM but NOT on Android — a phone joining this way completes the TCP
     * connect and then fails to stay in sync. Use [JOIN_IMPULSE] for mobile.
     */
    JOIN,

    /**
     * Lockstep join with forces computed host-side and shipped as impulses. The client runs
     * [ScavengersNoImpulseReducer], so it never has to reproduce the host's force math.
     *
     * This is the default, and the only join mode verified working on Android.
     */
    JOIN_IMPULSE,

    /** Thin client: the host sends full state, the client only renders. */
    JOIN_THIN,
}

enum class GameMode {
    PVP,
    CO_OP,
}
