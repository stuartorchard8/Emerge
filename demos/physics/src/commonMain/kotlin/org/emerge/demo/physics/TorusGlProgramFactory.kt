package org.emerge.demo.physics

/**
 * Platform-specific OpenGL program compilation/linking.
 *
 * - Android uses OpenGL ES 2.0 (GLES20)
 * - Desktop uses OpenGL 3.3 core via LWJGL (GL33C)
 *
 * Both return an integer program id.
 */
expect object TorusGlProgramFactory {
    fun createProgramGles2(maxBodies: Int): Int
    fun createProgramGl330(maxBodies: Int): Int
}

