package org.emerge.demo.drockets

import org.emerge.demo.drockets.shader.CladogramLineShaderSources
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Line segments for the cladogram using instanced GL_LINES (same pattern as [CircleShader]).
 *
 * Each instance supplies endpoints (x0,y0,x1,y1) and RGBA in clip space for the right panel.
 */
class CladogramLineShader {
    private val program: Int = ShaderFactory.createProgram(
        CladogramLineShaderSources.vertex(),
        CladogramLineShaderSources.fragment(),
    )
    private val vao: Int? = GPU.genAndBindVertexArrays()

    /** Per-vertex: 0 / 1 selects segment endpoint from [iEndpoints]. */
    private val baseVbo: Int = GPU.genBuffers()
    private val instanceSegVbo: Int = GPU.genBuffers()
    private val instanceColVbo: Int = GPU.genBuffers()

    private val segScratch = GpuFloatBuffer(MAX_LINE_INSTANCES * 4)
    private val colScratch = GpuFloatBuffer(MAX_LINE_INSTANCES * 4)

    /** Packed endpoints for [drawLinesRgba] path (node outlines etc.). */
    private val packEnds = FloatArray(MAX_LINE_INSTANCES * 4)
    private val packCols = FloatArray(MAX_LINE_INSTANCES * 4)

    init {
        GPU.bindVertexArray(vao)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, baseVbo)
        val baseData = GpuFloatBuffer(2)
        baseData.put(floatArrayOf(0f, 1f)).flip()
        GPU.bufferData(GPU.ARRAY_BUFFER, 2, baseData, GPU.STATIC_DRAW)
        GPU.enableVertexAttribArray(ATTR_WHICH)
        GPU.putVertexAttribPointer(ATTR_WHICH, 1, GPU.FLOAT, false, 4, 0)
        GPU.vertexAttribDivisor(ATTR_WHICH, 0)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceSegVbo)
        GPU.enableVertexAttribArray(ATTR_ENDPOINTS)
        GPU.putVertexAttribPointer(ATTR_ENDPOINTS, 4, GPU.FLOAT, false, 16, 0)
        GPU.vertexAttribDivisor(ATTR_ENDPOINTS, 1)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceColVbo)
        GPU.enableVertexAttribArray(ATTR_COLOR)
        GPU.putVertexAttribPointer(ATTR_COLOR, 4, GPU.FLOAT, false, 16, 0)
        GPU.vertexAttribDivisor(ATTR_COLOR, 1)

        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
        GPU.bindVertexArray(null)
    }

    /**
     * @param endpointsXyxy packed x0,y0,x1,y1 per segment (length ≥ instanceCount * 4).
     * @param colorsRgba packed r,g,b,a per segment (length ≥ instanceCount * 4).
     */
    fun drawLineSegmentsInstanced(endpointsXyxy: FloatArray, colorsRgba: FloatArray, instanceCount: Int) {
        if (instanceCount <= 0) return
        var offset = 0
        GPU.useProgram(program)
        GPU.bindVertexArray(vao)
        while (offset < instanceCount) {
            val n = minOf(MAX_LINE_INSTANCES, instanceCount - offset)
            val baseFloat = offset * 4
            val nf = n * 4
            segScratch.clear().put(endpointsXyxy, baseFloat, nf).flip()
            colScratch.clear().put(colorsRgba, baseFloat, nf).flip()

            GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceSegVbo)
            GPU.bufferData(GPU.ARRAY_BUFFER, nf, segScratch, GPU.DYNAMIC_DRAW)

            GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceColVbo)
            GPU.bufferData(GPU.ARRAY_BUFFER, nf, colScratch, GPU.DYNAMIC_DRAW)

            GPU.drawLinesInstanced(0, 2, n)
            offset += n
        }
        GPU.bindVertexArray(null)
    }

    /**
     * Legacy helper: packed vertex list (pairs of xy per line vertex); single RGBA for all segments.
     */
    fun drawLinesRgba(ndcXy: FloatArray, vertexCount: Int, r: Float, g: Float, b: Float, a: Float) {
        if (vertexCount < 2 || vertexCount % 2 != 0) return
        val segCount = vertexCount / 2
        if (segCount > MAX_LINE_INSTANCES) return
        var ei = 0
        val floatCount = vertexCount * 2
        for (fi in 0 until floatCount step 4) {
            packEnds[ei++] = ndcXy[fi]
            packEnds[ei++] = ndcXy[fi + 1]
            packEnds[ei++] = ndcXy[fi + 2]
            packEnds[ei++] = ndcXy[fi + 3]
        }
        for (s in 0 until segCount) {
            val b4 = s * 4
            packCols[b4] = r
            packCols[b4 + 1] = g
            packCols[b4 + 2] = b
            packCols[b4 + 3] = a
        }
        drawLineSegmentsInstanced(packEnds, packCols, segCount)
    }

    fun delete() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(baseVbo)
        GPU.deleteBuffers(instanceSegVbo)
        GPU.deleteBuffers(instanceColVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    companion object {
        const val MAX_LINE_INSTANCES: Int = 8192
        /** Legacy scratch verts for batched diamond outlines ([drawLinesRgba]). */
        const val CLADO_MAX_VERTS: Int = 4096
        const val CLADO_MAX_FLOATS: Int = CLADO_MAX_VERTS * 2

        private const val ATTR_WHICH = 0
        private const val ATTR_ENDPOINTS = 1
        private const val ATTR_COLOR = 2
    }

}
