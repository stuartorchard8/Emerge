package org.example.app

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusCoverTracker
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D

/**
 * Shader-esque torus renderer:
 * - Computes each screen pixel by sampling world-space at that location.
 * - Uses modulo wrap + torus shortest deltas, so the viewport can span many repeated tiles without
 *   instancing objects.
 *
 * This is CPU-based (Swing). Keep body counts small or downsample if needed.
 */
internal class TorusRaster(w: Int, h: Int) {
    var image: BufferedImage = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        private set
    private var data: IntArray = (image.raster.dataBuffer as DataBufferInt).data

    private var tracker: TorusCoverTracker? = null

    fun ensureSize(w: Int, h: Int) {
        if (image.width == w && image.height == h) return
        image = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        data = (image.raster.dataBuffer as DataBufferInt).data
        tracker = null
    }

    fun render(
        torus: Torus2D,
        state: PhysicsState,
        myId: PlayerId?,
        focusWrapped: Vec2Fx,
        viewW: Fx,
        viewH: Fx,
    ) {
        val w = image.width
        val h = image.height

        val tr = (tracker ?: TorusCoverTracker(torus, focusWrapped)).also { tracker = it }
        val focusCover = tr.update(focusWrapped)

        val halfW = Fx(viewW.raw / 2)
        val halfH = Fx(viewH.raw / 2)
        val topLeftCoverXRaw = focusCover.x.raw - halfW.raw
        val topLeftCoverYRaw = focusCover.y.raw - halfH.raw

        // Use a uniform step so aspect stays stable.
        val stepX = viewW.raw.toDouble() / Fx.SCALE.toDouble() / w.toDouble()
        val stepY = viewH.raw.toDouble() / Fx.SCALE.toDouble() / h.toDouble()
        val step = maxOf(stepX, stepY)

        val bg = 0xFF111111.toInt()
        val me = 0xFF2E86AB.toInt()
        val other = 0xFFCCCCCC.toInt()

        val bodies = state.bodies.values.toList()

        for (py in 0 until h) {
            val yCover = topLeftCoverYRaw.toDouble() / Fx.SCALE.toDouble() + py.toDouble() * step
            val yCoverRaw = (yCover * Fx.SCALE.toDouble()).toInt()
            val yWrappedRaw = torus.wrapRawY(yCoverRaw)
            for (px in 0 until w) {
                val xCover = topLeftCoverXRaw.toDouble() / Fx.SCALE.toDouble() + px.toDouble() * step
                val xCoverRaw = (xCover * Fx.SCALE.toDouble()).toInt()
                val xWrappedRaw = torus.wrapRawX(xCoverRaw)

                var bestColor = bg
                var bestDistSq = Long.MAX_VALUE

                for (b in bodies) {
                    val dx = torus.deltaRaw(xWrappedRaw, b.pos.x.raw, torus.width.raw)
                    val dy = torus.deltaRaw(yWrappedRaw, b.pos.y.raw, torus.height.raw)
                    val r = b.radius.raw
                    val distSq = dx.toLong() * dx.toLong() + dy.toLong() * dy.toLong()
                    val rSq = r.toLong() * r.toLong()
                    if (distSq <= rSq && distSq < bestDistSq) {
                        bestDistSq = distSq
                        bestColor = if (myId != null && b.playerId == myId) me else other
                    }
                }

                data[py * w + px] = bestColor
            }
        }
    }
}

