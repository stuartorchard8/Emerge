package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.EcsSystems
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val systems: List<EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput>> = listOf(
        InputSystem,
        LiftOffSystem,
        IntegrationSystem,
        ForceFieldSystem,
        CollisionSystem,
        AttachmentSystem,
    )

    override fun reduce(cfg: PhysicsConfig, state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>): PhysicsState {
        return EcsSystems.runAll(cfg, state, inputs, systems)
    }
}

private val LANDING_ALIGNMENT_THRESHOLD = Frac(1, 2)
private val FORCE_FIELD_TEAM_DAMPING = Frac(1, 32)

private object InputSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        var controls = state.controls
        for ((playerId, entityId) in state.playerEntities) {
            val input = inputs[playerId] ?: PhysicsInput.ZERO
            controls = controls.put(
                entityId,
                ControlIntentComponent(
                    thrust = input.thrust,
                    turn = input.turn,
                ),
            )
        }
        return state.copy(controls = controls)
    }
}

private object LiftOffSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        var motions = state.motions
        var landings = state.landings
        for ((entityId, control) in state.controls.entries()) {
            val landing = landings[entityId]
            if (control.thrust > 0 && landing != null) {
                val parentTransform = state.transforms[landing.parentEntityId]
                val parentMotion = state.motions[landing.parentEntityId]
                if (parentTransform != null && parentMotion != null) {
                    motions = motions.put(
                        entityId,
                        MotionComponent(
                            vel = surfaceVelocityAtAttachment(parentTransform, parentMotion, landing),
                            angVel = parentMotion.angVel,
                        ),
                    )
                }
                landings = landings.remove(entityId)
            }
        }
        return state.copy(
            motions = motions,
            landings = landings,
        )
    }
}

private object IntegrationSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val transforms = LinkedHashMap(state.transforms.asMap())
        val motions = LinkedHashMap(state.motions.asMap())
        for (entityId in state.world.entities) {
            if (state.landings.contains(entityId)) {
                continue
            }
            val transform = state.transforms[entityId] ?: continue
            val motion = state.motions[entityId] ?: continue
            val renderShape = state.renderShapes[entityId] ?: continue
            val control = state.controls[entityId] ?: ControlIntentComponent.ZERO
            val thrust = control.thrust / cfg.thrustFactorInv
            val turn = control.turn / cfg.turnFactorInv
            val acc = when (renderShape.shape) {
                BodyShape.TRIANGLE -> Norm.fromAngle(transform.ang) * Frac(thrust)
                BodyShape.CIRCLE -> Frac2.zero
            }

            val vel = motion.vel + acc
            val pos = transform.pos + vel

            val angVel = when (renderShape.shape) {
                BodyShape.TRIANGLE -> motion.angVel + Frac(turn)
                BodyShape.CIRCLE -> motion.angVel
            }
            val ang = Frac(transform.ang.raw + angVel.raw)

            transforms[entityId] = transform.copy(pos = pos, ang = ang)
            motions[entityId] = motion.copy(vel = vel, angVel = angVel)
        }
        return state.copy(
            transforms = state.transforms.putAll(transforms.map { it.key to it.value }),
            motions = state.motions.putAll(motions.map { it.key to it.value }),
        )
    }
}

