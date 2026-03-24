package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.collections.set


object ForceFieldSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val FORCE_FIELD_TEAM_DAMPING = Frac(1, 64)

    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val motions = LinkedHashMap(state.raw.motions.asMap())
        val ids = state.raw.materials.keys().toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                if (state.raw.landings.contains(aId) || state.raw.landings.contains(bId)) continue
                val aTransform = state.raw.transforms[aId] ?: continue
                val bTransform = state.raw.transforms[bId] ?: continue
                val aCollider = state.raw.colliders[aId] ?: continue
                val bCollider = state.raw.colliders[bId] ?: continue
                val aMaterial = state.raw.materials[aId] ?: continue
                val bMaterial = state.raw.materials[bId] ?: continue
                val aTeam = state.raw.teams[aId]?.teamId
                val bTeam = state.raw.teams[bId]?.teamId
                val aField = state.raw.forceFields[aId]
                val bField = state.raw.forceFields[bId]

                if (aField != null) {
                    val contact = Contact.compute(
                        aTransform = aTransform,
                        bTransform = bTransform,
                        aRadius = aCollider.radius + aField.depth,
                        bRadius = bCollider.radius,
                    )
                    if (contact != null) {
                        val sourceMotion = motions[aId] ?: state.raw.motions[aId] ?: continue
                        val targetMotion = motions[bId] ?: state.raw.motions[bId] ?: continue
                        val updated =
                            if (aTeam != null && aTeam == bTeam) {
                                applyVelocityMatchingDamping(
                                    sourceMotion = sourceMotion,
                                    sourceMass = aMaterial.mass,
                                    targetMotion = targetMotion,
                                    targetMass = bMaterial.mass,
                                    worldOffset = contact.normal * (contact.minDist - contact.penetration),
                                    desiredTargetVel = surfaceVelocityAtOffset(
                                        sourceMotion = sourceMotion,
                                        worldOffset = -contact.normal * (contact.minDist - contact.penetration),
                                    ),
                                    linearDamping = FORCE_FIELD_TEAM_DAMPING,
                                )
                            } else {
                                applyForceFieldAcceleration(
                                    sourceMotion = sourceMotion,
                                    sourceMass = aMaterial.mass,
                                    targetMotion = targetMotion,
                                    targetMass = bMaterial.mass,
                                    outwardNormal = -contact.normal,
                                    sourceAcc = aField.strength,
                                )
                            }
                        motions[aId] = updated.first
                        motions[bId] = updated.second
                    }
                }
                if (bField != null) {
                    val contact = Contact.compute(
                        aTransform = aTransform,
                        bTransform = bTransform,
                        aRadius = aCollider.radius,
                        bRadius = bCollider.radius + bField.depth,
                    )
                    if (contact != null) {
                        val targetMotion = motions[aId] ?: state.raw.motions[aId] ?: continue
                        val sourceMotion = motions[bId] ?: state.raw.motions[bId] ?: continue
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
                                applyForceFieldAcceleration(
                                    sourceMotion = sourceMotion,
                                    sourceMass = bMaterial.mass,
                                    targetMotion = targetMotion,
                                    targetMass = aMaterial.mass,
                                    outwardNormal = contact.normal,
                                    sourceAcc = bField.strength,
                                )
                            }
                        motions[bId] = updated.first
                        motions[aId] = updated.second
                    }
                }
            }
        }
        state.raw = state.raw.copy(motions = ComponentTable.fromMap(motions))
    }

    private fun surfaceVelocityAtOffset(
        sourceMotion: MotionComponent,
        worldOffset: Frac2,
    ): Coord2 {
        if (worldOffset.lenSq.raw == 0L) {
            return sourceMotion.vel
        }
        val tangent = worldOffset.norm.cw90
        val spinSpeed = worldOffset.len.toCircumference() * Frac(sourceMotion.angVel.raw.toLong())
        return sourceMotion.vel - tangent * spinSpeed
    }

    private fun applyForceFieldAcceleration(
        sourceMotion: MotionComponent,
        sourceMass: UInt,
        targetMotion: MotionComponent,
        targetMass: UInt,
        outwardNormal: Norm,
        sourceAcc: Frac,
    ): Pair<MotionComponent, MotionComponent> {
        val safeSourceMass = sourceMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
        val safeTargetMass = targetMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
        val sourceDelta = outwardNormal * Frac(sourceAcc.toLong() * safeTargetMass.toLong() / safeSourceMass.toLong())
        val targetDelta = outwardNormal * sourceAcc
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
        desiredTargetVel: Coord2,
        linearDamping: Frac,
    ): Pair<MotionComponent, MotionComponent> {
        val safeSourceMass = sourceMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
        val safeTargetMass = targetMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
        val totalMass = (safeSourceMass + safeTargetMass).coerceAtMost(Int.MAX_VALUE)
        val sourceWeight = Frac(targetMass.toLong(), totalMass)
        val targetWeight = Frac(sourceMass.toLong(), totalMass)

        val velDelta = (desiredTargetVel.asFrac2() - targetMotion.vel.asFrac2()) * linearDamping
        val targetTangentialDelta = if (worldOffset.lenSq.raw == 0L) {
            Frac(0)
        } else {
            velDelta.dot(worldOffset.norm.cw90)
        }
        val sourceAngDelta = if (worldOffset.lenSq.raw == 0L) {
            Frac(0)
        } else {
            -(targetTangentialDelta * targetWeight) / worldOffset.len.toCircumference()
        }

        return sourceMotion.copy(
            vel = sourceMotion.vel - velDelta*sourceWeight,
            angVel = sourceMotion.angVel + sourceAngDelta*sourceWeight,
        ) to targetMotion.copy(
            vel = targetMotion.vel + velDelta*targetWeight,
        )
    }
}