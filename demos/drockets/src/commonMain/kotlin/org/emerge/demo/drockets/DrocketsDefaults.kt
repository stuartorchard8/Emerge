package org.emerge.demo.drockets

import kotlinx.datetime.Clock
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.spawnBody
import org.emerge.sim.core.physics.primitives.*

fun createDrocketsInitialState(drocketCount: Int = INITIAL_DROCKET_COUNT, knightCount: Int = INITIAL_KNIGHT_COUNT): PhysicsState {
    val builder = PhysicsBuilder(PhysicsState())

    val planetId = builder.spawnBody(
        playerId = null,
        pos = Coord2.zero,
        vel = Coord2.zero,
        ang = Coord(0),
        angVel = Coord(1,1 shl 12),
        mass = PLANET_MASS,
        radius = PLANET_RADIUS,
        bounce = Frac(1, 5),
        rough = Frac(3, 4),
        shape = BodyShape.CIRCLE,
    )
    builder.update<PlanetComponent>(planetId) { PlanetComponent(seed = 42) }

    for (i in 0 until knightCount) {
        val teamId = TeamId(i)
        val angle = Coord(i*2, drocketCount)
        spawnKnightOnPlanet(builder, planetId, angle, teamId)
    }
    for (i in 0 until drocketCount) {
        val teamId = TeamId(i)
        val angle = Coord(i*2, drocketCount)
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
    val planetMotion = builder.getComponent<MotionComponent>(planetId) ?: return
    val planetCollider = builder.getComponent<ColliderComponent>(planetId) ?: return

    val localNormal = Norm.fromAngle(angle)
    val relativePos = localNormal * (planetCollider.radius + DROCKET_RADIUS)
    val worldPos = planetTransform.pos + relativePos.rotateByAngle(planetTransform.ang)
    val worldAng = Coord(planetTransform.ang.raw + angle.raw)

    spawnDrocket(
        builder = builder,
        position = worldPos,
        velocity = planetMotion.surfaceVelocityAtOffset(localNormal, planetCollider.radius+DROCKET_RADIUS),
        angle = worldAng,
        teamId = teamId,
    )
}

fun spawnDrocket(
    builder: PhysicsBuilder,
    position: Coord2,
    velocity: Coord2,
    angle: Coord,
    teamId: TeamId,
): EntityId {
    val entityId = builder.spawnBody(
        playerId = null,
        pos = position,
        vel = velocity,
        ang = angle,
        angVel = Coord(0),
        mass = DROCKET_MASS,
        radius = DROCKET_RADIUS,
        bounce = Frac(1, 4),
        rough = Frac(1, 1),
        shape = BodyShape.TRIANGLE,
    )

    builder.update<TeamComponent>(entityId) { TeamComponent(teamId) }

    builder.update<DrocketStateComponent>(entityId) {
        DrocketStateComponent(
            phase = DrocketPhase.FLYING,
            walkDirection = if (teamId.value % 2 == 0) 1 else -1,
        )
    }
    builder.update<ReproducerComponent>(entityId) {
        ReproducerComponent(
            birthdayMs = Clock.System.now().toEpochMilliseconds(),
            sex = if (teamId.value%2==0) Sex.FEMALE else Sex.MALE,
        )
    }
    builder.update<GenomeComponent>(entityId) {
        GenomeComponent(
            encodedGenes = mapOf(
                GenomeComponent.GenomeKey.AI_WALK_MIN_TICKS.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.AI_WALK_MIN_TICKS, 120),
                GenomeComponent.GenomeKey.AI_WALK_MAX_TICKS.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.AI_WALK_MAX_TICKS, 600),
                GenomeComponent.GenomeKey.AI_CHARGE_TICKS.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.AI_CHARGE_TICKS, 18),
                GenomeComponent.GenomeKey.AI_FUEL_TICKS.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.AI_FUEL_TICKS, 200),
                GenomeComponent.GenomeKey.AI_SPIN_RAW.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.AI_SPIN_RAW, Frac(1, 120).raw.toInt()),
                GenomeComponent.GenomeKey.AI_THRUST_RAW.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.AI_THRUST_RAW, Frac(1, 1024 * 256).raw.toInt()),
                GenomeComponent.GenomeKey.COLOR_H.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.COLOR_H, ((teamId.value * 137) % 360 + 360) % 360),
                GenomeComponent.GenomeKey.COLOR_S.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.COLOR_S, 550 + (((teamId.value + 1) * 71) % 380)),
                GenomeComponent.GenomeKey.COLOR_V.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.COLOR_V, 620 + (((teamId.value + 1) * 59) % 320)),
                GenomeComponent.GenomeKey.FIRE_COLOR_H.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.FIRE_COLOR_H, (((teamId.value * 137) + 24) % 360 + 360) % 360),
                GenomeComponent.GenomeKey.FIRE_COLOR_S.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.FIRE_COLOR_S, 820 + (((teamId.value + 1) * 43) % 180)),
                GenomeComponent.GenomeKey.FIRE_COLOR_V.wireName to GenomeComponent.encodeRanged(GenomeComponent.GenomeKey.FIRE_COLOR_V, 860 + (((teamId.value + 1) * 37) % 140)),
            )
        )
    }
    builder.update<LineageSeedComponent>(entityId) { LineageSeedComponent() }
    return entityId
}

private fun spawnKnightOnPlanet(
    builder: PhysicsBuilder,
    planetId: EntityId,
    angle: Coord,
    teamId: TeamId,
) {
    val planetTransform = builder.getComponent<TransformComponent>(planetId) ?: return
    val planetCollider = builder.getComponent<ColliderComponent>(planetId) ?: return

    val localNormal = Norm.fromAngle(angle)
    val relativePos = localNormal * (planetCollider.radius + KNIGHT_RADIUS)
    val worldPos = planetTransform.pos + relativePos.rotateByAngle(planetTransform.ang)
    val worldAng = Coord(planetTransform.ang.raw + angle.raw)

    val entityId = builder.spawnBody(
        playerId = null,
        pos = worldPos,
        vel = Coord2.zero,
        ang = worldAng,
        angVel = Coord(0),
        mass = DROCKET_MASS,
        radius = KNIGHT_RADIUS,
        bounce = Frac(1, 2),
        rough = Frac(1, 2),
        shape = BodyShape.TRIANGLE,
    )

    builder.update<TeamComponent>(entityId) { TeamComponent(teamId) }
    builder.update<MotionComponent>(entityId) { MotionComponent(vel = Coord2.zero, angVel = Coord(0)) }
    builder.update<LandingAttachmentComponent>(entityId) {
        LandingAttachmentComponent(
            parentEntityId = planetId,
            relativePos = relativePos,
            relativeAng = Frac(angle.raw.toLong()),
        )
    }

    builder.update<KnightStateComponent>(entityId) {
        KnightStateComponent(
            phase = KnightPhase.IDLE,
            planetId = planetId,
            walkDirection = if (teamId.value % 2 == 0) 1 else -1,
        )
    }
}

val PLANET_RADIUS = Frac(1, 8)
val PLANET_MASS = 5_000_000u
val DROCKET_RADIUS = Frac(1, 1 shl 13)
val KNIGHT_RADIUS = Frac(1, 1 shl 13)
private val DROCKET_MASS = 500u
private const val INITIAL_DROCKET_COUNT = 200
private const val INITIAL_KNIGHT_COUNT = 0

