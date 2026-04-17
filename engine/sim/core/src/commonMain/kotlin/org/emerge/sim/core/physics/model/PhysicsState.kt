package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.primitives.*

data class PhysicsState(
    var raw: PhysicsSnapshot,
) {

    fun integrate(
        transforms: ComponentTable<TransformComponent>,
        motions: ComponentTable<MotionComponent>,
    ) {
        raw = raw.copy(
            components = raw.components.update {
                set(transforms)
                set(motions)
            },
        )
    }

    fun setContacts(contacts: List<Contact>) {
        raw = raw.copy(contacts = contacts);
    }

    fun addImpulses(impulses: LinkedHashMap<EntityId, ImpulseComponent>) {
        val sums = impulses.mapValues { (entityId, impulse) -> raw.impulses[entityId]?.plus(impulse) ?: impulse }
        setImpulses(raw.impulses.putAll(sums.toList()))
    }

    fun setImpulses(impulses: ComponentTable<ImpulseComponent>) {
        raw = raw.copy(
            components = raw.components.update {
                set(impulses)
            },
        )
    }

    fun setLandings(
        landings: ComponentTable<LandingAttachmentComponent>,
    ) {
        raw = raw.copy(
            components = raw.components.update {
                set(landings)
            },
        )
    }

    fun addDamages(
        damages: Map<EntityId, Frac>,
    ) {
        if (damages.entries.isNotEmpty()) {
            val sums = damages.mapValues { (entityId, damage) ->
                val existing = raw.damages[entityId]
                if (existing == null) DamageComponent(Frac(0), Frac(0), damage)
                else existing.copy(next = existing.next + damage)
            }
            setDamages(raw.damages.putAll(sums.toList()))
        }
    }

    fun setDamages(
        damages: ComponentTable<DamageComponent>,
    ) {
        raw = raw.copy(
            components = raw.components.update {
                set(damages)
            },
        )
    }

    fun setAudioEvents(
        crashImpactAudioEvents: List<CrashImpactAudioEvent>
    ) {
        raw = raw.copy(
            crashImpactAudioEvents = crashImpactAudioEvents,
        )
    }

    fun setParticles(particles: ComponentTable<ParticleComponent>) {
        raw = raw.copy(
            components = raw.components.update {
                set(particles)
            },
        )
    }

    fun addShip(
        entityId: EntityId,
        team: TeamComponent,
        motion: MotionComponent,
        landing: LandingAttachmentComponent,
    ) {
        raw = raw.copy(
            components = raw.components.update {
                set(raw.teams.put(entityId,team))
                set(raw.motions.put(entityId,motion))
                set(raw.landings.put(entityId,landing))
            },
        )
    }

    fun setComponents(components: ComponentStore) {
        raw = raw.copy(components = components)
    }

    /**
     * Deterministic PRNG state carried across ticks.
     * Must be kept in sync across all lockstep peers — never seed from platform Random.
     * Serialized alongside the snapshot for Welcome/Resync.
     */
    var randomSeed: Long = 0

    fun nextRandomInt(): Int {
        randomSeed = randomSeed * 2862933555777941757L + 3037000493L
        return (randomSeed ushr 32).toInt()
    }

    fun nextRandomInt(until: Int): Int {
        require(until > 0)
        return (nextRandomInt().toLong() and 0x7FFFFFFFL).toInt() % until
    }

    fun spawnBody(
        playerId: PlayerId?,
        pos: Coord2,
        vel: Coord2,
        ang: Coord,
        angVel: Coord,
        mass: UInt,
        radius: Frac,
        bounce: Frac,
        rough: Frac,
        shape: BodyShape,
    ): EntityId {
        val entityId = raw.world.createEntity()
        putBody(
            entityId = entityId,
            playerId = playerId,
            pos = pos,
            vel = vel,
            ang = ang,
            angVel = angVel,
            mass = mass,
            radius = radius,
            bounce = bounce,
            rough = rough,
            shape = shape,
        )
        return entityId
    }

    fun putBody(
        entityId: EntityId,
        playerId: PlayerId?,
        pos: Coord2,
        vel: Coord2,
        ang: Coord,
        angVel: Coord,
        mass: UInt,
        radius: Frac,
        bounce: Frac,
        rough: Frac,
        shape: BodyShape,
    ) {
        raw.world.ensureEntity(entityId)
        val nextPlayerEntities =
            if (playerId == null) {
                raw.playerEntities.filterValues { it != entityId }
            } else {
                LinkedHashMap(raw.playerEntities).apply { put(playerId, entityId) }
            }
        val nextPlayerOwned =
            if (playerId == null) {
                raw.playerOwned.remove(entityId)
            } else {
                raw.playerOwned.put(entityId, PlayerOwnedComponent(playerId))
            }
        raw = raw.copy(
            playerEntities = nextPlayerEntities,
            pendingRespawns = if (playerId == null) raw.pendingRespawns else raw.pendingRespawns - playerId,

            components = raw.components.update {
                set(raw.transforms.put(entityId, TransformComponent(pos = pos, ang = ang)))
                set(raw.motions.put(entityId, MotionComponent(vel = vel, angVel = angVel)))
                set(raw.colliders.put(entityId, ColliderComponent(radius = radius)))
                set(raw.materials.put(entityId, MaterialComponent(mass = mass, bounce = bounce, rough = rough)))
                set(raw.renderShapes.put(entityId, RenderShapeComponent(shape = shape)))
                set(nextPlayerOwned,)
                set(raw.teams.remove(entityId))
                set(raw.planets.remove(entityId))
                set(raw.homePlanets.remove(entityId))
                set(raw.forceFields.remove(entityId))
                set(raw.landings.remove(entityId))
                set(raw.particles.remove(entityId))
                set(raw.damages.remove(entityId))
            },
        )
    }

    fun spawnParticle(
        pos: Coord2,
        vel: Coord2,
        radius: Frac,
        shape: BodyShape,
        lifetime: Int,
        teamId: TeamId,
    ): EntityId {
        val entityId = raw.world.createEntity()
        putParticle(
            entityId = entityId,
            pos = pos,
            vel = vel,
            radius = radius,
            shape = shape,
            lifetime = lifetime,
            teamId = teamId,
        )
        return entityId
    }

    fun putParticle(
        entityId: EntityId,
        pos: Coord2,
        vel: Coord2,
        radius: Frac,
        shape: BodyShape,
        lifetime: Int,
        teamId: TeamId,
    ) {
        raw.world.ensureEntity(entityId)
        raw = raw.copy(
            playerEntities = raw.playerEntities.filterValues { it != entityId },
            components = raw.components.update {
                set(raw.transforms.put(entityId, TransformComponent(pos = pos, ang = Coord(0))))
                set(raw.motions.put(entityId, MotionComponent(vel = vel, angVel = Coord(0))))
                set(raw.colliders.put(entityId, ColliderComponent(radius = radius)))    // TODO don't store radius in collider)
                set(raw.materials.remove(entityId))
                set(raw.renderShapes.put(entityId, RenderShapeComponent(shape = shape)))
                set(raw.playerOwned.remove(entityId))
                set(raw.teams.put(entityId, TeamComponent(teamId)))
                set(raw.planets.remove(entityId))
                set(raw.homePlanets.remove(entityId))
                set(raw.forceFields.remove(entityId))
                set(raw.landings.remove(entityId))
                set(raw.particles.put(entityId, ParticleComponent(lifetime, lifetime)))
                set(raw.damages.remove(entityId))
            }
        )
    }

    fun markPlanet(entityId: EntityId, seed: Int = entityId.value) {
        raw = raw.copy(
            components = raw.components.update {
                set(raw.planets.put(entityId, PlanetComponent(seed = seed)))
            }
        )
    }

    fun assignHomePlanet(
        entityId: EntityId,
        teamId: TeamId,
    ) {
        var nextHomePlanets = raw.homePlanets
        for ((existingEntityId, homePlanet) in raw.homePlanets.entries()) {
            if (homePlanet.teamId == teamId && existingEntityId != entityId) {
                nextHomePlanets = nextHomePlanets.remove(existingEntityId)
            }
        }
        raw = raw.copy(
            components = raw.components.update {
                set(nextHomePlanets.put(entityId, HomePlanetComponent(teamId)))
            }
        )
    }

    fun setForceField(
        entityId: EntityId,
        depth: Frac,
        strength: Frac,
        alpha: Frac,
    ) {
        raw = raw.copy(
            components = raw.components.update {
                set(raw.forceFields.put(
                    entityId,
                    ForceFieldComponent(
                        depth = depth,
                        strength = strength,
                        alpha = alpha,
                    ),
                ))
            }
        )
    }

    fun setTeam(entityId: EntityId, teamId: TeamId) {
        raw = raw.copy(
            components = raw.components.update {
                set(raw.teams.put(entityId, TeamComponent(teamId)))
            }
        )
    }

    fun removePlayerRocket(playerId: PlayerId) {
        raw = raw.copy(pendingRespawns = raw.pendingRespawns - playerId)
        val entityId = raw.playerEntities[playerId] ?: return
        removeEntity(entityId)
    }

    fun removeEntity(entityId: EntityId) {
        raw.world.removeEntity(entityId)
        val nextPlayerEntities = LinkedHashMap(raw.playerEntities.filterValues { it != entityId })
        val nextLandings = LinkedHashMap(raw.landings.asMap())
        for ((attachedEntityId, landing) in raw.landings.entries()) {
            if (landing.parentEntityId == entityId) {
                nextLandings.remove(attachedEntityId)
            }
        }
        raw.world.removeEntity(entityId)
        raw = raw.copy(
            playerEntities = nextPlayerEntities,
            components = raw.components.update {
                set(raw.transforms.remove(entityId))
                set(raw.motions.remove(entityId))
                set(raw.colliders.remove(entityId))
                set(raw.materials.remove(entityId))
                set(raw.renderShapes.remove(entityId))
                set(raw.playerOwned.remove(entityId))
                set(raw.teams.remove(entityId))
                set(raw.planets.remove(entityId))
                set(raw.homePlanets.remove(entityId))
                set(raw.forceFields.remove(entityId))
                set(ComponentTable.fromMap(nextLandings).remove(entityId))
                set(raw.particles.remove(entityId))
                set(raw.damages.remove(entityId))
            }
        )
    }

    fun queuePlayerRespawn(playerId: PlayerId, ticksRemaining: Int) {
        val entityId = raw.playerEntities[playerId] ?: return
        val transform = raw.transforms[entityId] ?: return removeEntity(entityId)
        val material = raw.materials[entityId] ?: return removeEntity(entityId)
        val collider = raw.colliders[entityId] ?: return removeEntity(entityId)
        val renderShape = raw.renderShapes[entityId] ?: return removeEntity(entityId)
        val teamId = raw.teams[entityId]?.teamId ?: return removeEntity(entityId)
        val nextRespawns = LinkedHashMap(raw.pendingRespawns)
        nextRespawns[playerId] =
            PlayerRespawnState(
                ticksRemaining = ticksRemaining,
                deathPos = transform.pos,
                teamId = teamId,
                entityId = entityId,
                rocket = RespawnRocketSpec(
                    mass = material.mass,
                    radius = collider.radius,
                    bounce = material.bounce,
                    rough = material.rough,
                    shape = renderShape.shape,
                ),
            )
        raw = raw.copy(pendingRespawns = nextRespawns)
    }

    fun advanceRespawns() {
        if (raw.pendingRespawns.isEmpty()) return
        val nextRespawns = LinkedHashMap(raw.pendingRespawns)
        for ((playerId, respawn) in raw.pendingRespawns) {
            if (raw.damages[respawn.entityId] != null) {
                removeEntity(respawn.entityId)
            }
            val nextTicks = (respawn.ticksRemaining - 1).coerceAtLeast(0)
            val updatedRespawn = respawn.copy(ticksRemaining = nextTicks)
            if (nextTicks > 0) {
                nextRespawns[playerId] = updatedRespawn
                continue
            }
            val respawned = tryRespawnPlayer(playerId, updatedRespawn)
            if (respawned) {
                nextRespawns.remove(playerId)
            } else {
                nextRespawns[playerId] = updatedRespawn
            }
        }
        raw = raw.copy(pendingRespawns = nextRespawns)
    }

    private fun tryRespawnPlayer(playerId: PlayerId, respawn: PlayerRespawnState): Boolean {
        val teamId = respawn.teamId
        val homePlanetId = raw.homePlanetEntity(teamId) ?: return false
        val planetTransform = raw.transforms[homePlanetId] ?: return false
        val planetMotion = raw.motions[homePlanetId] ?: return false
        val planetCollider = raw.colliders[homePlanetId] ?: return false
        val localAngle = Coord(playerId.value, Int.MAX_VALUE)
        val localNormal = Norm.fromAngle(localAngle)
        val relativePos = localNormal * (planetCollider.radius + respawn.rocket.radius)
        val worldPos = planetTransform.pos + rotateByAngle(relativePos, planetTransform.ang)
        val worldAng = Coord(planetTransform.ang.raw + localAngle.raw)
        val entityId = spawnBody(
            playerId = playerId,
            pos = worldPos,
            vel = planetMotion.vel,
            ang = worldAng,
            angVel = planetMotion.angVel,
            mass = respawn.rocket.mass,
            radius = respawn.rocket.radius,
            bounce = respawn.rocket.bounce,
            rough = respawn.rocket.rough,
            shape = respawn.rocket.shape,
        )
        setTeam(
            entityId = entityId,
            teamId = teamId,
        )
        raw = raw.copy(
            components = raw.components.update {
                set(raw.motions.put(
                    entityId,
                    MotionComponent(
                        vel = planetMotion.vel,
                        angVel = planetMotion.angVel,
                    ),
                ))
                set(raw.landings.put(
                    entityId,
                    LandingAttachmentComponent(
                        parentEntityId = homePlanetId,
                        relativePos = relativePos,
                        relativeAng = Frac(localAngle.raw.toLong()),
                    ),
                ))
            }
        )
        return true
    }

    private fun rotateByAngle(v: Frac2, angle: Coord): Frac2 {
        val rotation = Norm.fromAngle(angle)
        return Frac2(
            x = v.x * rotation.x - v.y * rotation.y,
            y = v.x * rotation.y + v.y * rotation.x,
        )
    }
}