private object CollisionSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val transforms = LinkedHashMap(state.transforms.asMap())
        val motions = LinkedHashMap(state.motions.asMap())
        val landings = LinkedHashMap(state.landings.asMap())
        val ids = state.world.entities
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                if (landings.containsKey(aId) || landings.containsKey(bId)) continue
                val aTransform = transforms[aId] ?: continue
                val bTransform = transforms[bId] ?: continue
                val aMotion = motions[aId] ?: continue
                val bMotion = motions[bId] ?: continue
                val aCollider = state.colliders[aId] ?: continue
                val bCollider = state.colliders[bId] ?: continue
                val aMaterial = state.materials[aId] ?: continue
                val bMaterial = state.materials[bId] ?: continue
                val aShape = state.renderShapes[aId] ?: continue
                val bShape = state.renderShapes[bId] ?: continue
                val aControl = state.controls[aId] ?: ControlIntentComponent.ZERO
                val bControl = state.controls[bId] ?: ControlIntentComponent.ZERO

                val bodyContact = computeContact(
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

                // Each collision pair can land in either direction: a-on-b or b-on-a.
                val aLanding = tryLand(
                    supportId = bId,
                    rocketShape = aShape,
                    rocketControl = aControl,
                    supportShape = bShape,
                    supportTransform = bTransform,
                    rocketTransform = aTransform,
                    landingNormal = normal,
                    minDist = minDist,
                )
                if (aLanding != null) {
                    landings[aId] = aLanding
                    motions[aId] = bMotion
                    continue
                }
                val bLanding = tryLand(
                    supportId = aId,
                    rocketShape = bShape,
                    rocketControl = bControl,
                    supportShape = aShape,
                    supportTransform = aTransform,
                    rocketTransform = bTransform,
                    landingNormal = inverted(normal),
                    minDist = minDist,
                )
                if (bLanding != null) {
                    landings[bId] = bLanding
                    motions[bId] = aMotion
                    continue
                }

                val massA = aMaterial.mass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
                val massB = bMaterial.mass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
                val totalMass = (massA + massB).coerceAtMost(Int.MAX_VALUE)
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
                val spinAlongTangent = aMotion.angVel * circumferenceA + bMotion.angVel * circumferenceB
                val velAlongTangent = velDelta.dot(tangent) - spinAlongTangent
                val tangentResponse = velAlongTangent * roughness

                val pushNormVelA = normal * (normResponse * invMassWeightA)
                val pushNormVelB = normal * (normResponse * invMassWeightB)
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
            }
        }
        return state.copy(
            transforms = state.transforms.putAll(transforms.map { it.key to it.value }),
            motions = state.motions.putAll(motions.map { it.key to it.value }),
            landings = state.landings.putAll(landings.map { it.key to it.value }),
        )
    }
}

private object ForceFieldSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val motions = LinkedHashMap(state.motions.asMap())
        val ids = state.world.entities
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                if (state.landings.contains(aId) || state.landings.contains(bId)) continue
                val aTransform = state.transforms[aId] ?: continue
                val bTransform = state.transforms[bId] ?: continue
                val aCollider = state.colliders[aId] ?: continue
                val bCollider = state.colliders[bId] ?: continue
                val aMaterial = state.materials[aId] ?: continue
                val bMaterial = state.materials[bId] ?: continue
                val aTeam = state.teams[aId]?.teamId
                val bTeam = state.teams[bId]?.teamId
                val aField = state.forceFields[aId]
                val bField = state.forceFields[bId]

                if (aField != null) {
                    val contact = computeContact(
                        aTransform = aTransform,
                        bTransform = bTransform,
                        aRadius = aCollider.radius + aField.depth,
                        bRadius = bCollider.radius,
                    )
                    if (contact != null) {
                        val sourceMotion = motions[aId] ?: state.motions[aId] ?: continue
                        val targetMotion = motions[bId] ?: state.motions[bId] ?: continue
                        val updated =
                            if (aTeam != null && aTeam == bTeam) {
                                applyVelocityMatchingDamping(
                                    sourceMotion = sourceMotion,
                                    sourceMass = aMaterial.mass,
                                    targetMotion = targetMotion,
                                    targetMass = bMaterial.mass,
                                    worldOffset = inverted(contact.normal) * (contact.minDist - contact.penetration),
                                    desiredTargetVel = surfaceVelocityAtOffset(
                                        sourceMotion = sourceMotion,
                                        worldOffset = inverted(contact.normal) * (contact.minDist - contact.penetration),
                                    ),
                                    linearDamping = FORCE_FIELD_TEAM_DAMPING,
                                )
                            } else {
                                applyForceFieldImpulse(
                                    sourceMotion = sourceMotion,
                                    sourceMass = aMaterial.mass,
                                    targetMotion = targetMotion,
                                    targetMass = bMaterial.mass,
                                    outwardNormal = inverted(contact.normal),
                                    impulse = aField.strength,
                                )
                            }
                        motions[aId] = updated.first
                        motions[bId] = updated.second
                    }
                }
                if (bField != null) {
                    val contact = computeContact(
                        aTransform = aTransform,
                        bTransform = bTransform,
                        aRadius = aCollider.radius,
                        bRadius = bCollider.radius + bField.depth,
                    )
                    if (contact != null) {
                        val targetMotion = motions[aId] ?: state.motions[aId] ?: continue
                        val sourceMotion = motions[bId] ?: state.motions[bId] ?: continue
                        val updated =
                            if (bTeam != null && bTeam == aTeam) {
                                applyVelocityMatchingDamping(
                                    sourceMotion = sourceMotion,
                                    sourceMass = bMaterial.mass,
                                    targetMotion = targetMotion,
                                    targetMass = aMaterial.mass,
                                    worldOffset = contact.normal * (contact.minDist - contact.penetration),
                                    desiredTargetVel = surfaceVelocityAtOffset(
                                        sourceMotion = sourceMotion,
                                        worldOffset = contact.normal * (contact.minDist - contact.penetration),
                                    ),
                                    linearDamping = FORCE_FIELD_TEAM_DAMPING,
                                )
                            } else {
                                applyForceFieldImpulse(
                                    sourceMotion = sourceMotion,
                                    sourceMass = bMaterial.mass,
                                    targetMotion = targetMotion,
                                    targetMass = aMaterial.mass,
                                    outwardNormal = contact.normal,
                                    impulse = bField.strength,
                                )
                            }
                        motions[bId] = updated.first
                        motions[aId] = updated.second
                    }
                }
            }
        }
        return state.copy(motions = state.motions.putAll(motions.map { it.key to it.value }))
    }
}

