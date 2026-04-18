package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.PlanetComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.spawnBody
import org.emerge.sim.core.physics.primitives.*

fun createDrocketsInitialState(): PhysicsState {
    val builder = PhysicsBuilder(PhysicsState())

    val planetId = builder.spawnBody(
        playerId = null,
        pos = Coord2.zero,
        vel = Coord2.zero,
        ang = Coord(0),
        angVel = Coord(0),
        mass = PLANET_MASS,
        radius = PLANET_RADIUS,
        bounce = Frac(1, 2),
        rough = Frac(1, 2),
        shape = BodyShape.CIRCLE,
    )
    builder.update<PlanetComponent>(planetId) { PlanetComponent(seed = 42) }

    for (i in 0 until DROCKET_COUNT) {
        val teamId = TeamId(i)
        val angle = Coord(i, DROCKET_COUNT)
        spawnDrocketOnPlanet(builder, planetId, angle, teamId)
    }

    return builder.build()
}

private fun spawnDrocketOnPlanet(
    builder: PhysicsBuilder,
    planetId: EntityId,
    angle: Coord,
    teamId: TeamId,
) {
    val planetTransform = builder.getComponent<TransformComponent>(planetId) ?: return
    val planetCollider = builder.getComponent<ColliderComponent>(planetId) ?: return

    val localNormal = Norm.fromAngle(angle)
    val relativePos = localNormal * (planetCollider.radius + DROCKET_RADIUS)
    val worldPos = planetTransform.pos + relativePos.rotateByAngle(planetTransform.ang)
    val worldAng = Coord(planetTransform.ang.raw + angle.raw)

    val rocketId = builder.spawnBody(
        playerId = null,
        pos = worldPos,
        vel = Coord2.zero,
        ang = worldAng,
        angVel = Coord(0),
        mass = DROCKET_MASS,
        radius = DROCKET_RADIUS,
        bounce = Frac(1, 2),
        rough = Frac(1, 2),
        shape = BodyShape.TRIANGLE,
    )

    builder.update<TeamComponent>(rocketId) { TeamComponent(teamId) }
    builder.update<MotionComponent>(rocketId) { MotionComponent(vel = Coord2.zero, angVel = Coord(0)) }
    builder.update<LandingAttachmentComponent>(rocketId) {
        LandingAttachmentComponent(
            parentEntityId = planetId,
            relativePos = relativePos,
            relativeAng = Frac(angle.raw.toLong()),
        )
    }

    val walkTicks = 120 + (teamId.value * 137) % 480
    builder.update<DrocketStateComponent>(rocketId) {
        DrocketStateComponent(
            phase = DrocketPhase.WALKING,
            planetId = planetId,
            walkDirection = if (teamId.value % 2 == 0) 1 else -1,
            ticksRemaining = walkTicks,
        )
    }
}

private val PLANET_RADIUS = Frac(1, 8)
val PLANET_MASS = 500_000u
private val DROCKET_RADIUS = Frac(1, 512)
private val DROCKET_MASS = 5000u
private const val DROCKET_COUNT = 3
