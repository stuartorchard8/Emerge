package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.*


object ForceFieldSystem : EcsSystem<PhysicsConfig, PhysicsInput> {
    private val FORCE_FIELD_TEAM_DAMPING = Frac(1, 64)

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val ids = builder.initial.raw.materials.keys().toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                if (builder.getComponent<LandingAttachmentComponent>(aId) != null
                    || builder.getComponent<LandingAttachmentComponent>(bId) != null ) continue

                val aTransform = builder.getComponent<TransformComponent>(aId) ?: continue
                val bTransform = builder.getComponent<TransformComponent>(bId) ?: continue
                val aMaterial = builder.getComponent<MaterialComponent>(aId) ?: continue
                val bMaterial = builder.getComponent<MaterialComponent>(bId) ?: continue
                val aCollider = builder.getComponent<ColliderComponent>(aId) ?: continue
                val bCollider = builder.getComponent<ColliderComponent>(bId) ?: continue
                val aField = builder.getComponent<ForceFieldComponent>(aId)
                val bField = builder.getComponent<ForceFieldComponent>(bId)
                if (aField == null && bField == null) continue
                val aTeam = builder.getComponent<TeamComponent>(aId)?.teamId
                val bTeam = builder.getComponent<TeamComponent>(bId)?.teamId

                val contact = Contact.compute(
                    aId = aId,
                    bId = bId,
                    aTransform = aTransform,
                    bTransform = bTransform,
                    aRadius = aCollider.radius + (aField?.depth ?: Frac(0)),
                    bRadius = bCollider.radius + (bField?.depth ?: Frac(0)),
                )
                if (contact != null) {
                    val aMotion = builder.getComponent<MotionComponent>(aId) ?: continue
                    val bMotion = builder.getComponent<MotionComponent>(bId) ?: continue
                    val (aImpulse, bImpulse) = getFieldImpulse(
                        aTeam = aTeam,
                        bTeam = bTeam,
                        aMass = aMaterial.mass,
                        bMass = bMaterial.mass,
                        aMotion = aMotion,
                        bMotion = bMotion,
                        contact = contact,
                        aFieldStrength = aField?.strength ?: Frac(0),
                        bFieldStrength = bField?.strength ?: Frac(0),
                    )

                    builder.update<ImpulseComponent>(aId) { aImpulse + it }
                    builder.update<ImpulseComponent>(bId) { bImpulse + it }
                }
            }
        }
    }

    private fun getFieldImpulse(
        aTeam: TeamId?,
        bTeam: TeamId?,
        aMass: UInt,
        bMass: UInt,
        aMotion: MotionComponent,
        bMotion: MotionComponent,
        contact: Contact,
        aFieldStrength: Frac,
        bFieldStrength: Frac,
    ) : Pair<ImpulseComponent, ImpulseComponent> {
        if (aTeam != null && aTeam == bTeam) {
            return applyVelocityMatchingDamping(
                aMass = aMass,
                bMass = bMass,
                aDrag = fieldDragAtOffset(
                    worldOffset = contact.normal * (contact.minDist - contact.penetration),
                    aMotion = aMotion,
                    bMotion = bMotion,
                    bHasField = bFieldStrength.raw > 0,
                )*FORCE_FIELD_TEAM_DAMPING,
                bDrag = fieldDragAtOffset(
                    worldOffset = -contact.normal * (contact.minDist - contact.penetration),
                    aMotion = bMotion,
                    bMotion = aMotion,
                    bHasField = aFieldStrength.raw > 0,
                )*FORCE_FIELD_TEAM_DAMPING,
                worldOffset = -contact.normal * (contact.minDist - contact.penetration),
                aHasField = aFieldStrength.raw > 0,
                bHasField = bFieldStrength.raw > 0,
            )
        } else {
            return applyForceFieldAcceleration(
                aMass = aMass,
                bMass = bMass,
                outwardNormal = -contact.normal,
                acc = aFieldStrength+bFieldStrength,
            )
        }
    }

    private fun fieldDragAtOffset(
        worldOffset: Frac2,
        aMotion: MotionComponent,
        bMotion: MotionComponent,
        bHasField: Boolean,
    ): Frac2 {
        if (worldOffset.lenSq.raw == 0L) {
            return Frac2.zero
        }
        var spinDrag = Frac2.zero
        if (bHasField) {
            val tangent = worldOffset.norm.cw90
            val spinSpeed = worldOffset.len.toCircumference() * Frac(bMotion.angVel.raw.toLong())
            spinDrag = tangent * spinSpeed
        }
        return (bMotion.vel-aMotion.vel) - spinDrag
    }

    private fun applyForceFieldAcceleration(
        aMass: UInt,
        bMass: UInt,
        outwardNormal: Norm,
        acc: Frac,
    ): Pair<ImpulseComponent, ImpulseComponent> {
        val safeSourceMass = aMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
        val safeTargetMass = bMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
        val sourceDelta = -outwardNormal * Frac(acc.toLong() * safeTargetMass.toLong() / safeSourceMass.toLong())
        val targetDelta = outwardNormal * Frac(acc.toLong() * safeSourceMass.toLong() / safeTargetMass.toLong())
        return ImpulseComponent(
            vel = sourceDelta,
        ) to ImpulseComponent(
            vel = targetDelta,
        )
    }

    private fun applyVelocityMatchingDamping(
        aMass: UInt,
        bMass: UInt,
        aDrag: Frac2,
        bDrag: Frac2,
        aHasField: Boolean,
        bHasField: Boolean,
        worldOffset: Frac2,
    ): Pair<ImpulseComponent, ImpulseComponent> {
        val safeSourceMass = aMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
        val safeTargetMass = bMass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
        val totalMass = (safeSourceMass + safeTargetMass).coerceAtMost(Int.MAX_VALUE)
        val aWeight = Frac(aMass.toLong(), totalMass)
        val bWeight = Frac(bMass.toLong(), totalMass)

        val velDelta = aDrag - bDrag
        val targetTangentialDelta = if (worldOffset.lenSq.raw == 0L) {
            Frac(0)
        } else {
            velDelta.dot(worldOffset.norm.cw90)
        }
        val angDelta = if (worldOffset.lenSq.raw == 0L) {
            Frac(0)
        } else {
            -(targetTangentialDelta) / worldOffset.len.toCircumference()
        }

        return ImpulseComponent(
            vel = aDrag*bWeight,
            angVel = if (aHasField) angDelta*bWeight else Frac(0),
        ) to ImpulseComponent(
            vel = bDrag*aWeight,
            angVel = if (bHasField) angDelta*bWeight else Frac(0),
        )
    }
}