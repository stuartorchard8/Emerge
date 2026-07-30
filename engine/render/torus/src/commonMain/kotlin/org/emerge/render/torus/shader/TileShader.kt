package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.ScreenLayout
import org.emerge.render.torus.put
import org.emerge.sim.core.physics.primitives.Vec2

/**
 * Draws a pre-rendered period of a torus world across the screen, repeating it as many times as the current
 * view calls for (see `tile.frag`). One full-screen pass, one texture fetch per pixel — the number of world
 * copies on screen does not affect its cost.
 *
 * Owns its own unit-quad VAO so it can be issued at any point in a frame without depending on whatever
 * geometry the previous pass left bound.
 */
class TileShader {
    private val program: Int = ShaderFactory.createProgram(
        TileShaderSources.vertex(),
        TileShaderSources.fragment(),
    )

    private val uVpMin = GPU.getUniformLocation(program, "uVpMin")
    private val uVpMax = GPU.getUniformLocation(program, "uVpMax")
    private val uCenter = GPU.getUniformLocation(program, "uCenter")
    private val uViewHalfExtent = GPU.getUniformLocation(program, "uViewHalfExtent")
    private val uPeriod = GPU.getUniformLocation(program, "uPeriod")
    private val uPeriodTex = GPU.getUniformLocation(program, "uPeriodTex")

    private var vpMin = Vec2(0f, 0f)
    private var vpMax = Vec2(1f, 1f)

    private val vao: Int? = GPU.genAndBindVertexArrays()
    private val quadVbo: Int = GPU.genBuffers()

    init {
        // Triangle-strip quad covering NDC [-1, 1]: TL, BL, TR, BR. With the viewport set to the world rect
        // this covers exactly that rect, and the fragment shader recovers screen position from gl_FragCoord.
        val verts = floatArrayOf(
            -1f, 1f,
            -1f, -1f,
            1f, 1f,
            1f, -1f,
        )
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, quadVbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun useLayout(layout: ScreenLayout) {
        vpMin = layout.worldPxMin
        vpMax = layout.worldPxMax
    }

    /** For hosts that give the world the whole framebuffer rather than a [ScreenLayout] sub-rect. */
    fun useFullViewport(widthPx: Float, heightPx: Float) {
        vpMin = Vec2(0f, 0f)
        vpMax = Vec2(widthPx, heightPx)
    }

    /**
     * @param periodTextureId colour texture of the render target the period was drawn into.
     * @param center camera centre in world units.
     * @param viewHalfExtent half the visible width/height in world units.
     * @param period the world period — the extent of one torus repeat.
     */
    fun draw(
        periodTextureId: Int,
        center: Vec2,
        viewHalfExtent: Vec2,
        period: Vec2,
        textureUnit: Int = TILE_TEXTURE_UNIT,
    ) {
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)

        GPU.activeTexture(textureUnit)
        GPU.bindTexture2D(periodTextureId)
        GPU.putUniform1i(uPeriodTex, textureUnit)

        GPU.putUniform2f(uVpMin, vpMin.x, vpMin.y)
        GPU.putUniform2f(uVpMax, vpMax.x, vpMax.y)
        GPU.putUniform2f(uCenter, center.x, center.y)
        GPU.putUniform2f(uViewHalfExtent, viewHalfExtent.x, viewHalfExtent.y)
        GPU.putUniform2f(uPeriod, period.x, period.y)

        GPU.drawTriangles(0, 4)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(quadVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    companion object {
        const val TILE_TEXTURE_UNIT = 0
    }
}