private object AttachmentSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val transforms = LinkedHashMap(state.transforms.asMap())
        val motions = LinkedHashMap(state.motions.asMap())
        var landings = state.landings
        for ((entityId, landing) in state.landings.entries()) {
            val parentTransform = transforms[landing.parentEntityId]
            val parentMotion = motions[landing.parentEntityId]
            val transform = transforms[entityId]
            if (parentTransform == null || parentMotion == null || transform == null) {
                landings = landings.remove(entityId)
                continue
            }
            transforms[entityId] = transform.copy(
                pos = parentTransform.pos + rotateByAngle(landing.relativePos, parentTransform.ang),
                ang = Frac(parentTransform.ang.raw + landing.relativeAng.raw),
            )
            motions[entityId] = parentMotion
        }
        return state.copy(
            transforms = state.transforms.putAll(transforms.map { it.key to it.value }),
            motions = state.motions.putAll(motions.map { it.key to it.value }),
            landings = landings,
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
    val alignment = dot(forward, landingNormal)
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
    val snappedRocketAng = angleFromNorm(landingNormal)
    return LandingAttachmentComponent(
        parentEntityId = supportId,
        relativePos = rotateByAngle(landingNormal * minDist, Frac(-supportTransform.ang.raw)),
        relativeAng = snappedRocketAng - supportTransform.ang,
    )
}

private data class Contact(
    val minDist: Frac,
    val penetration: Frac,
    val normal: Norm,
    val tangent: Norm,
)

private fun computeContact(
    aTransform: TransformComponent,
    bTransform: TransformComponent,
    aRadius: Frac,
    bRadius: Frac,
): Contact? {
    // Use shortest torus delta for both rigid collision and shield overlap tests.
    val delta = aTransform.pos - bTransform.pos
    val minDist = aRadius + bRadius
    val xPen = minDist - abs(delta.x)
    val yPen = minDist - abs(delta.y)
    if (xPen.sign <= 0 || yPen.sign <= 0) return null
    if (delta >= minDist) return null
    if (delta.lenSq.raw == 0) return null
    delta.capMax(minDist)
    val normal = delta.norm
    return Contact(
        minDist = minDist,
        penetration = minDist - delta.len,
        normal = normal,
        tangent = normal.perp,
    )
}

