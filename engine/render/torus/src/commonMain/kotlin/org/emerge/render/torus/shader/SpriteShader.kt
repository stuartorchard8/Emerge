package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put

/**
 * Instanced sprite shader for rendering textured quads from a sprite atlas.
 *
 * Manages its own VAO with a quad triangle-strip (4 vertices) and per-instance
 * buffers for the model matrix, team color ID, UV offset, and alpha.
 *
 * The atlas is divided into a grid of equally-sized frames; the per-instance UV
 * offset selects which frame to display.
 */
class SpriteShader {
    private val program: Int = ShaderFactory.createProgram(
        SpriteShaderSources.vertex(),
        SpriteShaderSources.fragment(),
    )
    private val uSpriteTexture: Int = GPU.getUniformLocation(program, "uSpriteTexture")
    private val uFrameSize: Int = GPU.getUniformLocation(program, "uFrameSize")
    private val uTintColor: Int = GPU.getUniformLocation(program, "uTintColor")
    private val uUseTint: Int = GPU.getUniformLocation(program, "uUseTint")

    private val vao: Int? = GPU.genAndBindVertexArrays()
    private val quadVbo: Int = GPU.genBuffers()

    private val instanceVbo: Int = GPU.genBuffers()
    private val instancePrimaryIdVbo: Int = GPU.genBuffers()
    private val instanceUvXVbo: Int = GPU.genBuffers()
    private val instanceUvYVbo: Int = GPU.genBuffers()
    private val instanceUvWVbo: Int = GPU.genBuffers()
    private val instanceUvHVbo: Int = GPU.genBuffers()
    private val instanceAlphaVbo: Int = GPU.genBuffers()
    private val instanceSquashVbo: Int = GPU.genBuffers()

    private val instanceMatrices = GpuFloatBuffer(MAX_INSTANCES * MAT4_FLOATS)
    private val instancePrimaryIds = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceUvXs = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceUvYs = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceUvWs = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceUvHs = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceAlphas = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceSquashs = GpuFloatBuffer(MAX_INSTANCES)

    init {
        uploadQuad()
        initFloatBuffer(instanceVbo, INSTANCE_ATTR_BASE, 4, 4)
        initFloatBuffer(instancePrimaryIdVbo, INSTANCE_PRIMARY_ID_ATTR)
        initFloatBuffer(instanceUvXVbo, INSTANCE_UV_X_ATTR)
        initFloatBuffer(instanceUvYVbo, INSTANCE_UV_Y_ATTR)
        initFloatBuffer(instanceUvWVbo, INSTANCE_UV_W_ATTR)
        initFloatBuffer(instanceUvHVbo, INSTANCE_UV_H_ATTR)
        initFloatBuffer(instanceAlphaVbo, INSTANCE_ALPHA_ATTR)
        initFloatBuffer(instanceSquashVbo, INSTANCE_SQUASH_ATTR)
    }

    private fun uploadQuad() {
        // Triangle-strip quad: TL, BL, TR, BR
        val verts = floatArrayOf(
            -1f,  1f,
            -1f, -1f,
             1f,  1f,
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

    private fun initFloatBuffer(vbo: Int, attribute: Int, sizeX: Int = 1, sizeY: Int = 1) {
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        val floatSize = 4
        val strideBytes = sizeX * sizeY * floatSize
        for (col in 0 until sizeY) {
            val loc = attribute + col
            GPU.enableVertexAttribArray(loc)
            GPU.putVertexAttribPointer(loc, sizeX, GPU.FLOAT, false, strideBytes, col * sizeX * floatSize)
            GPU.vertexAttribDivisor(loc, 1)
        }
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun drawInstanced(
        instanceCount: Int,
        matricesColMajor: FloatArray,
        primaryIds: FloatArray,
        uvXs: FloatArray,
        uvYs: FloatArray,
        uvWs: FloatArray,
        uvHs: FloatArray,
        alphas: FloatArray,
        squashs: FloatArray,
        textureId: Int,
        frameSizeX: Float,
        frameSizeY: Float,
    ) {
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        GPU.activeTexture(SPRITE_TEXTURE_UNIT)
        GPU.bindTexture2D(textureId)
        GPU.putUniform1i(uSpriteTexture, SPRITE_TEXTURE_UNIT)
        GPU.putUniform2f(uFrameSize, frameSizeX, frameSizeY)

        val n = instanceCount.coerceIn(0, MAX_INSTANCES)
        bind(instanceVbo, instanceMatrices, matricesColMajor, n * MAT4_FLOATS)
        bind(instancePrimaryIdVbo, instancePrimaryIds, primaryIds, n)
        bind(instanceUvXVbo, instanceUvXs, uvXs, n)
        bind(instanceUvYVbo, instanceUvYs, uvYs, n)
        bind(instanceUvWVbo, instanceUvWs, uvWs, n)
        bind(instanceUvHVbo, instanceUvHs, uvHs, n)
        bind(instanceAlphaVbo, instanceAlphas, alphas, n)
        bind(instanceSquashVbo, instanceSquashs, squashs, n)

        GPU.drawTrianglesInstanced(0, QUAD_VERTEX_COUNT, n)
    }

    private fun bind(vbo: Int, buffer: GpuFloatBuffer, array: FloatArray, count: Int) {
        buffer.clear().put(array, 0, count).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(quadVbo)
        GPU.deleteBuffers(instanceVbo)
        GPU.deleteBuffers(instancePrimaryIdVbo)
        GPU.deleteBuffers(instanceUvXVbo)
        GPU.deleteBuffers(instanceUvYVbo)
        GPU.deleteBuffers(instanceUvWVbo)
        GPU.deleteBuffers(instanceUvHVbo)
        GPU.deleteBuffers(instanceAlphaVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    companion object {
        private const val INSTANCE_ATTR_BASE = 1
        private const val INSTANCE_PRIMARY_ID_ATTR = 5
        private const val INSTANCE_UV_X_ATTR = 6
        private const val INSTANCE_UV_Y_ATTR = 7
        private const val INSTANCE_UV_W_ATTR = 8
        private const val INSTANCE_UV_H_ATTR = 9
        private const val INSTANCE_ALPHA_ATTR = 10
        private const val INSTANCE_SQUASH_ATTR = 11
        private const val MAT4_FLOATS = 16
        const val MAX_INSTANCES = 2000
        private const val SPRITE_TEXTURE_UNIT = 1
        private const val QUAD_VERTEX_COUNT = 4
    }
}
