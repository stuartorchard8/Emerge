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
    private val instancePrimaryIdVbo: Int = GPU.genBuffers()
    private val instanceSecondaryIdVbo: Int = GPU.genBuffers()
    private val instanceShapeVbo: Int = GPU.genBuffers()
    private val instanceAlphaVbo: Int = GPU.genBuffers()
    private val instanceRadiusVbo: Int = GPU.genBuffers()
    private val instanceMatrices: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_INSTANCES * MAT4_FLOATS * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val instancePrimaryIds: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_INSTANCES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val instanceSecondaryIds: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_INSTANCES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val instanceShapes: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_INSTANCES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val instanceAlphas: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_INSTANCES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val instanceRadii: FloatBuffer =
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

        // Instance primary id as float attribute, divisor = 1
        GPU.bindBuffer(GPU.ARRAY_BUFFER, instancePrimaryIdVbo)
        GPU.enableVertexAttribArray(INSTANCE_PRIMARY_ID_ATTR)
        GPU.putVertexAttribPointer(
            INSTANCE_PRIMARY_ID_ATTR,
            1,
            GPU.FLOAT,
            false,
            4,
            0,
        )
        GPU.vertexAttribDivisor(INSTANCE_PRIMARY_ID_ATTR, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        // Instance secondary id as float attribute, divisor = 1
        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceSecondaryIdVbo)
        GPU.enableVertexAttribArray(INSTANCE_SECONDARY_ID_ATTR)
        GPU.putVertexAttribPointer(
            INSTANCE_SECONDARY_ID_ATTR,
            1,
            GPU.FLOAT,
            false,
            4,
            0,
        )
        GPU.vertexAttribDivisor(INSTANCE_SECONDARY_ID_ATTR, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        // Instance shape as float attribute, divisor = 1
        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceShapeVbo)
        GPU.enableVertexAttribArray(INSTANCE_SHAPE_ATTR)
        GPU.putVertexAttribPointer(
            INSTANCE_SHAPE_ATTR,
            1,
            GPU.FLOAT,
            false,
            4,
            0,
        )
        GPU.vertexAttribDivisor(INSTANCE_SHAPE_ATTR, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        // Instance alpha as float attribute, divisor = 1
        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceAlphaVbo)
        GPU.enableVertexAttribArray(INSTANCE_ALPHA_ATTR)
        GPU.putVertexAttribPointer(
            INSTANCE_ALPHA_ATTR,
            1,
            GPU.FLOAT,
            false,
            4,
            0,
        )
        GPU.vertexAttribDivisor(INSTANCE_ALPHA_ATTR, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        // Instance radius as float attribute, divisor = 1
        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceRadiusVbo)
        GPU.enableVertexAttribArray(INSTANCE_RADIUS_ATTR)
        GPU.putVertexAttribPointer(
            INSTANCE_RADIUS_ATTR,
            1,
            GPU.FLOAT,
            false,
            4,
            0,
        )
        GPU.vertexAttribDivisor(INSTANCE_RADIUS_ATTR, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun drawInstanced(
        vOffset: Int,
        instanceCount: Int,
        matricesColMajor: FloatArray,
        primaryIds: FloatArray,
        secondaryIds: FloatArray,
        shapes: FloatArray,
        alphas: FloatArray,
        radii: FloatArray,
    ) {
        GPU.useProgram(program)

        val n = instanceCount.coerceIn(0, MAX_INSTANCES)
        instanceMatrices.clear()
        instanceMatrices.put(matricesColMajor, 0, n * MAT4_FLOATS)
        instanceMatrices.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceVbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, n * MAT4_FLOATS, instanceMatrices, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        instancePrimaryIds.clear()
        instancePrimaryIds.put(primaryIds, 0, n)
        instancePrimaryIds.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instancePrimaryIdVbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, n, instancePrimaryIds, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        instanceSecondaryIds.clear()
        instanceSecondaryIds.put(secondaryIds, 0, n)
        instanceSecondaryIds.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceSecondaryIdVbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, n, instanceSecondaryIds, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        instanceShapes.clear()
        instanceShapes.put(shapes, 0, n)
        instanceShapes.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceShapeVbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, n, instanceShapes, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        instanceAlphas.clear()
        instanceAlphas.put(alphas, 0, n)
        instanceAlphas.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceAlphaVbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, n, instanceAlphas, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        instanceRadii.clear()
        instanceRadii.put(radii, 0, n)
        instanceRadii.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, instanceRadiusVbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, n, instanceRadii, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)

        GPU.drawTrianglesInstanced(vOffset, 3, n)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(instanceVbo)
        GPU.deleteBuffers(instancePrimaryIdVbo)
        GPU.deleteBuffers(instanceSecondaryIdVbo)
        GPU.deleteBuffers(instanceShapeVbo)
        GPU.deleteBuffers(instanceAlphaVbo)
        GPU.deleteBuffers(instanceRadiusVbo)
    }

    companion object {
        private const val INSTANCE_ATTR_BASE = 1
        private const val INSTANCE_PRIMARY_ID_ATTR = 5
        private const val INSTANCE_SECONDARY_ID_ATTR = 6
        private const val INSTANCE_SHAPE_ATTR = 7
        private const val INSTANCE_ALPHA_ATTR = 8
        private const val INSTANCE_RADIUS_ATTR = 9
        private const val MAT4_FLOATS = 16
        private const val MAX_INSTANCES = 1000
    }
}
