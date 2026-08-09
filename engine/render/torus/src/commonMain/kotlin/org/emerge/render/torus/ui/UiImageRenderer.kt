package org.emerge.render.torus.ui

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory
import org.emerge.render.torus.shader.UiImageShaderSources

/**
 * A single textured quad in screen NDC, tinted between two colours by the texture's red channel — the
 * fill primitive for UI content that comes from a texture (e.g. [Ui]'s [CanvasBuilder.image]) rather
 * than being drawn primitive-by-primitive. One draw call regardless of the texture's resolution; GPU
 * bilinear filtering (set on the texture itself, not here) is what makes a coarse texture look smooth.
 */
class UiImageRenderer {
    private val program = ShaderFactory.createProgram(
        UiImageShaderSources.vertex(),
        UiImageShaderSources.fragment(),
    )
    private val uCenter = GPU.getUniformLocation(program, "uCenter")
    private val uHalfSize = GPU.getUniformLocation(program, "uHalfSize")
    private val uUvMin = GPU.getUniformLocation(program, "uUvMin")
    private val uUvMax = GPU.getUniformLocation(program, "uUvMax")
    private val uImage = GPU.getUniformLocation(program, "uImage")
    private val uTintLow = GPU.getUniformLocation(program, "uTintLow")
    private val uTintHigh = GPU.getUniformLocation(program, "uTintHigh")

    private val vao = GPU.genAndBindVertexArrays()
    private val quadVbo = GPU.genBuffers()

    init {
        val verts = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f)
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, quadVbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun draw(
        centerX: Float, centerY: Float, halfW: Float, halfH: Float,
        uvMinX: Float, uvMinY: Float, uvMaxX: Float, uvMaxY: Float,
        textureId: Int,
        textureUnit: Int = 2,
    ) {
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        GPU.activeTexture(textureUnit)
        GPU.bindTexture2D(textureId)
        GPU.putUniform1i(uImage, textureUnit)
        GPU.putUniform2f(uCenter, centerX, centerY)
        GPU.putUniform2f(uHalfSize, halfW, halfH)
        GPU.putUniform2f(uUvMin, uvMinX, uvMinY)
        GPU.putUniform2f(uUvMax, uvMaxX, uvMaxY)
        GPU.drawTriangles(0, 4)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(quadVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }
}
