package org.emerge.androidapp

import android.view.MotionEvent
import org.emerge.demo.scavengers.ScavengersInput
import kotlin.math.*

internal object TouchInputMapper {
    fun toPhysicsInput(
        widthPx: Int,
        heightPx: Int,
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float,
        actionMasked: Int,
        rocketAngleTurns: Float,
        rocketAngularVelocityTurns: Float,
        cameraRotationRad: Float,
    ): ScavengersInput {
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            return ScavengersInput.ZERO
        }
        val deadzonePx = min(widthPx, heightPx) / 24f
        val thrustDeadzonePx = deadzonePx * 2f
        val maxRadiusPx = min(widthPx, heightPx) / 4f

        val dx = currentX - startX
        val dy = currentY - startY
        val magnitudePx = hypot(dx, dy)
        if (magnitudePx <= deadzonePx) {
            return ScavengersInput.ZERO
        }

        // Convert from screen drag space to world heading space where +Y is up.
        val headingScreenX = dx
        val headingScreenY = dy

        // Undo camera rotation so touch intent is interpreted in world-space axes.
        val camC = cos(-cameraRotationRad)
        val camS = sin(-cameraRotationRad)
        val worldX = headingScreenX * camC - headingScreenY * camS
        val worldY = headingScreenX * camS + headingScreenY * camC

        val targetHeadingRad = atan2(worldY, worldX)
        val rocketHeadingRad = rocketAngleTurns * PI.toFloat()
        val rocketAngularVelocityRad = rocketAngularVelocityTurns * 2f * PI.toFloat()
        val angleErrorRad = normalizeAngleRad(targetHeadingRad - rocketHeadingRad)
        val turnControl =
            (
                (angleErrorRad / PI.toFloat()) * TURN_PROPORTIONAL_GAIN -
                    (rocketAngularVelocityRad / PI.toFloat()) * TURN_DERIVATIVE_GAIN
            ).coerceIn(-1f, 1f)
        val turn = (turnControl * Int.MAX_VALUE).roundToInt()

        val magnitudeNorm = ((magnitudePx - thrustDeadzonePx) / (maxRadiusPx - thrustDeadzonePx)).coerceIn(0f, 1f)
        val aligned =
            abs(angleErrorRad) <= THRUST_ALIGNMENT_RAD &&
                abs(rocketAngularVelocityRad) <= THRUST_ANGULAR_VELOCITY_RAD
        val thrust =
            if (aligned && magnitudeNorm > 0f) {
                val alignmentFactor = 1f - (abs(angleErrorRad) / THRUST_ALIGNMENT_RAD).coerceIn(0f, 1f)
                (magnitudeNorm * alignmentFactor * Int.MAX_VALUE).roundToLong().toInt()
            } else {
                0
            }
        return ScavengersInput(thrust = thrust, turn = turn)
    }

    private fun normalizeAngleRad(angle: Float): Float {
        var out = angle
        val pi = PI.toFloat()
        val twoPi = (2.0 * PI).toFloat()
        while (out > pi) out -= twoPi
        while (out < -pi) out += twoPi
        return out
    }

    private const val THRUST_ALIGNMENT_RAD = PI.toFloat() / 4f
    private const val THRUST_ANGULAR_VELOCITY_RAD = PI.toFloat() / 6f
    private const val TURN_PROPORTIONAL_GAIN = 10.0f
    private const val TURN_DERIVATIVE_GAIN = 240.0f
}

