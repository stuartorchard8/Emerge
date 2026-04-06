package org.emerge.sim.core.physics.primitives

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Frac.Companion.abs

data class Contact(
    val aId: EntityId,
    val bId: EntityId,
    val minDist: Frac,
    val penetration: Frac,
    val normal: Norm,
    val tangent: Norm,
) {
    companion object {
        fun compute(
            aId: EntityId,
            bId: EntityId,
            aTransform: TransformComponent,
            bTransform: TransformComponent,
            aRadius: Frac,
            bRadius: Frac,
        ): Contact? {
            // Use shortest torus delta for both rigid collision and shield overlap tests.
            val delta = aTransform.pos - bTransform.pos
            val minDist = aRadius + bRadius
            val xPen = minDist - abs(delta.x)
            val yPen = minDist - abs(delta.y)
            if (xPen.sign <= 0 || yPen.sign <= 0) return null
            if (delta >= minDist) return null
            if (delta.lenSq.raw == 0L) return null
            delta.capMax(minDist)
            val normal = delta.norm
            return Contact(
                aId = aId,
                bId = bId,
                minDist = minDist,
                penetration = minDist - delta.len,
                normal = normal,
                tangent = normal.cw90,
            )
        }
    }
}
