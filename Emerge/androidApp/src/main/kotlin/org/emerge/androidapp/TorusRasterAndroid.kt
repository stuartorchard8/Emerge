package org.emerge.androidapp

import android.graphics.Bitmap
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusCoverTracker
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D

internal class TorusRasterAndroid(w: Int, h: Int) {
    var bitmap: Bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        private set

    private var pixels: IntArray = IntArray(bitmap.width * bitmap.height)
    private var tracker: TorusCoverTracker? = null

    fun ensureSize(w: Int, h: Int) {
        if (bitmap.width == w && bitmap.height == h) return
        bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        pixels = IntArray(bitmap.width * bitmap.height)
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
        val w = bitmap.width
        val h = bitmap.height

        val tr = (tracker ?: TorusCoverTracker(torus, focusWrapped)).also { tracker = it }
        val focusCover = tr.update(focusWrapped)

        val halfW = Fx(viewW.raw / 2)
        val halfH = Fx(viewH.raw / 2)
        val topLeftCoverXRaw = focusCover.x.raw - halfW.raw
        val topLeftCoverYRaw = focusCover.y.raw - halfH.raw

        val stepX = viewW.raw.toFloat() / Fx.SCALE.toFloat() / w.toFloat()
        val stepY = viewH.raw.toFloat() / Fx.SCALE.toFloat() / h.toFloat()
        val step = maxOf(stepX, stepY)

        val bg = 0xFF111111.toInt()
        val me = 0xFF2E86AB.toInt()
        val other = 0xFFCCCCCC.toInt()

        val bodies = state.bodies.values.toList()

        var idx = 0
        for (py in 0 until h) {
            val yCover = topLeftCoverYRaw.toFloat() / Fx.SCALE.toFloat() + py.toFloat() * step
            val yCoverRaw = (yCover * Fx.SCALE.toFloat()).toInt()
            val yWrappedRaw = torus.wrapRawY(yCoverRaw)
            for (px in 0 until w) {
                val xCover = topLeftCoverXRaw.toFloat() / Fx.SCALE.toFloat() + px.toFloat() * step
                val xCoverRaw = (xCover * Fx.SCALE.toFloat()).toInt()
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

                pixels[idx++] = bestColor
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}

