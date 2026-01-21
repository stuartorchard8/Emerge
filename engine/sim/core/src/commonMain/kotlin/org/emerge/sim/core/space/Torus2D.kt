package org.emerge.sim.core.space

import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.Vec2Fx

/**
 * True 2D torus topology:
 * - Coordinates are taken modulo world width/height.
 * - Distances use the shortest wrapped delta on each axis.
 */
class Torus2D(
    val width: Fx,
    val height: Fx,
) {
    init {
        require(width.raw > 0) { "width must be > 0" }
        require(height.raw > 0) { "height must be > 0" }
    }

    fun wrap(pos: Vec2Fx): Vec2Fx =
        Vec2Fx(
            x = Fx(wrapRaw(pos.x.raw, width.raw)),
            y = Fx(wrapRaw(pos.y.raw, height.raw)),
        )

    fun wrapRawX(xRaw: Int): Int = wrapRaw(xRaw, width.raw)
    fun wrapRawY(yRaw: Int): Int = wrapRaw(yRaw, height.raw)

    fun wrapX(x: Fx): Fx = Fx(wrapRaw(x.raw, width.raw))
    fun wrapY(y: Fx): Fx = Fx(wrapRaw(y.raw, height.raw))

    /**
     * Shortest signed delta from [b] to [a] on the torus, in raw units (range approx [-size/2, +size/2]).
     */
    fun deltaRaw(aRaw: Int, bRaw: Int, sizeRaw: Int): Int {
        val d = aRaw - bRaw
        return wrapDeltaRaw(d, sizeRaw)
    }

    fun delta(a: Fx, b: Fx, size: Fx): Fx = Fx(wrapDeltaRaw(a.raw - b.raw, size.raw))

    fun delta(a: Vec2Fx, b: Vec2Fx): Vec2Fx =
        Vec2Fx(
            x = Fx(wrapDeltaRaw(a.x.raw - b.x.raw, width.raw)),
            y = Fx(wrapDeltaRaw(a.y.raw - b.y.raw, height.raw)),
        )

    /**
     * Wrap an integer coordinate into [0, size).
     */
    private fun wrapRaw(v: Int, size: Int): Int {
        val m = v % size
        return if (m < 0) m + size else m
    }

    /**
     * Wrap a signed delta into the shortest representative around a ring of length [size].
     */
    private fun wrapDeltaRaw(d: Int, size: Int): Int {
        // Map into (-size/2, +size/2] deterministically using integer math.
        val half = size / 2
        var x = d % size
        if (x <= -half) x += size
        if (x > half) x -= size
        return x
    }

    /**
     * Offsets used for seam-correct rendering (draw 3x3 tiled copies).
     */
    fun tileOffsetsRawX(): IntArray = intArrayOf(-width.raw, 0, width.raw)
    fun tileOffsetsRawY(): IntArray = intArrayOf(-height.raw, 0, height.raw)
}

