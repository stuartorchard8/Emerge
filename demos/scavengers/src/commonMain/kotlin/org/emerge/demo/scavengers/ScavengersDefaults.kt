package org.emerge.demo.scavengers

import kotlin.random.Random
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.PlanetComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.spawnBody
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm

fun createDefaultInitialState(
    gameMode: GameMode = GameMode.PVP,
    spawnHostPlayer: Boolean = true,
): ScavengersState {
    val builder = SimBuilder(SimState())
    for (i in 0 until DEFAULT_PLANET_COUNT) {
        val spawn = builder.spawnBody(
            pos = Coord2.zero + Norm.fromAngle(Coord(i, DEFAULT_PLANET_COUNT)) * Frac(1, 3),
            vel = Coord2.zero + Norm.fromAngle(Coord(i, DEFAULT_PLANET_COUNT)).cw90 * Frac(1, 1000),
            ang = Coord(0),
            angVel = Coord(0),
            mass = (i.toUInt() + 100u) * 1000u,
            radius = Frac(i + 100L, 4000),
            bounce = Frac(1, 1),
            rough = Frac(8, 16),
            shape = BodyShape.CIRCLE,
        )
        builder.update<PlanetComponent>(spawn) { PlanetComponent(seed = i) }
    }
    if (spawnHostPlayer) {
        assignHomePlanetAndSpawn(
            builder = builder,
            playerId = PlayerId(0),
            gameMode = gameMode,
            random = Random.Default,
        )
    }
    val core = builder.build()
    return ScavengersState(
        core = core,
        playerEntities = core.computePlayerEntities(),
    )
}

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(gameMode: GameMode = GameMode.PVP): (ScavengersState, PlayerId) -> ScavengersState =
    { snapshot, pid ->
        val builder = SimBuilder(snapshot.core)
        assignHomePlanetAndSpawn(
            builder = builder,
            playerId = pid,
            gameMode = gameMode,
            random = Random.Default,
        )
        val core = builder.build()
        snapshot.copy(
            core = core,
            playerEntities = core.computePlayerEntities(),
        )
    }

private fun assignHomePlanetAndSpawn(
    builder: SimBuilder,
    playerId: PlayerId,
    gameMode: GameMode,
    random: Random,
) {
    val teamId = gameMode.teamIdForPlayer(playerId)
    val homePlanets = builder.entries<HomePlanetComponent>()
    val homePlanetId =
        homePlanets.entries.firstOrNull { it.value.teamId == teamId }?.key
            ?: chooseHomePlanet(builder, homePlanets, random)
            ?: return

    // Clear any prior home planet for this team, then assign ours.
    for ((existingId, comp) in homePlanets) {
        if (comp.teamId == teamId && existingId != homePlanetId) {
            builder.remove<HomePlanetComponent>(existingId)
        }
    }
    builder.update<HomePlanetComponent>(homePlanetId) { HomePlanetComponent(teamId) }
    builder.update<TeamComponent>(homePlanetId) { TeamComponent(teamId) }
    builder.update<ForceFieldComponent>(homePlanetId) {
        ForceFieldComponent(
            depth = HOME_PLANET_FORCE_FIELD_DEPTH,
            strength = HOME_PLANET_FORCE_FIELD_STRENGTH,
            alpha = HOME_PLANET_FORCE_FIELD_ALPHA,
        )
    }

    spawnRocketOnPlanetSurface(
        builder = builder,
        playerId = playerId,
        teamId = teamId,
        planetId = homePlanetId,
        random = random,
    )
}

private fun GameMode.teamIdForPlayer(playerId: PlayerId): TeamId =
    when (this) {
        // PVP creates one team per player.
        GameMode.PVP -> TeamId(playerId.value)
        // CO_OP collapses everyone onto the same team and home planet.
        GameMode.CO_OP -> TeamId(0)
    }

private fun chooseHomePlanet(
    builder: SimBuilder,
    homePlanets: Map<EntityId, HomePlanetComponent>,
    random: Random,
): EntityId? {
    val planets = builder.entries<PlanetComponent>().keys
    if (planets.isEmpty()) return null
    val claimed = homePlanets.keys
    val available = planets.filterNot { it in claimed }
    val pool = available.ifEmpty { planets.toList() }
    return pool[random.nextInt(pool.size)]
}

private fun spawnRocketOnPlanetSurface(
    builder: SimBuilder,
    playerId: PlayerId,
    teamId: TeamId,
    planetId: EntityId,
    random: Random,
) {
    val planetTransform = builder.getComponent<TransformComponent>(planetId) ?: return
    val planetMotion = builder.getComponent<MotionComponent>(planetId) ?: return
    val planetCollider = builder.getComponent<ColliderComponent>(planetId) ?: return
    val localAngle = randomTurn(random)
    val localNormal = Norm.fromAngle(localAngle)
    val relativePos = localNormal * (planetCollider.radius + ROCKET_RADIUS)
    val worldPos = planetTransform.pos + relativePos.rotateByAngle(planetTransform.ang)
    val worldAng = Coord(planetTransform.ang.raw + localAngle.raw)

    // If the player already has a rocket (e.g. from a previous session), drop it first
    // so playerEntities ends up pointing to a single fresh entity.
    builder.entries<PlayerOwnedComponent>()
        .entries.firstOrNull { it.value.playerId == playerId }?.key
        ?.let { builder.removeEntity(it) }

    val rocketId = builder.spawnBody(
        pos = worldPos,
        vel = planetMotion.vel,
        ang = worldAng,
        angVel = planetMotion.angVel,
        mass = 10000u,
        radius = ROCKET_RADIUS,
        bounce = Frac(3, 4),
        rough = Frac(1, 16),
        shape = BodyShape.TRIANGLE,
    )
    builder.update<PlayerOwnedComponent>(rocketId) { PlayerOwnedComponent(playerId) }
    builder.update<TeamComponent>(rocketId) { TeamComponent(teamId) }
    builder.update<MotionComponent>(rocketId) {
        MotionComponent(vel = planetMotion.vel, angVel = planetMotion.angVel)
    }
    builder.update<LandingAttachmentComponent>(rocketId) {
        LandingAttachmentComponent(
            parentEntityId = planetId,
            relativePos = relativePos,
            relativeAng = Frac(localAngle.raw.toLong()),
        )
    }
}

private fun randomTurn(random: Random): Coord = Coord(random.nextInt(0, Int.MAX_VALUE))

private const val DEFAULT_PLANET_COUNT: Int = 50
private val HOME_PLANET_FORCE_FIELD_DEPTH = Frac(1, 24)
private val HOME_PLANET_FORCE_FIELD_STRENGTH = Frac(1, 1024 * 64)
private val HOME_PLANET_FORCE_FIELD_ALPHA = Frac(1, 3)
private val ROCKET_RADIUS = Frac(1, 1024)
