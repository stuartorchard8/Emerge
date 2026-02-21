package org.emerge.androidapp

import android.view.MotionEvent
import org.emerge.sim.core.physics.PhysicsInput
import kotlin.math.min

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
        val deadzone = min(widthPx, heightPx)/16

        val cx = widthPx/2
        val cy = heightPx/2
        val dx = x.toInt() - cx
        val dy = y.toInt() - cy

        val maxX = cx - deadzone
        val maxY = cy - deadzone

        val ax = when {
            dx < -deadzone -> (dx + deadzone).toLong()*Int.MAX_VALUE/maxX
            dx >  deadzone -> (dx - deadzone).toLong()*Int.MAX_VALUE/maxX
            else -> 0
        }
        val ay = when {
            dy < -deadzone -> (dy + deadzone).toLong()*Int.MAX_VALUE/maxY
            dy >  deadzone -> (dy - deadzone).toLong()*Int.MAX_VALUE/maxY
            else -> 0
        }
        return PhysicsInput(ax.toInt(), ay.toInt())
    }
}

