package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.CrashImpactAudioEvent
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.components.ControlIntentComponent
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import kotlin.collections.set


object CollisionSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val LANDING_ALIGNMENT_THRESHOLD = Frac(7, 8)
    private const val DESTRUCTION_BURST_PARTICLE_COUNT = 50
    private val DESTRUCTION_BURST_PARTICLE_RADIUS = Frac(1, 1536)
    private val DESTRUCTION_BURST_BASE_SPEED = Frac(1, 896)
    private const val DESTRUCTION_BURST_LIFETIME = 42

    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val transforms = LinkedHashMap(state.raw.transforms.asMap())
        val motions = LinkedHashMap(state.raw.motions.asMap())
        val landings = LinkedHashMap(state.raw.landings.asMap())
        val damages = LinkedHashMap(state.raw.damages.asMap())
        val crashImpactAudioEvents = ArrayList<CrashImpactAudioEvent>()
        val destructionBursts = ArrayList<DestructionBurstSpec>()
        val playersToRespawn = LinkedHashSet<PlayerId>()
        val ids = state.raw.materials.keys().toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                val aTransform = transforms[aId] ?: continue
                val bTransform = transforms[bId] ?: continue
                val aMotion = motions[aId] ?: continue
                val bMotion = motions[bId] ?: continue
                val aCollider = state.raw.colliders[aId] ?: continue
                val bCollider = state.raw.colliders[bId] ?: continue
                val aMaterial = state.raw.materials[aId] ?: continue
                val bMaterial = state.raw.materials[bId] ?: continue
                val aShape = state.raw.renderShapes[aId] ?: continue
                val bShape = state.raw.renderShapes[bId] ?: continue
                val aControl = state.raw.controls[aId] ?: ControlIntentComponent.ZERO
                val bControl = state.raw.controls[bId] ?: ControlIntentComponent.ZERO

                val bodyContact = Contact.compute(
                    aTransform = aTransform,
                    bTransform = bTransform,
                    aRadius = aCollider.radius,
                    bRadius = bCollider.radius,
                )
                val contact = bodyContact ?: continue
                val normal = contact.normal
                val tangent = contact.tangent
                val minDist = contact.minDist
                val pen = contact.penetration
                val aLanding = landings[aId]
                val bLanding = landings[bId]
                if (aLanding != null || bLanding != null) {
                    val aCrushEvent = crushLandedShipIfPinnedByPlanet(
                        state = state,
                        cfg = cfg,
                        damages = damages,
                        playersToRespawn = playersToRespawn,
                        entityId = aId,
                        entityTransform = aTransform,
                        entityShape = aShape,
                        landing = aLanding,
                        otherEntityId = bId,
                    )
                    if (aCrushEvent != null) {
                        crashImpactAudioEvents += aCrushEvent
                        val teamId = state.raw.teams[aId]?.teamId
                        if (teamId != null) {
                            destructionBursts += DestructionBurstSpec(pos = aTransform.pos, vel = aMotion.vel, teamId = teamId)
                        }
                    }
                    val bCrushEvent = crushLandedShipIfPinnedByPlanet(
                        state = state,
                        cfg = cfg,
                        damages = damages,
                        playersToRespawn = playersToRespawn,
                        entityId = bId,
                        entityTransform = bTransform,
                        entityShape = bShape,
                        landing = bLanding,
                        otherEntityId = aId,
                    )
                    if (bCrushEvent != null) {
                        crashImpactAudioEvents += bCrushEvent
                        val teamId = state.raw.teams[bId]?.teamId
                        if (teamId != null) {
                            destructionBursts += DestructionBurstSpec(pos = bTransform.pos, vel = bMotion.vel, teamId = teamId)
                        }
                    }
                    continue
                }

                // Each collision pair can land in either direction: a-on-b or b-on-a.
                val aLandingAttempt = tryLand(
                    supportId = bId,
                    rocketShape = aShape,
                    rocketControl = aControl,
                    supportShape = bShape,
                    supportTransform = bTransform,
                    rocketTransform = aTransform,
                    landingNormal = normal,
                    minDist = minDist,
                )
                if (aLandingAttempt != null) {
                    landings[aId] = aLandingAttempt
                    motions[aId] = bMotion
                    continue
                }
                val bLandingAttempt = tryLand(
                    supportId = aId,
                    rocketShape = bShape,
                    rocketControl = bControl,
                    supportShape = aShape,
                    supportTransform = aTransform,
                    rocketTransform = bTransform,
                    landingNormal = -normal,
                    minDist = minDist,
                )
                if (bLandingAttempt != null) {
                    landings[bId] = bLandingAttempt
                    motions[bId] = aMotion
                    continue
                }

                val massA = aMaterial.mass.toLong()
                val massB = bMaterial.mass.toLong()
                val totalMass = (massA + massB).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                val invMassWeightA = Frac(massB, totalMass)
                val invMassWeightB = Frac(massA, totalMass)

                val pushA = normal * (pen * invMassWeightA)
                val pushB = normal * (pen * invMassWeightB)

                val velDelta = bMotion.vel - aMotion.vel
                val velAlongNorm = velDelta.dot(normal)
                val bounciness = aMaterial.bounce.coerceAtMost(bMaterial.bounce)
                val normResponse = if (velAlongNorm.sign > 0) velAlongNorm * bounciness else Frac(0)

                val roughness = aMaterial.rough.coerceAtMost(bMaterial.rough)
                val circumferenceA = aCollider.radius.toCircumference()
                val circumferenceB = bCollider.radius.toCircumference()
                val spinAlongTangent = Frac(aMotion.angVel.raw.toLong()) * circumferenceA + Frac(bMotion.angVel.raw.toLong()) * circumferenceB
                val velAlongTangent = velDelta.dot(tangent) - spinAlongTangent
                val tangentResponse = velAlongTangent * roughness

                // Multiply by 2 so that bounciness of 1 results in full momentum transfer.
                val pushVelA = Frac((normResponse * invMassWeightA).raw*2)
                val pushVelB = Frac((normResponse * invMassWeightB).raw*2)
                val pushNormVelA = normal * pushVelA
                val pushNormVelB = normal * pushVelB

                val tangentResponseA = (tangentResponse * invMassWeightA) / 2
                val tangentResponseB = (tangentResponse * invMassWeightB) / 2
                val pushTangentialVelA = tangent * tangentResponseA
                val pushTangentialVelB = tangent * tangentResponseB
                val pushAngVelA = tangentResponseA / circumferenceA
                val pushAngVelB = tangentResponseB / circumferenceB

                transforms[aId] = aTransform.copy(pos = aTransform.pos + pushA)
                transforms[bId] = bTransform.copy(pos = bTransform.pos - pushB)
                motions[aId] = aMotion.copy(
                    vel = aMotion.vel + pushNormVelA + pushTangentialVelA,
                    angVel = aMotion.angVel + pushAngVelA,
                )
                motions[bId] = bMotion.copy(
                    vel = bMotion.vel - pushNormVelB - pushTangentialVelB,
                    angVel = bMotion.angVel + pushAngVelB,
                )

                val aCrashEvent = accumulateShipCollisionDamage(
                    state = state,
                    damages = damages,
                    playersToRespawn = playersToRespawn,
                    entityId = aId,
                    entityPos = aTransform.pos,
                    shape = aShape,
                    imactImpulse = pushVelA,
                    cfg = cfg,
                )
                if (aCrashEvent != null) {
                    crashImpactAudioEvents += aCrashEvent
                    if (aCrashEvent.destroyed) {
                        val teamId = state.raw.teams[aId]?.teamId
                        if (teamId != null) {
                            destructionBursts += DestructionBurstSpec(pos = aTransform.pos, vel = motions[aId]!!.vel, teamId = teamId)
                        }
                    }
                }
                val bCrashEvent = accumulateShipCollisionDamage(
                    state = state,
                    damages = damages,
                    playersToRespawn = playersToRespawn,
                    entityId = bId,
                    entityPos = bTransform.pos,
                    shape = bShape,
                    imactImpulse = pushVelB,
                    cfg = cfg,
                )
                if (bCrashEvent != null) {
                    crashImpactAudioEvents += bCrashEvent
                    if (bCrashEvent.destroyed) {
                        val teamId = state.raw.teams[bId]?.teamId
                        if (teamId != null) {
                            destructionBursts += DestructionBurstSpec(pos = bTransform.pos, vel = motions[bId]!!.vel, teamId = teamId)
                        }
                    }
                }
            }
        }
        state.raw = state.raw.copy(
            transforms = ComponentTable.fromMap(transforms),
            motions = ComponentTable.fromMap(motions),
            landings = ComponentTable.fromMap(landings),
            damages = ComponentTable.fromMap(damages),
            crashImpactAudioEvents = crashImpactAudioEvents,
        )
        for (burst in destructionBursts) {
            spawnDestructionBurst(state, burst)
        }
        for (playerId in playersToRespawn) {
            state.queuePlayerRespawn(playerId, cfg.shipRespawnTicks)
        }
    }

    private fun accumulateShipCollisionDamage(
        state: PhysicsState,
        damages: MutableMap<org.emerge.sim.core.EntityId, DamageComponent>,
        playersToRespawn: MutableSet<PlayerId>,
        entityId: org.emerge.sim.core.EntityId,
        entityPos: Coord2,
        shape: RenderShapeComponent,
        imactImpulse: Frac,
        cfg: PhysicsConfig,
    ): CrashImpactAudioEvent? {
        if (shape.shape != BodyShape.TRIANGLE) return null
        val owner = state.raw.playerOwned[entityId]?.playerId ?: return null
        if (playersToRespawn.contains(owner)) return null
        val speedOverThreshold = imactImpulse - cfg.shipCollisionDamageThreshold
        if (speedOverThreshold.sign <= 0) return null
        val impactDamageRaw = (speedOverThreshold * cfg.shipCollisionDamageScale).raw
        if (impactDamageRaw <= 0L) return null
        val currentDamage = damages[entityId]?.damage ?: Frac(0)
        val nextDamage = currentDamage + Frac(impactDamageRaw)
        if (nextDamage.raw >= cfg.shipMaxDamage.raw) {
            playersToRespawn += owner
            damages.remove(entityId)
            return CrashImpactAudioEvent(
                entityId = entityId,
                pos = entityPos,
                damageRaw = impactDamageRaw,
                destroyed = true,
            )
        } else {
            damages[entityId] = DamageComponent(damage = nextDamage)
            return CrashImpactAudioEvent(
                entityId = entityId,
                pos = entityPos,
                damageRaw = impactDamageRaw,
                destroyed = false,
            )
        }
    }

    private fun canLand(
        rocketShape: RenderShapeComponent,
        rocketControl: ControlIntentComponent,
        supportShape: RenderShapeComponent,
        rocketTransform: TransformComponent,
        landingNormal: Norm,
    ): Boolean {
        if (rocketShape.shape != BodyShape.TRIANGLE) return false
        if (supportShape.shape == BodyShape.TRIANGLE) return false
        if (rocketControl.thrust > 0) return false
        val forward = Norm.fromAngle(rocketTransform.ang)
        val alignment = forward.dot(landingNormal)
        return alignment.raw > LANDING_ALIGNMENT_THRESHOLD.raw
    }

    private fun tryLand(
        supportId: org.emerge.sim.core.EntityId,
        rocketShape: RenderShapeComponent,
        rocketControl: ControlIntentComponent,
        supportShape: RenderShapeComponent,
        supportTransform: TransformComponent,
        rocketTransform: TransformComponent,
        landingNormal: Norm,
        minDist: Frac,
    ): LandingAttachmentComponent? {
        if (!canLand(
                rocketShape = rocketShape,
                rocketControl = rocketControl,
                supportShape = supportShape,
                rocketTransform = rocketTransform,
                landingNormal = landingNormal,
            )
        ) {
            return null
        }
        val snappedRocketAng = landingNormal.asAngle
        return LandingAttachmentComponent(
            parentEntityId = supportId,
            relativePos = (landingNormal * minDist).rotateByAngle(Coord(-supportTransform.ang.raw)),
            relativeAng = snappedRocketAng - supportTransform.ang,
        )
    }

    private fun spawnDestructionBurst(state: PhysicsState, burst: DestructionBurstSpec) {
        repeat(DESTRUCTION_BURST_PARTICLE_COUNT) {
            val direction = Norm.fromAngle(Coord(state.nextRandomInt()))
            val speed = DESTRUCTION_BURST_BASE_SPEED * Frac(state.nextRandomInt(until = Int.MAX_VALUE).toLong())
            state.spawnParticle(
                pos = burst.pos,
                vel = burst.vel + direction * speed,
                radius = DESTRUCTION_BURST_PARTICLE_RADIUS,
                shape = BodyShape.CIRCLE,
                lifetime = DESTRUCTION_BURST_LIFETIME,
                teamId = burst.teamId,
            )
        }
    }

    private fun crushLandedShipIfPinnedByPlanet(
        state: PhysicsState,
        cfg: PhysicsConfig,
        damages: MutableMap<org.emerge.sim.core.EntityId, DamageComponent>,
        playersToRespawn: MutableSet<PlayerId>,
        entityId: org.emerge.sim.core.EntityId,
        entityTransform: TransformComponent,
        entityShape: RenderShapeComponent,
        landing: LandingAttachmentComponent?,
        otherEntityId: org.emerge.sim.core.EntityId,
    ): CrashImpactAudioEvent? {
        if (landing == null) return null
        if (landing.parentEntityId == otherEntityId) return null
        if (entityShape.shape != BodyShape.TRIANGLE) return null
        if (state.raw.planets[otherEntityId] == null) return null
        val owner = state.raw.playerOwned[entityId]?.playerId ?: return null
        if (!playersToRespawn.add(owner)) return null
        damages.remove(entityId)
        return CrashImpactAudioEvent(
            entityId = entityId,
            pos = entityTransform.pos,
            damageRaw = cfg.shipMaxDamage.raw,
            destroyed = true,
        )
    }

    private data class DestructionBurstSpec(
        val pos: Coord2,
        val vel: Coord2,
        val teamId: org.emerge.sim.core.TeamId,
    )
}