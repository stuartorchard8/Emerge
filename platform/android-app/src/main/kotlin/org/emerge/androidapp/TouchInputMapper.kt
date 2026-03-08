package org.emerge.androidapp

import android.view.MotionEvent
import org.emerge.sim.core.physics.PhysicsInput
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

internal object TouchInputMapper {
    fun toPhysicsInput(
        widthPx: Int,
        heightPx: Int,
        x: Float,
        y: Float,
        actionMasked: Int,
        rocketAngleTurns: Float,
    ): PhysicsInput {
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            return PhysicsInput.ZERO
        }
        val deadzone = min(widthPx, heightPx) / 16

        val cx = widthPx / 2
        val cy = heightPx / 2
        val dx = (x.toInt() - cx).toFloat()
        val dy = (y.toInt() - cy).toFloat()

        // Convert from screen to "up-positive" coordinates, then rotate into rocket-local space.
        // NOTE: rocketAngleTurns is in turns [-1, 1], not radians.
        val inputX = dy
        val inputY = -dx
        val angleRad = rocketAngleTurns * 2f * (PI).toFloat()
        val c = cos(angleRad)
        val s = sin(angleRad)
        val localX = inputX * c + inputY * s
        val localY = -inputX * s + inputY * c

        val maxX = cx - deadzone
        val maxY = cy - deadzone

        val turn = when {
            localX < -deadzone -> ((localX + deadzone).toLong() * Int.MAX_VALUE) / maxX
            localX > deadzone -> ((localX - deadzone).toLong() * Int.MAX_VALUE) / maxX
            else -> 0L
        }

        // Forward in rocket-local space applies positive thrust; reverse is clamped to zero.
        val thrust = when {
            localY < -deadzone -> ((-localY + deadzone) * Int.MAX_VALUE / maxY).roundToLong()
            else -> 0L
        }
        return PhysicsInput(thrust = thrust.toInt(), turn = turn.toInt())
    }
}

