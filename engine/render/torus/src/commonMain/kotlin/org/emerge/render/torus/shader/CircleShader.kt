package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4

/**
 * Generic instanced primitive shader: a soft tinted disc, a flat tinted triangle, or a soft tinted
 * annulus, selected per-instance by the `shapes` attribute ([SHAPE_DISC] / [SHAPE_TRIANGLE] /
 * [SHAPE_ANNULUS]). Game-specific looks (e.g. rocket bodies, procedural planets) live in their own
 * shaders in the owning demo module.
 *
 * [SHAPE_ANNULUS] needs one number the other two don't — the hole's radius — and takes it from the
 * `primaryIds` slot, which this shader's fragment stage otherwise ignores (it stopped hashing ids into
 * hues long ago). Callers drawing discs or triangles can keep passing whatever they pass today; the
 * slot is only read when the shape is an annulus.
 */
class CircleShader {
    private val vSrc = CircleShaderSources.vertex()
    private val fSrc = CircleShaderSources.fragment()
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)

    private val instanceVbo: Int = GPU.genBuffers()
    private val instancePrimaryIdVbo: Int = GPU.genBuffers()
    private val instanceShapeVbo: Int = GPU.genBuffers()
    private val instanceAlphaVbo: Int = GPU.genBuffers()
    private val instanceTintColorVbo: Int = GPU.genBuffers()
    private val instanceMatrices = GpuFloatBuffer(MAX_INSTANCES * Mat4.FLOATS)
    private val instancePrimaryIds = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceShapes = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceAlphas = GpuFloatBuffer(MAX_INSTANCES)
    private val instanceTintColors = GpuFloatBuffer(MAX_INSTANCES * 3)

    init {
        initFloatBuffer(instanceVbo, INSTANCE_ATTR_BASE, 4, 4)
        initFloatBuffer(instancePrimaryIdVbo, INSTANCE_PRIMARY_ID_ATTR)
        initFloatBuffer(instanceShapeVbo, INSTANCE_SHAPE_ATTR)
        initFloatBuffer(instanceAlphaVbo, INSTANCE_ALPHA_ATTR)
        initFloatBuffer(instanceTintColorVbo, INSTANCE_TINT_COLOR_ATTR, sizeX = 3)
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
        shapes: FloatArray,
        alphas: FloatArray,
        tintColorsRgb: FloatArray,
    ) {
        GPU.useProgram(program)

        val n = instanceCount.coerceIn(0, MAX_INSTANCES)
        bindAndSetup(instanceVbo         , instanceMatrices  , matricesColMajor, n * Mat4.FLOATS, INSTANCE_ATTR_BASE, 4, 4)
        bindAndSetup(instancePrimaryIdVbo, instancePrimaryIds, primaryIds      , n, INSTANCE_PRIMARY_ID_ATTR)
        bindAndSetup(instanceShapeVbo    , instanceShapes    , shapes          , n, INSTANCE_SHAPE_ATTR)
        bindAndSetup(instanceAlphaVbo    , instanceAlphas    , alphas          , n, INSTANCE_ALPHA_ATTR)
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
        GPU.deleteBuffers(instanceShapeVbo)
        GPU.deleteBuffers(instanceAlphaVbo)
        GPU.deleteBuffers(instanceTintColorVbo)
    }

    companion object {
        private const val INSTANCE_ATTR_BASE = 1
        private const val INSTANCE_PRIMARY_ID_ATTR = 5
        private const val INSTANCE_SHAPE_ATTR = 6
        private const val INSTANCE_ALPHA_ATTR = 7
        private const val INSTANCE_TINT_COLOR_ATTR = 8
        const val MAX_INSTANCES = 10000

        /** `shapes` values. See the class doc — [SHAPE_ANNULUS] also reads the `primaryIds` slot, as its
         *  hole radius in local units (0 = solid, →1 = a hairline ring at the outer edge). */
        const val SHAPE_DISC = 0f
        const val SHAPE_TRIANGLE = 1f
        const val SHAPE_ANNULUS = 2f
    }
}
