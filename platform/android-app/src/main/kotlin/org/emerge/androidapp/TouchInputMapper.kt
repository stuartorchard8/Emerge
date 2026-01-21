package org.emerge.androidapp

import android.view.MotionEvent
import org.emerge.sim.core.physics.PhysicsInput

internal object TouchInputMapper {
    fun toPhysicsInput(
        widthPx: Int,
        heightPx: Int,
        x: Float,
        y: Float,
        actionMasked: Int,
    ): PhysicsInput {
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            return PhysicsInput(0, 0)
        }
        val cx = widthPx * 0.5f
        val cy = heightPx * 0.5f
        val dx = x - cx
        val dy = y - cy

        val ax = when {
            dx < -40f -> -1
            dx > 40f -> 1
            else -> 0
        }
        val ay = when {
            dy < -40f -> -1
            dy > 40f -> 1
            else -> 0
        }
        return PhysicsInput(ax, ay)
    }
}

