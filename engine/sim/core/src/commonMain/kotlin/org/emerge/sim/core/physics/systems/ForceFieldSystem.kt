package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import kotlin.collections.set


object ForceFieldSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val FORCE_FIELD_TEAM_DAMPING = Frac(1, 32)

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
                    val contact = Contact.compute(
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
                                val relativeSpeed = (targetMotion.vel-sourceMotion.vel).dot(contact.normal)
                                if (relativeSpeed.raw > 1024 * 512) {
                                    applyForceFieldAcceleration(
                                        sourceMotion = sourceMotion,
                                        sourceMass = aMaterial.mass,
                                        targetMotion = targetMotion,
                                        targetMass = bMaterial.mass,
                                        outwardNormal = -contact.normal,
                                        sourceAcc = aField.strength,
                                    )
                                } else {
                                    sourceMotion to targetMotion
                                }
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
                        val targetMotion = motions[aId] ?: state.motions[aId] ?: continue
                        val sourceMotion = motions[bId] ?: state.motions[bId] ?: continue
                        val updated =
                            if (bTeam != null && bTeam == aTeam) {
                                sourceMotion to targetMotion
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
        return state.copy(motions = ComponentTable.fromMap(motions))
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
}