package org.emerge.demo.cyto

import org.emerge.demo.cyto.shader.MatterFieldShaderSources
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.NoiseTexture
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Draws the matter density field as a single full-screen triangle sampling a per-frame density texture (the
 * quad-tree rasterised to RGBA by the caller). The texture uses GL_REPEAT + linear filtering, so matter reads
 * as a smooth, seamlessly torus-wrapped density cloud over the whole screen — the same full-screen treatment
 * as the light field, but texture-backed since matter is live data, not an analytic function. Caller wraps
 * blend state (drawn opaque). Cyto-specific.
 */
internal class CytoMatterFieldTexture(private val res: Int) {
    private val program = ShaderFactory.createProgram(
        MatterFieldShaderSources.vertex(),
        MatterFieldShaderSources.fragment(),
    )

    private val vao = GPU.genAndBindVertexArrays()
    private val vbo = GPU.genBuffers()
    private val texture = GPU.genTextures()
    private val noiseTexture = GPU.genTextures()

    private val locTex = GPU.getUniformLocation(program, "uTex")
    private val locNoise = GPU.getUniformLocation(program, "uNoise")
    private val locCenter = GPU.getUniformLocation(program, "uCenter")
    private val locHalfView = GPU.getUniformLocation(program, "uHalfView")
    private val locHalf = GPU.getUniformLocation(program, "uHalf")
    private val locSpan = GPU.getUniformLocation(program, "uSpan")
    private val locTime = GPU.getUniformLocation(program, "uTime")
    private val locAmp = GPU.getUniformLocation(program, "uAmp")

    init {
        uploadTriangle()
        GPU.activeTexture(TEX_UNIT)
        GPU.bindTexture2D(texture)
        GPU.configureTexture2DRepeatLinear()   // GL_REPEAT wrap (torus) + linear filter (smooth cloud)
        // The tileable value-noise that drives the gaseous domain warp — baked + uploaded once.
        GPU.activeTexture(NOISE_UNIT)
        GPU.bindTexture2D(noiseTexture)
        GPU.configureTexture2DRepeatLinear()
        GPU.uploadTextureR8(NOISE_RES, NOISE_RES, NoiseTexture.createTileable(NOISE_RES))
        GPU.bindTexture2D(0)
    }

    /** Upload the [res]×[res] RGBA [pixels] density map and draw the full-screen pass. [centerX]/[centerY] +
     *  [halfViewX]/[halfViewY] map clip space → world; [half]/[span] are the torus constants (one tile → uv
     *  [0,1], the rest wrapping). */
    fun draw(
        pixels: ByteArray,
        centerX: Float, centerY: Float,
        halfViewX: Float, halfViewY: Float,
        half: Float, span: Float,
        time: Float, amp: Float,
    ) {
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        GPU.activeTexture(TEX_UNIT)
        GPU.bindTexture2D(texture)
        GPU.uploadTextureRGBA8(res, res, pixels)
        GPU.activeTexture(NOISE_UNIT)
        GPU.bindTexture2D(noiseTexture)
        GPU.putUniform1i(locTex, TEX_UNIT)
        GPU.putUniform1i(locNoise, NOISE_UNIT)
        GPU.putUniform2f(locCenter, centerX, centerY)
        GPU.putUniform2f(locHalfView, halfViewX, halfViewY)
        GPU.putUniform1f(locHalf, half)
        GPU.putUniform1f(locSpan, span)
        GPU.putUniform1f(locTime, time)
        GPU.putUniform1f(locAmp, amp)
        GPU.drawTriangles(0, 3)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(vbo)
        GPU.deleteTextures(texture)
        GPU.deleteTextures(noiseTexture)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    private fun uploadTriangle() {
        GPU.bindVertexArray(vao)
        val verts = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    private companion object {
        // Distinct from the sprite atlas unit (1) so binds don't clobber each other.
        const val TEX_UNIT = 2
        const val NOISE_UNIT = 3
        const val NOISE_RES = 256
    }
}
