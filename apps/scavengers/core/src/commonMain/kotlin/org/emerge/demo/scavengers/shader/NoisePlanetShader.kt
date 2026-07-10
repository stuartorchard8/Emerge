package org.emerge.demo.scavengers.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.shader.NoiseTexture
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Scavengers procedural-planet renderer: a tinted disc textured with multi-octave
 * value noise.
 *
 * Shares the engine VAO and vertex-attribute layout with CircleShader (locations 0,
 * 1-4, 5, 6, 7, 8) so it draws from the same shared triangle, rebinding its VBOs
 * before each draw — same pattern as the drockets PlanetShader.
 */
class NoisePlanetShader {
    private val program: Int = ShaderFactory.createProgram(
        PlanetNoiseShaderSources.vertex(),
        PlanetNoiseShaderSources.fragment(),
    )
    private val uNoiseTexture: Int = GPU.getUniformLocation(program, "uNoiseTexture")
    private val noiseTexture: Int = GPU.genTextures()

    private val instanceVbo: Int = GPU.genBuffers()
    private val instancePrimaryIdVbo: Int = GPU.genBuffers()
    private val instanceSecondaryIdVbo: Int = GPU.genBuffers()
    private val instanceRadiusVbo: Int = GPU.genBuffers()
    private val instanceTintColorVbo: Int = GPU.genBuffers()

    private val instanceMatrices = GpuFloatBuffer(MAX_INSTANCES * Mat4.FLOATS)
    private val instancePrimaryIds = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceSecondaryIds = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceRadii = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceTintColors = GpuFloatBuffer(MAX_INSTANCES * 3)

    init {
        uploadNoiseTexture()
    }

    fun drawInstanced(
        vOffset: Int,
        instanceCount: Int,
        matricesColMajor: FloatArray,
        primaryIds: FloatArray,
        secondaryIds: FloatArray,
        radii: FloatArray,
        tintColorsRgb: FloatArray,
    ) {
        GPU.useProgram(program)
        GPU.activeTexture(NOISE_TEXTURE_UNIT)
        GPU.bindTexture2D(noiseTexture)

        val n = instanceCount.coerceIn(0, MAX_INSTANCES)
        bindAndSetup(instanceVbo         , instanceMatrices  , matricesColMajor, n * Mat4.FLOATS, INSTANCE_ATTR_BASE, 4, 4)
        bindAndSetup(instancePrimaryIdVbo, instancePrimaryIds, primaryIds      , n, INSTANCE_PRIMARY_ID_ATTR)
        bindAndSetup(instanceSecondaryIdVbo, instanceSecondaryIds, secondaryIds , n, INSTANCE_SECONDARY_ID_ATTR)
        bindAndSetup(instanceRadiusVbo   , instanceRadii     , radii           , n, INSTANCE_RADIUS_ATTR)
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
        GPU.deleteTextures(noiseTexture)
        GPU.deleteProgram(program)
        GPU.deleteBuffers(instanceVbo)
        GPU.deleteBuffers(instancePrimaryIdVbo)
        GPU.deleteBuffers(instanceSecondaryIdVbo)
        GPU.deleteBuffers(instanceRadiusVbo)
        GPU.deleteBuffers(instanceTintColorVbo)
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
        private const val INSTANCE_RADIUS_ATTR = 7
        private const val INSTANCE_TINT_COLOR_ATTR = 8
        const val MAX_INSTANCES = 256
        private const val NOISE_TEXTURE_UNIT = 0
        private const val NOISE_TEXTURE_SIZE = 128
    }
}
