package org.emerge.demo.cyto

import org.emerge.demo.cyto.shader.CellShaderSources
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Non-instanced port of Cyto's CellShader: one draw call per cell, with per-cell
 * uniforms, exactly as the original LibGDX renderer did. Faithful over fast —
 * instancing is a later optimization. Owns a unit-quad VAO ([-1, 1] triangle strip)
 * and reuses a single scratch buffer for the packed neighbour array.
 */
class CytoCellShader {
  private val program: Int = ShaderFactory.createProgram(
    CellShaderSources.vertex(),
    CellShaderSources.fragment(),
  )

  private val uMvp = GPU.getUniformLocation(program, "uMvp[0]")
  private val uTexture = GPU.getUniformLocation(program, "u_texture")
  private val uRadius = GPU.getUniformLocation(program, "u_radius")
  private val uColor = GPU.getUniformLocation(program, "u_color")
  private val uNeighbourCount = GPU.getUniformLocation(program, "u_neighbourCount")
  private val uNeighbour = GPU.getUniformLocation(program, "u_neighbour[0]")

  private val vao: Int? = GPU.genAndBindVertexArrays()
  private val quadVbo: Int = GPU.genBuffers()

  init {
    uploadQuad()
  }

  private fun uploadQuad() {
    // Triangle-strip quad covering [-1, 1]: TL, BL, TR, BR.
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

  /** Bind once per frame before issuing [draw] calls. */
  fun begin(textureId: Int) {
    GPU.bindVertexArray(vao)
    GPU.useProgram(program)
    GPU.activeTexture(CELL_TEXTURE_UNIT)
    GPU.bindTexture2D(textureId)
    GPU.putUniform1i(uTexture, CELL_TEXTURE_UNIT)
  }

  /**
   * Draws one cell. [mvp] is the column-major model-view-projection matrix; [color] is
   * RGBA in 0..1; [neighbours] is a `count * 4` packed array of (relX, relY, radius, 0)
   * world-space membrane data; [radiusUniform] is the original `u_radius` (= cell
   * radius * 2 — the fragment shader divides neighbour data by `u_radius * 2`).
   */
  fun draw(
    mvp: Mat4,
    radiusUniform: Float,
    color: FloatArray,
    neighbours: FloatArray,
    count: Int,
  ) {
    GPU.putUniform4fv(uMvp, mvp.m, 4)
    GPU.putUniform1f(uRadius, radiusUniform)
    GPU.putUniform4fv(uColor, color, 1)
    GPU.putUniform1i(uNeighbourCount, count)
    if (count > 0) {
      GPU.putUniform4fv(uNeighbour, neighbours, count)
    }
    GPU.drawTriangles(0, QUAD_VERTEX_COUNT)
  }

  fun deleteProgram() {
    GPU.deleteProgram(program)
    GPU.deleteBuffers(quadVbo)
    if (vao != null) GPU.deleteVertexArrays(vao)
  }

  companion object {
    private const val CELL_TEXTURE_UNIT = 0
    private const val QUAD_VERTEX_COUNT = 4
    const val MAX_NEIGHBOURS = 8
  }
}
