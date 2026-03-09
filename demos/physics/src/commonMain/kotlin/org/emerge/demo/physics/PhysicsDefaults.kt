package org.emerge.demo.physics

import kotlin.random.Random
import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.Frac
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Frac2
import org.emerge.sim.core.physics.Norm
import org.emerge.sim.core.physics.BodyShape
import org.emerge.sim.core.physics.LandingAttachmentComponent
import org.emerge.sim.core.physics.MotionComponent

fun createDefaultInitialState(): PhysicsState {
    var state = PhysicsState()
    for (it in 0 until DEFAULT_PLANET_COUNT) {
        val spawn = state.spawnBody(
            playerId = null,
            pos = Norm.fromAngle(Frac(it, DEFAULT_PLANET_COUNT)) * Frac(1, 3),
            vel = Norm.fromAngle(Frac(it, DEFAULT_PLANET_COUNT)).perp * Frac(1, 1000),
            ang = Frac(0),
            angVel = Frac(0),
            mass = (it.toUInt() + 10u) * 100u,
            radius = Frac(it + 100, 4000),
            bounce = Frac(3, 4),
            rough = Frac(8, 16),
            shape = BodyShape.CIRCLE,
        )
        state = spawn.first.markPlanet(spawn.second, seed = it)
    }
    return assignHomePlanetAndSpawn(
        state = state,
        playerId = PlayerId(0),
        random = Random.Default,
    )
}

/**
 * Join policy used by both desktop and Android demos:
 * - deterministic spawn positions based on player id
 */
fun defaultJoinPolicy(): (PhysicsState, PlayerId) -> PhysicsState =
    { s, pid ->
        assignHomePlanetAndSpawn(
            state = s,
            playerId = pid,
            random = Random.Default,
        )
    }

private fun assignHomePlanetAndSpawn(
    state: PhysicsState,
    playerId: PlayerId,
    random: Random,
): PhysicsState {
    val homePlanetId =
        state.homePlanetEntity(playerId)
            ?: chooseHomePlanet(state, random)
            ?: return state
    val withHome = state.assignHomePlanet(
        entityId = homePlanetId,
        playerId = playerId,
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
        planetId = homePlanetId,
        random = random,
    )
}

private fun chooseHomePlanet(state: PhysicsState, random: Random): EntityId? {
    val planets = state.planetEntities()
    if (planets.isEmpty()) return null
    val claimed = state.homePlanets.entries().map { it.key }.toSet()
    val available = planets.filterNot { it in claimed }
    val pool = if (available.isNotEmpty()) available else planets
    return pool[random.nextInt(pool.size)]
}

private fun spawnRocketOnPlanetSurface(
    state: PhysicsState,
    playerId: PlayerId,
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
                mass = 1000u,
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
                mass = 1000u,
                radius = ROCKET_RADIUS,
                bounce = Frac(3, 4),
                rough = Frac(1, 16),
                shape = BodyShape.TRIANGLE,
            )
        }
    return rocketState.first.copy(
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

private const val DEFAULT_PLANET_COUNT: Int = ScreenRenderer.MAX_BODIES - 1
private val HOME_PLANET_FORCE_FIELD_DEPTH = Frac(1, 24)
private val HOME_PLANET_FORCE_FIELD_STRENGTH = Frac(1, 1024*64)
private val HOME_PLANET_FORCE_FIELD_ALPHA = Frac(1, 4)
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

