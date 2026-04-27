package org.emerge.demo.drockets

import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

class HudGlyphShader {
    private val program = ShaderFactory.createProgram(vertexSource(), fragmentSource())
    private val uTexture = GPU.getUniformLocation(program, "uHudTexture")
    private val uColor = GPU.getUniformLocation(program, "uColor")

    private val vao = GPU.genAndBindVertexArrays()
    private val quadVbo = GPU.genBuffers()
    private val centerVbo = GPU.genBuffers()
    private val halfSizeVbo = GPU.genBuffers()
    private val uvRectVbo = GPU.genBuffers()
    private val alphaVbo = GPU.genBuffers()

    private val centerBuffer = GpuFloatBuffer(MAX_GLYPHS * 2)
    private val halfSizeBuffer = GpuFloatBuffer(MAX_GLYPHS * 2)
    private val uvRectBuffer = GpuFloatBuffer(MAX_GLYPHS * 4)
    private val alphaBuffer = GpuFloatBuffer(MAX_GLYPHS)

    init {
        uploadQuad()
        initFloatBuffer(centerVbo, 1, sizeX = 2)
        initFloatBuffer(halfSizeVbo, 2, sizeX = 2)
        initFloatBuffer(uvRectVbo, 3, sizeX = 4)
        initFloatBuffer(alphaVbo, 4, sizeX = 1)
    }

    fun drawInstanced(
        glyphCount: Int,
        centers: FloatArray,
        halfSizes: FloatArray,
        uvRects: FloatArray,
        alphas: FloatArray,
        textureId: Int,
        colorR: Float,
        colorG: Float,
        colorB: Float,
    ) {
        val n = glyphCount.coerceIn(0, MAX_GLYPHS)
        if (n <= 0) return

        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        GPU.activeTexture(HUD_TEXTURE_UNIT)
        GPU.bindTexture2D(textureId)
        GPU.putUniform1i(uTexture, HUD_TEXTURE_UNIT)
        GPU.putUniform4fv(uColor, floatArrayOf(colorR, colorG, colorB, 1f), 1)

        bind(centerVbo, centerBuffer, centers, n * 2)
        bind(halfSizeVbo, halfSizeBuffer, halfSizes, n * 2)
        bind(uvRectVbo, uvRectBuffer, uvRects, n * 4)
        bind(alphaVbo, alphaBuffer, alphas, n)

        GPU.drawTrianglesInstanced(0, QUAD_VERTEX_COUNT, n)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(quadVbo)
        GPU.deleteBuffers(centerVbo)
        GPU.deleteBuffers(halfSizeVbo)
        GPU.deleteBuffers(uvRectVbo)
        GPU.deleteBuffers(alphaVbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    private fun uploadQuad() {
        val verts = floatArrayOf(
            -1f, 1f,
            -1f, -1f,
            1f, 1f,
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

    private fun initFloatBuffer(vbo: Int, attribute: Int, sizeX: Int) {
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(attribute)
        GPU.putVertexAttribPointer(attribute, sizeX, GPU.FLOAT, false, sizeX * 4, 0)
        GPU.vertexAttribDivisor(attribute, 1)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private fun bind(vbo: Int, buffer: GpuFloatBuffer, array: FloatArray, count: Int) {
        buffer.clear().put(array, 0, count).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.bufferData(GPU.ARRAY_BUFFER, count, buffer, GPU.DYNAMIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    companion object {
        const val MAX_GLYPHS = 256
        private const val HUD_TEXTURE_UNIT = 2
        private const val QUAD_VERTEX_COUNT = 4

        private fun vertexSource(): String = """
            #version ${GPU.shaderVersion}
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 iCenter;
            layout(location = 2) in vec2 iHalfSize;
            layout(location = 3) in vec4 iUvRect;
            layout(location = 4) in float iAlpha;

            out vec2 vUv;
            out float vAlpha;

            void main() {
                vec2 localUv = vec2(
                    aPos.x * 0.5 + 0.5,
                    1.0 - (aPos.y * 0.5 + 0.5)
                );
                vUv = iUvRect.xy + localUv * iUvRect.zw;
                vAlpha = iAlpha;
                gl_Position = vec4(iCenter + aPos * iHalfSize, 0.0, 1.0);
            }
        """.trimIndent()

        private fun fragmentSource(): String {
            val precision = if (GPU.shaderVersion.contains("es")) {
                "precision highp float;\nprecision highp int;"
            } else ""
            return """
                #version ${GPU.shaderVersion}
                $precision

                in vec2 vUv;
                in float vAlpha;
                out vec4 fragColor;

                uniform sampler2D uHudTexture;
                uniform vec4 uColor;

                void main() {
                    vec4 texel = texture(uHudTexture, vUv);
                    if (texel.a < 0.01) discard;
                    fragColor = vec4(uColor.rgb, texel.a * vAlpha);
                }
            """.trimIndent()
        }
    }
}
