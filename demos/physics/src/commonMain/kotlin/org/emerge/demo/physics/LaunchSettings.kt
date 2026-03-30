package org.emerge.demo.physics

/**
 * Cross-platform "how should the demo start?" settings.
 *
 * This is intentionally tiny and UI-agnostic so desktop + Android can share the same
 * launch semantics (mode, networking target, and preferred render backend).
 */
data class LaunchSettings(
    val mode: LaunchMode = LaunchMode.JOIN,
    val gameMode: GameMode = GameMode.CO_OP,
    val hostIp: String = "192.168.0.114",
    val port: Int = 7777,
)

enum class LaunchMode {
    LOCAL,
    HOST,
    HEADLESS_HOST,
    JOIN,
    JOIN_IMPULSE,
    JOIN_THIN,
}

enum class GameMode {
    PVP,
    CO_OP,
}
