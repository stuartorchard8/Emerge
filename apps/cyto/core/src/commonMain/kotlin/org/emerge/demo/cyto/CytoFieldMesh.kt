package org.emerge.demo.cyto

import org.emerge.demo.cyto.shader.FieldShaderSources
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

/**
 * A gouraud-interpolated, vertex-coloured triangle mesh in screen NDC — the fill primitive for the
 * light-field heatmap. Each vertex carries a position + rgba, and the GPU interpolates the colour across
 * every triangle, so a smooth field renders continuously (no visible grid cells). Non-instanced: the whole
 * mesh is one dynamic vertex list re-uploaded per frame (cheap at the field's resolution). Caller wraps
 * blend state. Cyto-specific (the light field is a cyto concept), mirroring the shared [org.emerge.render.
 * torus.ui.UiRectRenderer] plumbing.
 */
internal class CytoFieldMesh(private val maxVerts: Int) {
    private val program = ShaderFactory.createProgram(
        FieldShaderSources.vertex(),
        FieldShaderSources.fragment(),
    )

    private val vao = GPU.genAndBindVertexArrays()
    private val posVbo = GPU.genBuffers()
    private val colorVbo = GPU.genBuffers()

    private val posBuffer = GpuFloatBuffer(maxVerts * 2)
    private val colorBuffer = GpuFloatBuffer(maxVerts * 4)

    init {
        initFloatBuffer(posVbo, 0, 2)
        initFloatBuffer(colorVbo, 1, 4)
    }

    /** Draw [vertexCount] independent-triangle vertices (a multiple of 3), reading NDC [positions] (x,y
     *  pairs) and [colors] (rgba quads). */
    fun draw(vertexCount: Int, positions: FloatArray, colors: FloatArray) {
        val n = vertexCount.coerceIn(0, maxVerts)
        if (n <= 0) return
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        bind(posVbo, posBuffer, positions, n * 2)
        bind(colorVbo, colorBuffer, colors, n * 4)
        GPU.drawTriangles(0, n)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(posVbo)
        GPU.deleteBuffers(colorVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    private fun initFloatBuffer(vbo: Int, attribute: Int, sizeX: Int) {
        GPU.bindVertexArray(vao)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(attribute)
        GPU.putVertexAttribPointer(attribute, sizeX, GPU.FLOAT, false, sizeX * 4, 0)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun bind(vbo: Int, buffer: GpuFloatBuffer, array: FloatArray, count: Int) {
        buffer.clear().put(array, 0, count).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }
}
