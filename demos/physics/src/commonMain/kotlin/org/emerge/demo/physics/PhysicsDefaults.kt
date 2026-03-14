package org.emerge.demo.physics

import kotlin.random.Random
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TeamComponent

fun createDefaultInitialState(gameMode: GameMode = GameMode.PVP): PhysicsState {
    var state = PhysicsState()
    for (it in 0 until DEFAULT_PLANET_COUNT) {
        val spawn = state.spawnBody(
            playerId = null,
            pos = Norm.fromAngle(Frac(it, DEFAULT_PLANET_COUNT)) * Frac(1, 3),
            vel = Norm.fromAngle(Frac(it, DEFAULT_PLANET_COUNT)).cw90 * Frac(1, 1000),
            ang = Frac(0),
            angVel = Frac(0),
            mass = (it.toUInt() + 100u) * 1000u,
            radius = Frac(it + 100, 4000),
            bounce = Frac(1, 1),
            rough = Frac(8, 16),
            shape = BodyShape.CIRCLE,
        )
        state = spawn.first.markPlanet(spawn.second, seed = it)
    }
    return assignHomePlanetAndSpawn(
        state = state,
        playerId = PlayerId(0),
        gameMode = gameMode,
        random = Random.Default,
    )
}

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(gameMode: GameMode = GameMode.PVP): (PhysicsState, PlayerId) -> PhysicsState =
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
): PhysicsState {
    val teamId = gameMode.teamIdForPlayer(playerId)
    val homePlanetId =
        state.homePlanetEntity(teamId)
            ?: chooseHomePlanet(state, random)
            ?: return state
    val withHome = state.assignHomePlanet(
        entityId = homePlanetId,
        teamId = teamId,
    )
        .setTeam(
            entityId = homePlanetId,
            teamId = teamId,
        )
        .setForceField(
            entityId = homePlanetId,
            depth = HOME_PLANET_FORCE_FIELD_DEPTH,
            strength = HOME_PLANET_FORCE_FIELD_STRENGTH,
            alpha = HOME_PLANET_FORCE_FIELD_ALPHA,
        )
    return spawnRocketOnPlanetSurface(
        state = withHome,
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

private fun chooseHomePlanet(state: PhysicsState, random: Random): EntityId? {
    val planets = state.planetEntities()
    if (planets.isEmpty()) return null
    val claimed = state.homePlanets.entries().map { it.key }.toSet()
    val available = planets.filterNot { it in claimed }
    val pool = available.ifEmpty { planets }
    return pool[random.nextInt(pool.size)]
}

private fun spawnRocketOnPlanetSurface(
    state: PhysicsState,
    playerId: PlayerId,
    teamId: TeamId,
    planetId: EntityId,
    random: Random,
): PhysicsState {
    val planetTransform = state.transforms[planetId] ?: return state
    val planetMotion = state.motions[planetId] ?: return state
    val planetCollider = state.colliders[planetId] ?: return state
    val existingEntity = state.playerEntities[playerId]
    val localAngle = randomTurn(random)
    val localNormal = Norm.fromAngle(localAngle)
    val relativePos = localNormal * (planetCollider.radius + ROCKET_RADIUS)
    val worldPos = planetTransform.pos + rotateByAngle(relativePos, planetTransform.ang)
    val worldAng = Frac(planetTransform.ang.raw + localAngle.raw)
    val rocketState =
        if (existingEntity != null) {
            state.putBody(
                entityId = existingEntity,
                playerId = playerId,
                pos = worldPos,
                vel = planetMotion.vel,
                ang = worldAng,
                angVel = planetMotion.angVel,
                mass = 100u,
                radius = ROCKET_RADIUS,
                bounce = Frac(3, 4),
                rough = Frac(1, 16),
                shape = BodyShape.TRIANGLE,
            ) to existingEntity
        } else {
            state.spawnBody(
                playerId = playerId,
                pos = worldPos,
                vel = planetMotion.vel,
                ang = worldAng,
                angVel = planetMotion.angVel,
                mass = 100u,
                radius = ROCKET_RADIUS,
                bounce = Frac(3, 4),
                rough = Frac(1, 16),
                shape = BodyShape.TRIANGLE,
            )
        }
    return rocketState.first.copy(
        teams = rocketState.first.teams.put(
            rocketState.second,
            TeamComponent(teamId),
        ),
        motions = rocketState.first.motions.put(
            rocketState.second,
            MotionComponent(
                vel = planetMotion.vel,
                angVel = planetMotion.angVel,
            ),
        ),
        landings = rocketState.first.landings.put(
            rocketState.second,
            LandingAttachmentComponent(
                parentEntityId = planetId,
                relativePos = relativePos,
                relativeAng = localAngle,
            ),
        ),
    )
}

private fun randomTurn(random: Random): Frac = Frac(random.nextInt(0, Int.MAX_VALUE))

private fun rotateByAngle(v: Frac2, angle: Frac): Frac2 {
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
private val ROCKET_RADIUS = Frac(1, 160)

private fun spawnRocket(
    state: PhysicsState,
    playerId: PlayerId,
    pos: Frac2,
): PhysicsState =
    state.spawnBody(
        playerId = playerId,
        pos = pos,
        vel = Frac2.zero,
        ang = Frac(0),
        angVel = Frac(0),
        mass = 1000u,
        radius = Frac(1, 160),
        bounce = Frac(3, 4),
        rough = Frac(1, 16),
        shape = BodyShape.TRIANGLE,
    ).first

