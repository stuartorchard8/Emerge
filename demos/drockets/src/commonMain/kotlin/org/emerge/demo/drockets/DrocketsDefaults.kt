package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.PhysicsSnapshot
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm

fun createDrocketsInitialState(): PhysicsState {
    val state = PhysicsSnapshot().mutable
    DrocketsRegistry.clear()

    // Spawn a single large planet at the world center
    val planetId = state.spawnBody(
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
    state.markPlanet(planetId, seed = 42)

    // Spawn 3 drockets on the planet surface at different bearings
    for (i in 0 until DROCKET_COUNT) {
        val teamId = TeamId(i)
        val angle = Coord(i, DROCKET_COUNT)
        spawnDrocketOnPlanet(state, planetId, angle, teamId)
    }

    return state
}

private fun spawnDrocketOnPlanet(
    state: PhysicsState,
    planetId: EntityId,
    angle: Coord,
    teamId: TeamId,
) {
    val planetTransform = state.raw.transforms[planetId] ?: return
    val planetCollider = state.raw.colliders[planetId] ?: return

    val localNormal = Norm.fromAngle(angle)
    val relativePos = localNormal * (planetCollider.radius + DROCKET_RADIUS)
    val worldPos = planetTransform.pos + relativePos.rotateByAngle(planetTransform.ang)
    val worldAng = Coord(planetTransform.ang.raw + angle.raw)

    val rocketId = state.spawnBody(
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

    state.addShip(
        entityId = rocketId,
        team = TeamComponent(teamId),
        motion = MotionComponent(vel = Coord2.zero, angVel = Coord(0)),
        landing = LandingAttachmentComponent(
            parentEntityId = planetId,
            relativePos = relativePos,
            relativeAng = Frac(angle.raw.toLong()),
        ),
    )

    val walkTicks = 120 + (teamId.value * 137) % 480
    DrocketsRegistry.drocketStates[rocketId] = DrocketStateComponent(
        phase = DrocketPhase.WALKING,
        planetId = planetId,
        walkDirection = if (teamId.value % 2 == 0) 1 else -1,
        ticksRemaining = walkTicks,
    )
}

private val PLANET_RADIUS = Frac(1, 8)
val PLANET_MASS = 500_000u
private val DROCKET_RADIUS = Frac(1, 512)
private val DROCKET_MASS = 5000u
private const val DROCKET_COUNT = 3
