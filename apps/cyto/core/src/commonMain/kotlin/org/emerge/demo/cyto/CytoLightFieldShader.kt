package org.emerge.demo.cyto

import org.emerge.demo.cyto.shader.FieldShaderSources
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Draws the daylight band as a single full-screen triangle whose fragment shader evaluates the moving band
 * analytically per pixel (see `field.frag`). Continuous by construction — no mesh, no per-frame CPU baking,
 * and defined across the whole screen, torus-wrapped, so there is no tile edge. The band formula mirrors
 * [org.emerge.demo.cyto.sim.CytoLightField]'s moving-band branch; keep the two in sync. Cyto-specific.
 *
 * This is a **white multiply over the finished scene**, so the caller must draw it LAST in the world pass
 * with [GPU.setBlendFuncDstColorZero] — never opaque, and never before the cells, or it would erase them.
 */
internal class CytoLightFieldShader {
    private val program = ShaderFactory.createProgram(
        FieldShaderSources.vertex(),
        FieldShaderSources.fragment(),
    )

    private val vao = GPU.genAndBindVertexArrays()
    private val vbo = GPU.genBuffers()

    private val locCenterX = GPU.getUniformLocation(program, "uCenterX")
    private val locHalfViewX = GPU.getUniformLocation(program, "uHalfViewX")
    private val locBandX = GPU.getUniformLocation(program, "uBandX")
    private val locFalloff = GPU.getUniformLocation(program, "uFalloff")
    private val locHalf = GPU.getUniformLocation(program, "uHalf")
    private val locSpan = GPU.getUniformLocation(program, "uSpan")
    private val locNight = GPU.getUniformLocation(program, "uNight")

    init {
        uploadTriangle()
    }

    /** Draw the field for the given camera + band state. [halfViewX]/[centerX] map clip-space x → world x;
     *  [bandX] is the daylight band centre; [falloff]/[half]/[span] are the field's torus constants.
     *  [night] is the scene multiplier at full night (1.0 = no day/night contrast at all). */
    fun draw(centerX: Float, halfViewX: Float, bandX: Float, falloff: Float, half: Float, span: Float, night: Float) {
        GPU.bindVertexArray(vao)
        GPU.useProgram(program)
        GPU.putUniform1f(locCenterX, centerX)
        GPU.putUniform1f(locHalfViewX, halfViewX)
        GPU.putUniform1f(locBandX, bandX)
        GPU.putUniform1f(locFalloff, falloff)
        GPU.putUniform1f(locHalf, half)
        GPU.putUniform1f(locSpan, span)
        GPU.putUniform1f(locNight, night)
        GPU.drawTriangles(0, 3)
    }

    fun deleteProgram() {
        GPU.deleteProgram(program)
        GPU.deleteBuffers(vbo)
        if (vao != null) GPU.deleteVertexArrays(vao)
    }

    /** A single oversized triangle covering the whole clip volume — the standard full-screen-pass trick
     *  (cheaper than a quad, no diagonal seam). Uploaded once. */
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
}
