package org.emerge.sim.core.physics

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId

data class TransformComponent(
    val pos: Frac2,
    val ang: Frac,
)

data class MotionComponent(
    val vel: Frac2,
    val angVel: Frac,
)

data class ColliderComponent(
    val radius: Frac,
)

data class MaterialComponent(
    val mass: UInt,
    val bounce: Frac,
    val rough: Frac,
)

data class ControlIntentComponent(
    val thrust: Int,
    val turn: Int,
) {
    companion object {
        val ZERO = ControlIntentComponent(
            thrust = 0,
            turn = 0,
        )
    }
}

data class RenderShapeComponent(
    val shape: BodyShape,
)

data class PlayerOwnedComponent(
    val playerId: PlayerId,
)

data class TeamComponent(
    val teamId: TeamId,
)

data class PlanetComponent(
    val seed: Int = 0,
)

data class HomePlanetComponent(
    val playerId: PlayerId,
)

data class ForceFieldComponent(
    val depth: Frac,
    val strength: Frac,
    val alpha: Frac,
)

data class LandingAttachmentComponent(
    val parentEntityId: EntityId,
    val relativePos: Frac2,
    val relativeAng: Frac,
)

data class PhysicsRenderBody(
    val entityId: EntityId,
    val playerId: PlayerId?,
    val pos: Frac2,
    val ang: Frac,
    val radius: Frac,
    val shape: BodyShape,
    val alpha: Float = 1f,
)

enum class BodyShape(val wireValue: Int) {
    CIRCLE(0),
    TRIANGLE(1);

    companion object {
        fun fromWireValue(value: Int): BodyShape =
            entries.firstOrNull { it.wireValue == value } ?: CIRCLE
    }
}
