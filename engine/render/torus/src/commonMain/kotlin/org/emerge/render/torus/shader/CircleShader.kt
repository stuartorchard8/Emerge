package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CircleShader {
    private val vSrc = CircleShaderSources.vertex()
    private val fSrc = CircleShaderSources.fragment()
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)

    private val instanceVbo: Int = GPU.genBuffers()
    private val instanceIdVbo: Int = GPU.genBuffers()
    private val instanceMatrices: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_INSTANCES * MAT4_FLOATS * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val instanceIds: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_INSTANCES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    init {
        // Instance matrix as 4 vec4 attributes (one per column), divisor = 1
        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceVbo)
        val strideBytes = MAT4_FLOATS * 4
        for (col in 0 until 4) {
            val loc = INSTANCE_ATTR_BASE + col
            GPU.enableVertexAttribArray(loc)
            GPU.putVertexAttribPointer(
                loc,
                4,
                GPU.FLOAT,
                false,
                strideBytes,
                col * 4 * 4,
            )
            GPU.vertexAttribDivisor(loc, 1)
        }
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        // Instance body id as float attribute, divisor = 1
        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceIdVbo)
        GPU.enableVertexAttribArray(INSTANCE_ID_ATTR)
        GPU.putVertexAttribPointer(
            INSTANCE_ID_ATTR,
            1,
            GPU.FLOAT,
            false,
            4,
            0,
        )
        GPU.vertexAttribDivisor(INSTANCE_ID_ATTR, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun drawInstanced(vOffset: Int, instanceCount: Int, matricesColMajor: FloatArray, ids: FloatArray) {
        GPU.useProgram(program)

        val n = instanceCount.coerceIn(0, MAX_INSTANCES)
        instanceMatrices.clear()
        instanceMatrices.put(matricesColMajor, 0, n * MAT4_FLOATS)
        instanceMatrices.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceVbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, n * MAT4_FLOATS, instanceMatrices, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        instanceIds.clear()
        instanceIds.put(ids, 0, n)
        instanceIds.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceIdVbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, n, instanceIds, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        GPU.drawTrianglesInstanced(vOffset, 3, n)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(instanceVbo)
        GPU.deleteBuffers(instanceIdVbo)
    }

    companion object {
        private const val INSTANCE_ATTR_BASE = 1
        private const val INSTANCE_ID_ATTR = 5
        private const val MAT4_FLOATS = 16
        private const val MAX_INSTANCES = 1000
    }
}
