package org.emerge.demo.drockets

import org.emerge.demo.drockets.shader.LineageNodeShaderSources
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Instanced filled-disc shader for the lineage overlay. Each instance is one node
 * at NDC `iCenter` with NDC `iRadius`, filled with `iColor` (RGBA) and optionally
 * decorated with an opaque ring when `iRingFrac > 0`.
 *
 * Replaces the existing cladogram-panel approach of drawing each node as 4 line
 * segments (diamond outline) + emulating a "filled" highlight as ~13 horizontal
 * line segments. Here the disc is the geometry, the fragment shader handles
 * antialiasing, and selection/hover state is a per-instance flag — no second
 * draw call needed.
 */
class LineageNodeShader {
    private val program: Int = ShaderFactory.createProgram(
        LineageNodeShaderSources.vertex(),
        LineageNodeShaderSources.fragment(),
    )
    private val uAspectScale: Int = GPU.getUniformLocation(program, "uAspectScale")
    private val vao: Int? = GPU.genAndBindVertexArrays()

    private val quadVbo: Int = GPU.genBuffers()
    private val centerVbo: Int = GPU.genBuffers()
    private val radiusVbo: Int = GPU.genBuffers()
    private val colorVbo: Int = GPU.genBuffers()
    private val ringFracVbo: Int = GPU.genBuffers()

    private val centerScratch = GpuFloatBuffer(MAX_INSTANCES * 2)
    private val radiusScratch = GpuFloatBuffer(MAX_INSTANCES)
    private val colorScratch = GpuFloatBuffer(MAX_INSTANCES * 4)
    private val ringScratch = GpuFloatBuffer(MAX_INSTANCES)

    init {
        GPU.bindVertexArray(vao)

        // Per-vertex quad: triangle strip ordering ( bl, br, tl, tr ).
        GPU.bindBuffer(GPU.ARRAY_BUFFER, quadVbo)
        val quad = GpuFloatBuffer(8)
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).flip()
        GPU.bufferData(GPU.ARRAY_BUFFER, 8, quad, GPU.STATIC_DRAW)
        GPU.enableVertexAttribArray(ATTR_QUAD)
        GPU.putVertexAttribPointer(ATTR_QUAD, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.vertexAttribDivisor(ATTR_QUAD, 0)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, centerVbo)
        GPU.enableVertexAttribArray(ATTR_CENTER)
        GPU.putVertexAttribPointer(ATTR_CENTER, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.vertexAttribDivisor(ATTR_CENTER, 1)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, radiusVbo)
        GPU.enableVertexAttribArray(ATTR_RADIUS)
        GPU.putVertexAttribPointer(ATTR_RADIUS, 1, GPU.FLOAT, false, 1 * 4, 0)
        GPU.vertexAttribDivisor(ATTR_RADIUS, 1)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, colorVbo)
        GPU.enableVertexAttribArray(ATTR_COLOR)
        GPU.putVertexAttribPointer(ATTR_COLOR, 4, GPU.FLOAT, false, 4 * 4, 0)
        GPU.vertexAttribDivisor(ATTR_COLOR, 1)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, ringFracVbo)
        GPU.enableVertexAttribArray(ATTR_RING)
        GPU.putVertexAttribPointer(ATTR_RING, 1, GPU.FLOAT, false, 1 * 4, 0)
        GPU.vertexAttribDivisor(ATTR_RING, 1)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
        GPU.bindVertexArray(null)
    }

    /**
     * Draws [instanceCount] filled discs. `centersXy`, `radii`, `colorsRgba`, and `ringFracs`
     * are dense arrays of length `instanceCount * channels`; the shader splits across multiple
     * draw calls if the count exceeds [MAX_INSTANCES].
     */
    fun drawInstanced(
        instanceCount: Int,
        centersXy: FloatArray,
        radii: FloatArray,
        colorsRgba: FloatArray,
        ringFracs: FloatArray,
        aspectScaleX: Float = 1f,
        aspectScaleY: Float = 1f,
    ) {
        if (instanceCount <= 0) return
        GPU.useProgram(program)
        GPU.putUniform2f(uAspectScale, aspectScaleX, aspectScaleY)
        GPU.bindVertexArray(vao)

        var offset = 0
        while (offset < instanceCount) {
            val n = minOf(MAX_INSTANCES, instanceCount - offset)

            centerScratch.clear().put(centersXy, offset * 2, n * 2).flip()
            GPU.bindBuffer(GPU.ARRAY_BUFFER, centerVbo)
            GPU.bufferData(GPU.ARRAY_BUFFER, n * 2, centerScratch, GPU.DYNAMIC_DRAW)

            radiusScratch.clear().put(radii, offset, n).flip()
            GPU.bindBuffer(GPU.ARRAY_BUFFER, radiusVbo)
            GPU.bufferData(GPU.ARRAY_BUFFER, n, radiusScratch, GPU.DYNAMIC_DRAW)

            colorScratch.clear().put(colorsRgba, offset * 4, n * 4).flip()
            GPU.bindBuffer(GPU.ARRAY_BUFFER, colorVbo)
            GPU.bufferData(GPU.ARRAY_BUFFER, n * 4, colorScratch, GPU.DYNAMIC_DRAW)

            ringScratch.clear().put(ringFracs, offset, n).flip()
            GPU.bindBuffer(GPU.ARRAY_BUFFER, ringFracVbo)
            GPU.bufferData(GPU.ARRAY_BUFFER, n, ringScratch, GPU.DYNAMIC_DRAW)

            GPU.drawTrianglesInstanced(0, 4, n)
            offset += n
        }
        GPU.bindVertexArray(null)
    }

    fun delete() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(quadVbo)
        GPU.deleteBuffers(centerVbo)
        GPU.deleteBuffers(radiusVbo)
        GPU.deleteBuffers(colorVbo)
        GPU.deleteBuffers(ringFracVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    companion object {
        const val MAX_INSTANCES: Int = 4096

        private const val ATTR_QUAD = 0
        private const val ATTR_CENTER = 1
        private const val ATTR_RADIUS = 2
        private const val ATTR_COLOR = 3
        private const val ATTR_RING = 4
    }
}
