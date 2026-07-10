package org.emerge.demo.drockets.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4

/**
 * Procedural planet renderer using instanced draw calls.
 *
 * Uses the same vertex attribute layout as CircleShader (locations 0, 1-4, 5, 8)
 * so it shares the same VAO. Because other shaders may overwrite VAO attribute
 * bindings at overlapping locations, this shader rebinds its VBOs before each draw.
 */
class PlanetShader {
    private val program: Int = org.emerge.render.torus.shader.ShaderFactory.createProgram(
        PlanetShaderSources.vertex(),
        PlanetShaderSources.fragment(),
    )

    private val instanceVbo: Int = GPU.genBuffers()
    private val instancePrimaryIdVbo: Int = GPU.genBuffers()
    private val instanceAlphaVbo: Int = GPU.genBuffers()

    private val instanceMatrices = GpuFloatBuffer(MAX_INSTANCES * Mat4.FLOATS)
    private val instancePrimaryIds = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceAlphas = GpuFloatBuffer(MAX_INSTANCES)

    fun drawInstanced(
        vOffset: Int,
        instanceCount: Int,
        matricesColMajor: FloatArray,
        primaryIds: FloatArray,
        alphas: FloatArray,
    ) {
        GPU.useProgram(program)
        val n = instanceCount.coerceIn(0, MAX_INSTANCES)

        bindAndSetup(instanceVbo, instanceMatrices, matricesColMajor, n * Mat4.FLOATS, INSTANCE_ATTR_BASE, 4, 4)
        bindAndSetup(instancePrimaryIdVbo, instancePrimaryIds, primaryIds, n, INSTANCE_PRIMARY_ID_ATTR)
        bindAndSetup(instanceAlphaVbo, instanceAlphas, alphas, n, INSTANCE_ALPHA_ATTR)

        GPU.drawTrianglesInstanced(vOffset, 3, n)
    }

    private fun bindAndSetup(
        vbo: Int,
        buffer: GpuFloatBuffer,
        array: FloatArray,
        count: Int,
        attribute: Int,
        sizeX: Int = 1,
        sizeY: Int = 1,
    ) {
        buffer.clear().put(array, 0, count).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
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

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(instanceVbo)
        GPU.deleteBuffers(instancePrimaryIdVbo)
        GPU.deleteBuffers(instanceAlphaVbo)
    }

    companion object {
        private const val INSTANCE_ATTR_BASE = 1
        private const val INSTANCE_PRIMARY_ID_ATTR = 5
        private const val INSTANCE_ALPHA_ATTR = 8
        const val MAX_INSTANCES = 16
    }
}
