package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CircleShader {
    private val vSrc = CircleShaderSources.vertex()
    private val fSrc = CircleShaderSources.fragment()
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)
    private val uNoiseTexture: Int = GPU.getUniformLocation(program, "uNoiseTexture")
    private val noiseTexture: Int = GPU.genTextures()

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
        uploadNoiseTexture()

        initFloatBuffer(instanceVbo, INSTANCE_ATTR_BASE, 4, 4)
        initFloatBuffer(instancePrimaryIdVbo, INSTANCE_PRIMARY_ID_ATTR)
        initFloatBuffer(instanceSecondaryIdVbo, INSTANCE_SECONDARY_ID_ATTR)
        initFloatBuffer(instanceShapeVbo, INSTANCE_SHAPE_ATTR)
        initFloatBuffer(instanceAlphaVbo, INSTANCE_ALPHA_ATTR)
        initFloatBuffer(instanceRadiusVbo, INSTANCE_RADIUS_ATTR)
    }

    fun initFloatBuffer(vbo: Int, attribute: Int, sizeX: Int = 1, sizeY: Int = 1) {
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        val floatSize = 4
        val strideBytes = sizeX * sizeY * floatSize
        for (col in 0 until sizeY) {
            val loc = attribute + col
            GPU.enableVertexAttribArray(loc)
            GPU.putVertexAttribPointer(
                loc,
                sizeX,
                GPU.FLOAT,
                false,
                strideBytes,
                col * sizeX * floatSize,
            )
            GPU.vertexAttribDivisor(loc, 1)
        }
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
        GPU.activeTexture(NOISE_TEXTURE_UNIT)
        GPU.bindTexture2D(noiseTexture)

        val n = instanceCount.coerceIn(0, MAX_INSTANCES)
        bind(instanceVbo           , instanceMatrices    , matricesColMajor, n * MAT4_FLOATS)
        bind(instancePrimaryIdVbo  , instancePrimaryIds  , primaryIds      , n)
        bind(instanceSecondaryIdVbo, instanceSecondaryIds, secondaryIds    , n)
        bind(instanceShapeVbo      , instanceShapes      , shapes          , n)
        bind(instanceAlphaVbo      , instanceAlphas      , alphas          , n)
        bind(instanceRadiusVbo     , instanceRadii       , radii           , n)

        GPU.drawTrianglesInstanced(vOffset, 3, n)
    }

    fun bind(vbo: Int, buffer: FloatBuffer, array: FloatArray, count: Int) {
        buffer.clear()
        buffer.put(array, 0, count)
        buffer.flip()

        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun deleteProgram() {
        GPU.deleteTextures(noiseTexture)
        GPU.deleteProgram(program)
        GPU.deleteBuffers(instanceVbo)
        GPU.deleteBuffers(instancePrimaryIdVbo)
        GPU.deleteBuffers(instanceSecondaryIdVbo)
        GPU.deleteBuffers(instanceShapeVbo)
        GPU.deleteBuffers(instanceAlphaVbo)
        GPU.deleteBuffers(instanceRadiusVbo)
    }

    private fun uploadNoiseTexture() {
        GPU.useProgram(program)
        GPU.activeTexture(NOISE_TEXTURE_UNIT)
        GPU.bindTexture2D(noiseTexture)
        GPU.configureTexture2DRepeatLinear()
        GPU.uploadTextureR8(NOISE_TEXTURE_SIZE, NOISE_TEXTURE_SIZE, NoiseTexture.createTileable(NOISE_TEXTURE_SIZE))
        GPU.putUniform1i(uNoiseTexture, NOISE_TEXTURE_UNIT)
        GPU.bindTexture2D(0)
    }

    companion object {
        private const val INSTANCE_ATTR_BASE = 1
        private const val INSTANCE_PRIMARY_ID_ATTR = 5
        private const val INSTANCE_SECONDARY_ID_ATTR = 6
        private const val INSTANCE_SHAPE_ATTR = 7
        private const val INSTANCE_ALPHA_ATTR = 8
        private const val INSTANCE_RADIUS_ATTR = 9
        private const val MAT4_FLOATS = 16
        const val MAX_INSTANCES = 1000
        private const val NOISE_TEXTURE_UNIT = 0
        private const val NOISE_TEXTURE_SIZE = 128
    }
}
