package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
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
 *
 * Because all of a body's springs are applied in one pass (Jacobi, not Gauss–Seidel),
 * a body in a dense cluster would otherwise receive N independent corrections that sum to
 * an over-relaxed, unstable kick. Each pair's correction is therefore under-relaxed by the
 * larger of the two endpoints' spring counts, which keeps a body's total per-tick
 * correction bounded (~one spring's worth) and the system stable at any connectivity. A
 * lone spring (count 1) is unaffected.
 */
object SpringConstraintSystem : EcsSystem<PhysicsTuning, SimState, SimInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: SimBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        val springComps = builder.entries<SpringConstraintComponent>()
        // Relaxation divides each correction by the larger endpoint's spring count. Read
        // that count from a flat map built once, instead of a per-spring component lookup.
        val springCounts = HashMap<EntityId, Int>(springComps.size)
        for ((id, comp) in springComps) springCounts[id] = comp.springs.size

        for ((id, comp) in springComps) {
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
                val rawClosingSpeed = lengthError * spring.stiffness + separationSpeed * spring.damping
                // Under-relax by connectivity so a clustered body's many springs don't sum
                // to an unstable over-correction (Jacobi stability). Lone springs: ÷1.
                val otherCount = springCounts[other] ?: 1
                val relaxation = maxOf(comp.springs.size, otherCount, 1)
                val closingSpeed = rawClosingSpeed / relaxation

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
