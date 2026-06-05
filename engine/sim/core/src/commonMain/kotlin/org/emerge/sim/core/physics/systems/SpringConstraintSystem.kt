package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimInput
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Soft distance-constraint (spring) solver. For each [SpringConstraintComponent.springs]
 * entry it drives the two bodies' separation toward the spring's rest length and damps
 * their relative approach/retreat speed, applying mass-weighted, equal-and-opposite
 * velocity impulses via [ImpulseComponent] (which [IntegrationSystem] then integrates).
 *
 * A single explicit pass per tick — not Box2D's iterative soft constraint — so it is an
 * approximation: pick [SpringConstraint.stiffness]/[SpringConstraint.damping] to taste
 * rather than expecting frequency-hz/damping-ratio parity. Each pair is solved once, from
 * the lower-id endpoint, so springs must be registered on (at least) the smaller id.
 */
object SpringConstraintSystem : EcsSystem<PhysicsTuning, SimState, SimInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: SimBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        for ((id, comp) in builder.entries<SpringConstraintComponent>()) {
            if (comp.springs.isEmpty()) continue
            val transformA = builder.getComponent<TransformComponent>(id) ?: continue
            val motionA = builder.getComponent<MotionComponent>(id) ?: continue
            val massA = builder.getComponent<MaterialComponent>(id)?.mass ?: 1u

            for (spring in comp.springs) {
                val other = spring.other
                // Solve each pair exactly once, from the smaller-id endpoint.
                if (other.value <= id.value) continue
                val transformB = builder.getComponent<TransformComponent>(other) ?: continue
                val motionB = builder.getComponent<MotionComponent>(other) ?: continue
                val massB = builder.getComponent<MaterialComponent>(other)?.mass ?: 1u

                val delta = transformB.pos - transformA.pos // Frac2, A -> B
                val dist = delta.len
                if (dist.raw == 0L) continue
                val normal = delta.norm

                // +ve = stretched (too far apart).
                val lengthError = dist - spring.restLength
                // +ve = separating along the normal.
                val separationSpeed = (motionB.vel - motionA.vel).dot(normal)

                // Desired closing speed this tick: pull in proportional to the stretch,
                // plus damp out the current separation velocity.
                val closingSpeed = lengthError * spring.stiffness + separationSpeed * spring.damping

                val totalMass = (massA + massB).toLong()
                if (totalMass <= 0L) continue
                // Lighter body moves more: A's share is weighted by B's mass.
                val weightA = Frac(massB.toLong(), totalMass.toInt())
                val weightB = Frac(massA.toLong(), totalMass.toInt())

                val speedA = closingSpeed * weightA
                val speedB = closingSpeed * weightB

                // A accelerates toward B (+normal); B toward A (-normal).
                builder.update<ImpulseComponent>(id) { ImpulseComponent(vel = normal * speedA) + it }
                builder.update<ImpulseComponent>(other) { ImpulseComponent(vel = -(normal * speedB)) + it }
            }
        }
    }
}
