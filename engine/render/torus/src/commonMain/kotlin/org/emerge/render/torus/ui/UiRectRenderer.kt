package org.emerge.render.torus.ui

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
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
    private val mat4Vbo = GPU.genBuffers()
    private val centerVbo = GPU.genBuffers()
    private val halfSizeVbo = GPU.genBuffers()
    private val colorVbo = GPU.genBuffers()

    private val colorBuffer = GpuFloatBuffer(maxRects * 4)
    private val mat4Buffer = GpuFloatBuffer(maxRects * Mat4.FLOATS)

    init {
        uploadQuad()
        initFloatBuffer(colorVbo, INSTANCE_COLOR_ATTR, 4)
        initFloatBuffer(mat4Vbo, INSTANCE_MAT4_ATTR, 4, 4)
    }

    /**
     * ⛔ **More than [maxRects] draws in batches; it does not draw the first [maxRects] and drop the
     * rest.** It used to `coerceIn(0, maxRects)`, which is silent, total, and invisible until
     * something downstream of the loss is looked for: a nav dial built from a few hundred rects took
     * the panel's whole contents with it — needle, ship and all — because those were *later* rects in
     * the same coalesced run, and the symptom was a map with nothing on it rather than a partial
     * circle. A cap belongs on the buffer, which is what [maxRects] still sizes; it does not belong
     * on the drawing.
     */
    fun drawInstanced(
        count: Int,
        matrices: FloatArray,
        colors: FloatArray,
    ) {
        if (count <= 0) return
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        var done = 0
        while (done < count) {
            val n = minOf(maxRects, count - done)
            bind(colorVbo, colorBuffer, colors, done * 4, n * 4)
            bind(mat4Vbo, mat4Buffer, matrices, done * Mat4.FLOATS, n * Mat4.FLOATS)
            GPU.drawTrianglesInstanced(0, QUAD_VERTEX_COUNT, n)
            done += n
        }
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(quadVbo)
        GPU.deleteBuffers(centerVbo)
        GPU.deleteBuffers(halfSizeVbo)
        GPU.deleteBuffers(colorVbo)
        GPU.deleteBuffers(mat4Vbo)
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

    private fun initFloatBuffer(vbo: Int, attribute: Int, sizeX: Int = 1, sizeY: Int = 1) {
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(attribute)
        val floatSize = 4
        val strideBytes = sizeX * sizeY * floatSize
        for (col in 0 until sizeY) {
            val loc = attribute + col
            GPU.enableVertexAttribArray(loc)
            GPU.putVertexAttribPointer(loc, sizeX, GPU.FLOAT, false, strideBytes, col * sizeX * floatSize)
            GPU.vertexAttribDivisor(loc, 1)
        }
        GPU.vertexAttribDivisor(attribute, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun bind(vbo: Int, buffer: GpuFloatBuffer, array: FloatArray, offset: Int, count: Int) {
        buffer.clear().put(array, offset, count).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    companion object {
        private const val INSTANCE_COLOR_ATTR = 1
        private const val INSTANCE_MAT4_ATTR = 2

        const val DEFAULT_MAX_RECTS = 128

        private const val QUAD_VERTEX_COUNT = 4
    }
}
