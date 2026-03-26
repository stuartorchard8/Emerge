package org.emerge.demo.physics

import kotlin.random.Random
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.PhysicsSnapshot
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2

fun createDefaultInitialState(gameMode: GameMode = GameMode.PVP, spawnHostPlayer: Boolean = true): PhysicsState {
    val state = PhysicsSnapshot().mutable
    for (it in 0 until DEFAULT_PLANET_COUNT) {
        val spawn = state.spawnBody(
            playerId = null,
            pos = Coord2.zero + Norm.fromAngle(Coord(it, DEFAULT_PLANET_COUNT)) * Frac(1, 3),
            vel = Coord2.zero + Norm.fromAngle(Coord(it, DEFAULT_PLANET_COUNT)).cw90 * Frac(1, 1000),
            ang = Coord(0),
            angVel = Coord(0),
            mass = (it.toUInt() + 100u) * 1000u,
            radius = Frac(it + 100L, 4000),
            bounce = Frac(1, 1),
            rough = Frac(8, 16),
            shape = BodyShape.CIRCLE,
        )
        state.markPlanet(spawn, seed = it)
    }
    if (spawnHostPlayer) {
        assignHomePlanetAndSpawn(
            state = state,
            playerId = PlayerId(0),
            gameMode = gameMode,
            random = Random.Default,
        )
    }
    return state;
}

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(gameMode: GameMode = GameMode.PVP): (PhysicsState, PlayerId) -> Unit =
    { s, pid ->
        assignHomePlanetAndSpawn(
            state = s,
            playerId = pid,
            gameMode = gameMode,
            random = Random.Default,
        )
    }

private fun assignHomePlanetAndSpawn(
    state: PhysicsState,
    playerId: PlayerId,
    gameMode: GameMode,
    random: Random,
) {
    val teamId = gameMode.teamIdForPlayer(playerId)
    val homePlanetId =
        state.raw.homePlanetEntity(teamId)
            ?: chooseHomePlanet(state.raw, random)
            ?: return
    with(state) {
        state.assignHomePlanet(
            entityId = homePlanetId,
            teamId = teamId,
        )
        state.setTeam(
            entityId = homePlanetId,
            teamId = teamId,
        )
        state.setForceField(
            entityId = homePlanetId,
            depth = HOME_PLANET_FORCE_FIELD_DEPTH,
            strength = HOME_PLANET_FORCE_FIELD_STRENGTH,
            alpha = HOME_PLANET_FORCE_FIELD_ALPHA,
        )
    }
    spawnRocketOnPlanetSurface(
        state = state,
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

private fun chooseHomePlanet(state: PhysicsSnapshot, random: Random): EntityId? {
    val planets = state.planetEntities()
    if (planets.isEmpty()) return null
    val claimed = state.homePlanets.entries().map { it.key }.toSet()
    val available = planets.filterNot { it in claimed }
    val pool = available.ifEmpty { planets.toList() }
    return pool[random.nextInt(pool.size)]
}

private fun spawnRocketOnPlanetSurface(
    state: PhysicsState,
    playerId: PlayerId,
    teamId: TeamId,
    planetId: EntityId,
    random: Random,
) {
    val planetTransform = state.raw.transforms[planetId] ?: return
    val planetMotion = state.raw.motions[planetId] ?: return
    val planetCollider = state.raw.colliders[planetId] ?: return
    val existingEntity = state.raw.playerEntities[playerId]
    val localAngle = randomTurn(random)
    val localNormal = Norm.fromAngle(localAngle)
    val relativePos = localNormal * (planetCollider.radius + ROCKET_RADIUS)
    val worldPos = planetTransform.pos + rotateByAngle(relativePos, planetTransform.ang)
    val worldAng = Coord(planetTransform.ang.raw + localAngle.raw)
    val rocketId: EntityId
    if (existingEntity != null) {
        rocketId = existingEntity
        state.putBody(
            entityId = existingEntity,
            playerId = playerId,
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
    } else {
        rocketId = state.spawnBody(
            playerId = playerId,
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
    }
    state.raw = state.raw.copy(
        teams = state.raw.teams.put(
            rocketId,
            TeamComponent(teamId),
        ),
        motions = state.raw.motions.put(
            rocketId,
            MotionComponent(
                vel = planetMotion.vel,
                angVel = planetMotion.angVel,
            ),
        ),
        landings = state.raw.landings.put(
            rocketId,
            LandingAttachmentComponent(
                parentEntityId = planetId,
                relativePos = relativePos,
                relativeAng = Frac(localAngle.raw.toLong()),
            ),
        ),
    )
}

private fun randomTurn(random: Random): Coord = Coord(random.nextInt(0, Int.MAX_VALUE))

private fun rotateByAngle(v: Frac2, angle: Coord): Frac2 {
    val rotation = Norm.fromAngle(angle)
    return Frac2(
        x = v.x * rotation.x - v.y * rotation.y,
        y = v.x * rotation.y + v.y * rotation.x,
    )
}

private const val DEFAULT_PLANET_COUNT: Int = 50
private val HOME_PLANET_FORCE_FIELD_DEPTH = Frac(1, 24)
private val HOME_PLANET_FORCE_FIELD_STRENGTH = Frac(1, 1024*64)
private val HOME_PLANET_FORCE_FIELD_ALPHA = Frac(1, 3)
private val ROCKET_RADIUS = Frac(1, 1024)

private fun spawnRocket(
    state: PhysicsState,
    playerId: PlayerId,
    pos: Coord2,
): EntityId = state.spawnBody(
    playerId = playerId,
    pos = pos,
    vel = Coord2.zero,
    ang = Coord(0),
    angVel = Coord(0),
    mass = 1000u,
    radius = Frac(1, 160),
    bounce = Frac(3, 4),
    rough = Frac(1, 16),
    shape = BodyShape.TRIANGLE,
)

