package org.emerge.demo.cyto

import org.emerge.demo.cyto.shader.CellShaderSources
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.put
import org.emerge.render.torus.shader.ShaderFactory

/**
 * Cyto's cell pass: every cell in one instanced draw call.
 *
 * It began as a faithful non-instanced port of the original LibGDX renderer — a draw call per cell, with
 * per-cell uniforms — and that submission cost turned out to dominate the pass. On a 4058-cell world it was
 * ~3.0ms of an 11.5ms frame, and it barely moved with zoom, which is the signature of per-call overhead
 * rather than fill.
 *
 * So the per-cell uniforms became per-instance attributes. Cells are staged with [add] and drawn by
 * [flush]: one buffer upload per attribute, one draw. The membrane blending in the fragment shader is
 * untouched — the neighbour data reaches it as flat varyings instead of uniforms, but the geometry code
 * that carves necks between welded cells is the same code it always was.
 *
 * Staging arrays are allocated once at [MAX_INSTANCES] and reused; [add] past that cap drops the cell
 * rather than growing, matching how the renderer's other instanced passes behave under saturation.
 */
class CytoCellShader {
  private val program: Int = ShaderFactory.createProgram(
    CellShaderSources.vertex(),
    CellShaderSources.fragment(),
  )

  private val uProj = GPU.getUniformLocation(program, "uProj[0]")
  private val uTexture = GPU.getUniformLocation(program, "u_texture")
  private val uBorder = GPU.getUniformLocation(program, "u_border")

  private val vao: Int? = GPU.genAndBindVertexArrays()
  private val quadVbo: Int = GPU.genBuffers()

  private val centerSizeVbo: Int = GPU.genBuffers()
  private val colorVbo: Int = GPU.genBuffers()
  private val neighbourCountVbo: Int = GPU.genBuffers()
  private val neighbourVbo: Int = GPU.genBuffers()

  // Staged instance data, CPU side.
  private val centerSize = FloatArray(MAX_INSTANCES * 4)
  private val colors = FloatArray(MAX_INSTANCES * 4)
  private val neighbourCounts = FloatArray(MAX_INSTANCES)
  private val neighbours = FloatArray(MAX_INSTANCES * MAX_NEIGHBOURS * 4)

  // GPU-side staging buffers, reused every frame.
  private val centerSizeBuf = GpuFloatBuffer(MAX_INSTANCES * 4)
  private val colorBuf = GpuFloatBuffer(MAX_INSTANCES * 4)
  private val neighbourCountBuf = GpuFloatBuffer(MAX_INSTANCES)
  private val neighbourBuf = GpuFloatBuffer(MAX_INSTANCES * MAX_NEIGHBOURS * 4)

  private var count = 0

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

  /** Bind and set the pass-wide state, then stage cells with [add]. [proj] is the shared projection. */
  fun begin(textureId: Int, proj: Mat4) {
    GPU.bindVertexArray(vao)
    GPU.useProgram(program)
    GPU.activeTexture(CELL_TEXTURE_UNIT)
    GPU.bindTexture2D(textureId)
    GPU.putUniform1i(uTexture, CELL_TEXTURE_UNIT)
    GPU.putUniform1f(uBorder, MEMBRANE_BORDER)
    GPU.putUniform4fv(uProj, proj.m, 4)
    count = 0
  }

  /**
   * Stage one cell.
   *
   * @param centerX centre in view coordinates (already wrapped to the pass, as the old MVP's translation was).
   * @param centerY as [centerX].
   * @param radius the cell's logical radius; the quad's half-extent and the shader's divisor are both 2×.
   * @param color RGBA in 0..1.
   * @param neighbourData `neighbourCount * 4` packed (relX, relY, radius, 0) world-space membrane data.
   */
  fun add(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: FloatArray,
    neighbourData: FloatArray,
    neighbourCount: Int,
  ) {
    if (count >= MAX_INSTANCES) return
    val i = count
    val cs = i * 4
    centerSize[cs] = centerX
    centerSize[cs + 1] = centerY
    centerSize[cs + 2] = radius * 2f     // half-extent, and the old u_radius, which were the same value
    centerSize[cs + 3] = 0f

    colors[cs] = color[0]; colors[cs + 1] = color[1]; colors[cs + 2] = color[2]; colors[cs + 3] = color[3]

    val n = neighbourCount.coerceIn(0, MAX_NEIGHBOURS)
    neighbourCounts[i] = n.toFloat()
    // The tail past `n` is never read (the shader loops to the count), so it is left as whatever the
    // previous frame put there rather than cleared.
    val nb = i * MAX_NEIGHBOURS * 4
    for (k in 0 until n * 4) neighbours[nb + k] = neighbourData[k]

    count++
  }

  /** Upload everything staged since [begin] and draw it in one call. */
  fun flush() {
    if (count == 0) return

    uploadInstanceAttr(centerSizeVbo, centerSizeBuf, centerSize, count * 4, ATTR_CENTER_SIZE, 4)
    uploadInstanceAttr(colorVbo, colorBuf, colors, count * 4, ATTR_COLOR, 4)
    uploadInstanceAttr(neighbourCountVbo, neighbourCountBuf, neighbourCounts, count, ATTR_NEIGHBOUR_COUNT, 1)
    // The eight neighbour slots are consecutive attribute locations over one interleaved buffer, so each
    // instance's block of 8 vec4s is read as eight separate inputs at a 32-float stride.
    uploadInstanceAttr(
      neighbourVbo, neighbourBuf, neighbours,
      count * MAX_NEIGHBOURS * 4, ATTR_NEIGHBOUR_BASE, 4, MAX_NEIGHBOURS,
    )

    GPU.drawTrianglesInstanced(0, QUAD_VERTEX_COUNT, count)
    count = 0
  }

  private fun uploadInstanceAttr(
    vbo: Int,
    buffer: GpuFloatBuffer,
    array: FloatArray,
    floats: Int,
    attribute: Int,
    sizeX: Int,
    sizeY: Int = 1,
  ) {
    buffer.clear().put(array, 0, floats).flip()
    GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
    GPU.bufferData(GPU.ARRAY_BUFFER, floats, buffer, GPU.DYNAMIC_DRAW)
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
    GPU.deleteBuffers(quadVbo)
    GPU.deleteBuffers(centerSizeVbo)
    GPU.deleteBuffers(colorVbo)
    GPU.deleteBuffers(neighbourCountVbo)
    GPU.deleteBuffers(neighbourVbo)
    if (vao != null) GPU.deleteVertexArrays(vao)
  }

  companion object {
    private const val CELL_TEXTURE_UNIT = 0

    /** Membrane thickness in logical world units; the body inside it renders transparent. */
    const val MEMBRANE_BORDER = 0.125f

    private const val QUAD_VERTEX_COUNT = 4
    const val MAX_NEIGHBOURS = 8

    /** Cap on cells drawn in one pass. Sized past the biggest worlds the sim reaches in practice. */
    const val MAX_INSTANCES = 20000

    // Attribute locations, matching cell.vert. The eight neighbour slots occupy 4..11.
    private const val ATTR_CENTER_SIZE = 1
    private const val ATTR_COLOR = 2
    private const val ATTR_NEIGHBOUR_COUNT = 3
    private const val ATTR_NEIGHBOUR_BASE = 4
  }
}