private fun dot(a: Norm, b: Norm): Frac = a.x * b.x + a.y * b.y

private fun inverted(n: Norm): Norm = Norm(-n.x, -n.y)

private fun rotateByAngle(v: Frac2, angle: Frac): Frac2 {
    val rotation = Norm.fromAngle(angle)
    return Frac2(
        x = v.x * rotation.x - v.y * rotation.y,
        y = v.x * rotation.y + v.y * rotation.x,
    )
}

private fun angleFromNorm(n: Norm): Frac {
    val angleTurns = atan2(n.y.toFloat(), n.x.toFloat()) / (2f * PI.toFloat())
    return Frac((angleTurns * Int.MAX_VALUE.toFloat()).roundToInt())
}

private fun surfaceVelocityAtAttachment(
    parentTransform: TransformComponent,
    parentMotion: MotionComponent,
    landing: LandingAttachmentComponent,
): Frac2 {
    val worldOffset = rotateByAngle(landing.relativePos, parentTransform.ang)
    return surfaceVelocityAtOffset(
        sourceMotion = parentMotion,
        worldOffset = worldOffset,
    )
}

private fun ccwPerp(n: Norm): Norm = Norm(-n.y, n.x)

private fun surfaceVelocityAtOffset(
    sourceMotion: MotionComponent,
    worldOffset: Frac2,
): Frac2 {
    if (worldOffset.lenSq.raw == 0) {
        return sourceMotion.vel
    }
    val tangent = ccwPerp(worldOffset.norm)
    val spinSpeed = worldOffset.len.toCircumference() * sourceMotion.angVel
    return sourceMotion.vel + tangent * spinSpeed
}

private fun applyForceFieldImpulse(
    sourceMotion: MotionComponent,
    sourceMass: UInt,
    targetMotion: MotionComponent,
    targetMass: UInt,
    outwardNormal: Norm,
    impulse: Frac,
): Pair<MotionComponent, MotionComponent> {
    val safeSourceMass = sourceMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
    val safeTargetMass = targetMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
    val sourceDelta = outwardNormal * (impulse / safeSourceMass)
    val targetDelta = outwardNormal * (impulse / safeTargetMass)
    return sourceMotion.copy(
        vel = sourceMotion.vel - sourceDelta,
    ) to targetMotion.copy(
        vel = targetMotion.vel + targetDelta,
    )
}

private fun applyVelocityMatchingDamping(
    sourceMotion: MotionComponent,
    sourceMass: UInt,
    targetMotion: MotionComponent,
    targetMass: UInt,
    worldOffset: Frac2,
    desiredTargetVel: Frac2,
    linearDamping: Frac,
): Pair<MotionComponent, MotionComponent> {
    val safeSourceMass = sourceMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
    val safeTargetMass = targetMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
    val totalMass = (safeSourceMass + safeTargetMass).coerceAtMost(Int.MAX_VALUE)
    val sourceWeight = Frac(safeTargetMass, totalMass)
    val targetWeight = Frac(safeSourceMass, totalMass)

    val velDelta = scale(desiredTargetVel - targetMotion.vel, linearDamping)
    val targetTangentialDelta = if (worldOffset.lenSq.raw == 0) {
        Frac(0)
    } else {
        velDelta.dot(ccwPerp(worldOffset.norm))
    }
    val sourceAngDelta = if (worldOffset.lenSq.raw == 0) {
        Frac(0)
    } else {
        -(targetTangentialDelta * targetWeight) / worldOffset.len.toCircumference()
    }

    return sourceMotion.copy(
        vel = sourceMotion.vel - scale(velDelta, sourceWeight),
        angVel = sourceMotion.angVel + sourceAngDelta,
    ) to targetMotion.copy(
        vel = targetMotion.vel + scale(velDelta, targetWeight),
    )
}

private fun scale(v: Frac2, s: Frac): Frac2 = Frac2(
    x = v.x * s,
    y = v.y * s,
)
