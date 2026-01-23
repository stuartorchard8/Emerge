package org.emerge.render.torus

/**
 * Platform-specific OpenGL program compilation/linking for the torus shader renderer.
 *
 * - Android: OpenGL ES 2.0 (GLES20)
 * - Desktop JVM: OpenGL 3.3 core via LWJGL (GL33C)
 *
 * Both return an integer program id.
 */
expect object TorusGlProgramFactory {
    fun createProgramGles2(maxBodies: Int): Int
    fun createProgramGl330(maxBodies: Int): Int
}

