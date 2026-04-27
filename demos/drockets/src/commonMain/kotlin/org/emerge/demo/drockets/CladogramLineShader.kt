package org.emerge.demo.drockets

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Minimal NDC line shader for the lineage cladogram (independent segments, [GL_LINES]).
 */
class CladogramLineShader {
    private val program: Int = ShaderFactory.createProgram(vertexSource(), fragmentSource())
    private val uColor: Int = GPU.getUniformLocation(program, "uColor")
    private val vao: Int? = GPU.genAndBindVertexArrays()
    private val vbo: Int = GPU.genBuffers()
    private val scratch: GpuFloatBuffer = GpuFloatBuffer(CLADO_MAX_FLOATS)

    init {
        GPU.bindVertexArray(vao)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        val empty = GpuFloatBuffer(4)
        empty.put(floatArrayOf(0f, 0f, 0f, 0f)).flip()
        GPU.bufferData(GPU.ARRAY_BUFFER, 4, empty, GPU.DYNAMIC_DRAW)
        GPU.bindVertexArray(null)
    }

    /**
     * @param ndcXy pairs (x, y) in clip space; x spans the right panel (0 = screen center, 1 = right edge).
     * @param vertexCount number of vertices (must be even).
     */
    fun drawLinesRgba(ndcXy: FloatArray, vertexCount: Int, r: Float, g: Float, b: Float, a: Float) {
        if (vertexCount < 2 || vertexCount % 2 != 0) return
        val nFloat = vertexCount * 2
        if (nFloat > ndcXy.size || nFloat > CLADO_MAX_FLOATS) return
        scratch.clear()
        scratch.put(ndcXy, 0, nFloat).flip()
        GPU.bindVertexArray(vao)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, nFloat, scratch, GPU.DYNAMIC_DRAW)
        GPU.useProgram(program)
        GPU.putUniform4fv(uColor, floatArrayOf(r, g, b, a), 1)
        GPU.drawLines(0, vertexCount)
    }

    fun delete() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(vbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    companion object {
        const val CLADO_MAX_VERTS: Int = 4096
        const val CLADO_MAX_FLOATS: Int = CLADO_MAX_VERTS * 2
    }

    private fun precisionBlock(version: String): String =
        if (version.contains("es")) {
            "precision highp float;\nprecision highp int;"
        } else {
            ""
        }

    private fun vertexSource(): String {
        val v = GPU.shaderVersion
        return """
        #version $v
        layout(location = 0) in vec2 aPos;
        void main() {
            gl_Position = vec4(aPos.xy, 0.0, 1.0);
        }
        """.trimIndent()
    }

    private fun fragmentSource(): String {
        val v = GPU.shaderVersion
        return """
        #version $v
        ${precisionBlock(v)}
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            fragColor = uColor;
        }
        """.trimIndent()
    }
}
