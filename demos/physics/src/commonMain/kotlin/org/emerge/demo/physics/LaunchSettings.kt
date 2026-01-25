package org.emerge.demo.physics

/**
 * Cross-platform "how should the demo start?" settings.
 *
 * This is intentionally tiny and UI-agnostic so desktop + Android can share the same
 * launch semantics (mode, networking target, and preferred render backend).
 */
data class LaunchSettings(
    val mode: LaunchMode = LaunchMode.HOST,
    val renderBackend: RenderBackend = RenderBackend.GPU,
    val hostIp: String = "192.168.0.114",
    val port: Int = 7777,
)

enum class LaunchMode {
    LOCAL,
    HOST,
    JOIN,
}

/**
 * Abstract backend intent; each platform maps this to its concrete renderer:
 * - Desktop: GPU -> LWJGL/OpenGL, CPU -> Swing2D
 * - Android: GPU -> GLSurfaceView/OpenGL ES, CPU -> Canvas
 */
enum class RenderBackend {
    GPU,
    CPU,
}

