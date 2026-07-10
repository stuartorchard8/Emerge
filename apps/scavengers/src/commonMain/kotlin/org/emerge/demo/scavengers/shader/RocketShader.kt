package org.emerge.demo.scavengers.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Scavengers rocket-body renderer (the cone/bell/window silhouette).
 *
 * Shares the engine VAO and vertex-attribute layout with CircleShader (locations 0,
 * 1-4, 5, 6, 7) so it draws from the same shared triangle. Because sibling shaders
 * overwrite VAO attribute bindings at overlapping locations, this rebinds its VBOs
 * before each draw — same pattern as the drockets PlanetShader.
 */
class RocketShader {
    private val program: Int = ShaderFactory.createProgram(
        RocketShaderSources.vertex(),
        RocketShaderSources.fragment(),
    )

    private val instanceVbo: Int = GPU.genBuffers()
    private val instancePrimaryIdVbo: Int = GPU.genBuffers()
    private val instanceSecondaryColorVbo: Int = GPU.genBuffers()
    private val instanceTintColorVbo: Int = GPU.genBuffers()

    private val instanceMatrices = GpuFloatBuffer(MAX_INSTANCES * Mat4.FLOATS)
    private val instancePrimaryIds = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceSecondaryColors = GpuFloatBuffer(MAX_INSTANCES * 3)
    private val instanceTintColors = GpuFloatBuffer(MAX_INSTANCES * 3)

    fun drawInstanced(
        vOffset: Int,
        instanceCount: Int,
        matricesColMajor: FloatArray,
        primaryIds: FloatArray,
        secondaryColorsRgb: FloatArray,
        tintColorsRgb: FloatArray,
    ) {
        GPU.useProgram(program)
        val n = instanceCount.coerceIn(0, MAX_INSTANCES)

        bindAndSetup(instanceVbo         , instanceMatrices  , matricesColMajor, n * Mat4.FLOATS, INSTANCE_ATTR_BASE, 4, 4)
        bindAndSetup(instancePrimaryIdVbo, instancePrimaryIds, primaryIds      , n, INSTANCE_PRIMARY_ID_ATTR)
        bindAndSetup(instanceSecondaryColorVbo, instanceSecondaryColors, secondaryColorsRgb, n * 3, INSTANCE_SECONDARY_COLOR_ATTR, 3)
        bindAndSetup(instanceTintColorVbo, instanceTintColors, tintColorsRgb   , n * 3, INSTANCE_TINT_COLOR_ATTR, 3)

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
        GPU.deleteBuffers(instanceSecondaryColorVbo)
        GPU.deleteBuffers(instanceTintColorVbo)
    }

    companion object {
        private const val INSTANCE_ATTR_BASE = 1
        private const val INSTANCE_PRIMARY_ID_ATTR = 5
        private const val INSTANCE_SECONDARY_COLOR_ATTR = 6
        private const val INSTANCE_TINT_COLOR_ATTR = 7
        const val MAX_INSTANCES = 4096
    }
}
