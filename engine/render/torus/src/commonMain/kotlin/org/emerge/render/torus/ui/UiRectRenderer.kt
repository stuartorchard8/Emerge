package org.emerge.render.torus.ui

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.UiRectShaderSources
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Instanced solid-colour rectangles in screen NDC — the fill primitive for the in-game UI toolkit
 * ([Ui]) and its panels/buttons. Per-instance centre, half-size, and rgba colour. Shared across games
 * (moved out of cyto). Caller wraps blend state.
 */
class UiRectRenderer(private val maxRects: Int = DEFAULT_MAX_RECTS) {
    private val program = ShaderFactory.createProgram(
        UiRectShaderSources.vertex(),
        UiRectShaderSources.fragment(),
    )

    private val vao = GPU.genAndBindVertexArrays()
    private val quadVbo = GPU.genBuffers()
    private val centerVbo = GPU.genBuffers()
    private val halfSizeVbo = GPU.genBuffers()
    private val colorVbo = GPU.genBuffers()

    private val centerBuffer = GpuFloatBuffer(maxRects * 2)
    private val halfSizeBuffer = GpuFloatBuffer(maxRects * 2)
    private val colorBuffer = GpuFloatBuffer(maxRects * 4)

    init {
        uploadQuad()
        initFloatBuffer(centerVbo, 1, 2)
        initFloatBuffer(halfSizeVbo, 2, 2)
        initFloatBuffer(colorVbo, 3, 4)
    }

    fun drawInstanced(count: Int, centers: FloatArray, halfSizes: FloatArray, colors: FloatArray) {
        val n = count.coerceIn(0, maxRects)
        if (n <= 0) return
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        bind(centerVbo, centerBuffer, centers, n * 2)
        bind(halfSizeVbo, halfSizeBuffer, halfSizes, n * 2)
        bind(colorVbo, colorBuffer, colors, n * 4)
        GPU.drawTrianglesInstanced(0, QUAD_VERTEX_COUNT, n)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(quadVbo)
        GPU.deleteBuffers(centerVbo)
        GPU.deleteBuffers(halfSizeVbo)
        GPU.deleteBuffers(colorVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    private fun uploadQuad() {
        val verts = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f)
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, quadVbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun initFloatBuffer(vbo: Int, attribute: Int, sizeX: Int) {
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(attribute)
        GPU.putVertexAttribPointer(attribute, sizeX, GPU.FLOAT, false, sizeX * 4, 0)
        GPU.vertexAttribDivisor(attribute, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun bind(vbo: Int, buffer: GpuFloatBuffer, array: FloatArray, count: Int) {
        buffer.clear().put(array, 0, count).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    companion object {
        const val DEFAULT_MAX_RECTS = 128
        private const val QUAD_VERTEX_COUNT = 4
    }
}
