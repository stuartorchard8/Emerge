package org.emerge.demo.outofspace.shader

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

/**
 * **Every exhaust plume in the game, in one instanced draw.**
 *
 * One triangle per firing engine, in *plume space* — see `exhaust.vert`, which states the frame. The
 * caller supplies a matrix carrying that frame to clip space and four vectors of parameters per
 * plume; what those parameters *mean* on screen is entirely `exhaust.frag`'s, and nothing in this
 * file has an opinion about it.
 *
 * ### Why a shader at all, when the rest of the world is rectangles
 *
 * [org.emerge.demo.outofspace.OutofspaceRenderer] draws the entire vessel through one
 * `UiRectRenderer` batch, and that is the right trade for a tile game: a tile is a rect, and a rect
 * costs nothing. A plume is the first thing in this game that is genuinely **not** a tile. It is a
 * continuous field with a soft edge, it flickers faster than the sim ticks, and its shape is a
 * function of numbers that change every tick. Drawing it out of rectangles would mean hundreds of
 * them per motor, re-laid on the CPU every frame, to approximate something a fragment shader does in
 * one triangle.
 *
 * ⚠️ **The parameters arrive physical, not normalised.** Kilometres a second, kilokelvin, kilograms,
 * grams per mole. A shader handed a pre-normalised 0..1 has had the interesting decision — *what
 * counts as fast* — taken for it somewhere it cannot be seen or tuned.
 */
class ExhaustShader(private val maxPlumes: Int = DEFAULT_MAX_PLUMES) {

    private val program = ShaderFactory.createProgram(
        ExhaustShaderSources.vertex(),
        ExhaustShaderSources.fragment(),
    )
    private val timeUniform = GPU.getUniformLocation(program, "uTime")

    private val vao = GPU.genAndBindVertexArrays()
    private val meshVbo = GPU.genBuffers()
    private val matrixVbo = GPU.genBuffers()
    private val flowVbo = GPU.genBuffers()
    private val stuffVbo = GPU.genBuffers()
    private val shapeVbo = GPU.genBuffers()

    private val matrixBuffer = GpuFloatBuffer(maxPlumes * Mat4.FLOATS)
    private val flowBuffer = GpuFloatBuffer(maxPlumes * 4)
    private val stuffBuffer = GpuFloatBuffer(maxPlumes * 4)
    private val shapeBuffer = GpuFloatBuffer(maxPlumes * 4)

    init {
        uploadMesh()
        declareInstanceAttribute(matrixVbo, MATRIX_ATTR, columns = 4)
        declareInstanceAttribute(flowVbo, FLOW_ATTR)
        declareInstanceAttribute(stuffVbo, STUFF_ATTR)
        declareInstanceAttribute(shapeVbo, SHAPE_ATTR)
    }

    /**
     * @param matrices plume space → clip, column-major, [Mat4.FLOATS] per plume
     * @param flows `(km/s, kilokelvin, kg/tick, throttle 0..1)` per plume
     * @param stuffs `(r, g, b, g/mol)` per plume
     * @param shapes `(length tiles, mouth width tiles, nozzle anchor, seed)` per plume
     * @param seconds a clock for the fragment stage; wrapped by the caller, since a float loses its
     *   fractional bits after a few hours of uptime and a plume that stops flickering is a bug that
     *   takes a long afternoon to reproduce
     */
    fun drawInstanced(
        count: Int,
        matrices: FloatArray,
        flows: FloatArray,
        stuffs: FloatArray,
        shapes: FloatArray,
        seconds: Float,
    ) {
        if (count <= 0) return
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        GPU.putUniform1f(timeUniform, seconds)
        var done = 0
        while (done < count) {
            val n = minOf(maxPlumes, count - done)
            upload(matrixVbo, matrixBuffer, matrices, done * Mat4.FLOATS, n * Mat4.FLOATS)
            upload(flowVbo, flowBuffer, flows, done * 4, n * 4)
            upload(stuffVbo, stuffBuffer, stuffs, done * 4, n * 4)
            upload(shapeVbo, shapeBuffer, shapes, done * 4, n * 4)
            GPU.drawTrianglesInstanced(0, MESH_VERTEX_COUNT, n)
            done += n
        }
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(meshVbo)
        GPU.deleteBuffers(matrixVbo)
        GPU.deleteBuffers(flowVbo)
        GPU.deleteBuffers(stuffVbo)
        GPU.deleteBuffers(shapeVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    /**
     * The triangle, in plume space: tip on the axis at x = 0, mouth corners at x = 1, y = ±½.
     *
     * ⛔ **Those two halves are what make the long edges `y = ±x/2` exactly**, which is the identity
     * the fragment stage divides by to find out how far across the jet a fragment is. A mesh of some
     * other width would still draw a triangle and would silently mis-scale every plume's edge.
     */
    private fun uploadMesh() {
        val vertices = floatArrayOf(
            0f, 0f,
            1f, -0.5f,
            1f, 0.5f,
        )
        val buffer = GpuFloatBuffer(vertices.size)
        buffer.put(vertices).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, meshVbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * FLOAT_BYTES, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, vertices.size, buffer, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    /** One vec4 per instance, or [columns] of them for a matrix. */
    private fun declareInstanceAttribute(vbo: Int, attribute: Int, columns: Int = 1) {
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        val stride = 4 * columns * FLOAT_BYTES
        for (col in 0 until columns) {
            val location = attribute + col
            GPU.enableVertexAttribArray(location)
            GPU.putVertexAttribPointer(location, 4, GPU.FLOAT, false, stride, col * 4 * FLOAT_BYTES)
            GPU.vertexAttribDivisor(location, 1)
        }
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun upload(vbo: Int, buffer: GpuFloatBuffer, array: FloatArray, offset: Int, count: Int) {
        buffer.clear().put(array, offset, count).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    companion object {
        private const val MATRIX_ATTR = 1
        private const val FLOW_ATTR = 5
        private const val STUFF_ATTR = 6
        private const val SHAPE_ATTR = 7

        private const val FLOAT_BYTES = 4
        private const val MESH_VERTEX_COUNT = 3

        /**
         * How many plumes go out in one upload. Generous — a vessel with sixty-four motors aboard is
         * already an odd ship — and anything past it batches rather than being dropped, the lesson
         * `UiRectRenderer` learned the hard way.
         */
        const val DEFAULT_MAX_PLUMES = 64
    }
}
