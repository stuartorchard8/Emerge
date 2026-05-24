package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.contacts
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.SimInput

/**
 * Applies angular damping while bodies are in contact to model rolling resistance.
 *
 * This is intentionally separate from bounce/tangential collision response:
 * - roughness controls tangential velocity equalization during impact
 * - rolling resistance bleeds rotational energy during sustained contact
 */
object RollingResistanceSystem : EcsSystem<PhysicsTuning, SimState, SimInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: SimBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        if (cfg.rollingResistance.raw <= 0L) return
        for (contact in builder.contacts) {
            val aId = contact.aId
            val bId = contact.bId
            val aMaterial = builder.getComponent<MaterialComponent>(aId) ?: continue
            val bMaterial = builder.getComponent<MaterialComponent>(bId) ?: continue
            val aMotion = builder.getComponent<MotionComponent>(aId) ?: continue
            val bMotion = builder.getComponent<MotionComponent>(bId) ?: continue

            val roughness = aMaterial.rough.coerceAtMost(bMaterial.rough)
            if (roughness.raw <= 0L) continue

            val massA = aMaterial.mass.toLong()
            val massB = bMaterial.mass.toLong()
            val totalMass = (massA + massB).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val invMassWeightA = Frac(massB, totalMass)
            val invMassWeightB = Frac(massA, totalMass)

            val resistance = cfg.rollingResistance * roughness
            val angDampA = dampAngularVelocity(
                angularVelocity = Frac(aMotion.angVel.raw.toLong()),
                resistance = resistance * invMassWeightA,
            )
            val angDampB = dampAngularVelocity(
                angularVelocity = Frac(bMotion.angVel.raw.toLong()),
                resistance = resistance * invMassWeightB,
            )
            if (angDampA.raw == 0L && angDampB.raw == 0L) continue

            builder.update<ImpulseComponent>(aId) { ImpulseComponent(angVel = angDampA) + it }
            builder.update<ImpulseComponent>(bId) { ImpulseComponent(angVel = angDampB) + it }
        }
    }

    private fun dampAngularVelocity(
        angularVelocity: Frac,
        resistance: Frac,
    ): Frac {
        if (angularVelocity.raw == 0L || resistance.raw <= 0L) return Frac(0)
        val requested = -(angularVelocity * resistance)
        return clampDelta(requested, angularVelocity)
    }

    private fun clampDelta(delta: Frac, value: Frac): Frac {
        val absDelta = Frac.abs(delta)
        val absValue = Frac.abs(value)
        if (absDelta.raw <= absValue.raw) return delta
        return if (value.sign >= 0) -absValue else absValue
    }
}
