package org.emerge.demo.cyto

import com.badlogic.gdx.math.Vector2
import org.emerge.render.torus.GPU
import org.emerge.render.torus.Mat4
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the Cyto world on Emerge's GPU. Owns a flat 2D camera (centre + vertical
 * world extent) replicating Cyto's `FillViewport(100, 100)` framing, and routes every
 * cell through [CytoCellShader] one draw at a time (the faithful, non-instanced port of
 * Cyto's per-cell SpriteBatch draw). The engine's torus [org.emerge.demo.drockets]-style
 * renderer is deliberately not reused: Cyto is a flat, free-panning world, not a torus.
 */
class CytoRenderer(
  private val cellTextureId: Int,
) {
  private val shader = CytoCellShader()

  private var resW = 1f
  private var resH = 1f

  // Camera: world point at screen centre + how many world units tall the view is.
  private var centerX = 0f
  private var centerY = 0f
  private var viewHeight = 100f

  // Scratch matrices/arrays (per-frame, allocation-free).
  private val matP = Mat4.scratch()
  private val matS = Mat4.scratch()
  private val matT = Mat4.scratch()
  private val matM = Mat4.scratch()
  private val matMS = Mat4.scratch()
  private val matMT = Mat4.scratch()
  private val mvp = Mat4.scratch()
  private val colorTmp = FloatArray(4)
  private val neighbourTmp = FloatArray(CytoCellShader.MAX_NEIGHBOURS * 4)

  fun setResolution(widthPx: Float, heightPx: Float) {
    resW = max(1f, widthPx)
    resH = max(1f, heightPx)
    GPU.setViewport(0, 0, resW.toInt(), resH.toInt())
  }

  /** Pan the camera by a screen-pixel delta (screen y grows downward, world y up). */
  fun panByPixels(dxPx: Float, dyPx: Float) {
    val worldPerPx = viewHeight / resH
    centerX -= dxPx * worldPerPx
    centerY += dyPx * worldPerPx
  }

  /** factor > 1 zooms in (shrinks the visible world extent). */
  fun zoomByFactor(factor: Float) {
    if (!factor.isFinite() || factor <= 0f) return
    viewHeight = (viewHeight / factor).coerceIn(0.5f, 100_000f)
  }

  /** Convert a framebuffer pixel to a world-space point. */
  fun screenToWorld(px: Float, py: Float): Vector2 {
    val aspect = resW / resH
    val viewWidth = viewHeight * aspect
    val ndcX = px / resW * 2f - 1f
    val ndcY = 1f - py / resH * 2f
    return Vector2(
      centerX + ndcX * viewWidth * 0.5f,
      centerY + ndcY * viewHeight * 0.5f,
    )
  }

  fun draw(frame: CytoFrame) {
    computeProjection()

    GPU.enableBlend()
    GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
    shader.begin(cellTextureId)

    val cells = frame.world.cells
    for (cell in cells) {
      val radius = cell.shape.radius
      // Model: unit quad [-1,1] -> world quad centred on the cell with half-extent
      // 2*radius (matching the original SpriteBatch draw extents).
      matMS.setScale(2f * radius, 2f * radius)
      matMT.setTranslation(cell.body.position.x, cell.body.position.y)
      matM.setProduct(matMT, matMS)
      mvp.setProduct(matP, matM)

      packColor(cell.type.color)

      val nd = cell.neighbourRenderData()
      val count = min(CytoCellShader.MAX_NEIGHBOURS, nd.size)
      for (i in 0 until count) {
        val v = nd[i]
        val base = i * 4
        neighbourTmp[base] = v.x
        neighbourTmp[base + 1] = v.y
        neighbourTmp[base + 2] = v.z
        neighbourTmp[base + 3] = 0f
      }

      shader.draw(
        mvp = mvp,
        radiusUniform = radius * 2f,
        color = colorTmp,
        neighbours = neighbourTmp,
        count = count,
      )
    }

    GPU.disableBlend()
  }

  fun cleanup() {
    shader.deleteProgram()
  }

  private fun computeProjection() {
    val aspect = resW / resH
    val viewWidth = viewHeight * aspect
    matT.setTranslation(-centerX, -centerY)
    matS.setScale(2f / viewWidth, 2f / viewHeight)
    matP.setProduct(matS, matT)
  }

  /** 0xRRGGBBAA -> rgba floats in [0,1], into [colorTmp]. */
  private fun packColor(rgba: Long) {
    colorTmp[0] = ((rgba ushr 24) and 0xFF).toFloat() / 255f
    colorTmp[1] = ((rgba ushr 16) and 0xFF).toFloat() / 255f
    colorTmp[2] = ((rgba ushr 8) and 0xFF).toFloat() / 255f
    colorTmp[3] = (rgba and 0xFF).toFloat() / 255f
  }
}
