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
import org.emerge.sim.core.physics.primitives.Frac2
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
        if (springComps.isEmpty()) return
        val n = springComps.size

        // Cache each spring-bearing entity's components into flat arrays under a dense index,
        // so the inner loop reads positions/velocities/mass by array index instead of a
        // per-spring component-table lookup. This is the same component-caching the
        // ContactSystem uses. When springs are registered symmetrically (both endpoints
        // carry the component — as cyto does), every `other` is in this index and the inner
        // loop never touches the component tables; a one-sided spring whose `other` is absent
        // falls back to a direct lookup. Reads are valid for the whole phase because nothing
        // writes Transform/Motion/Material before the solver (integration and structural
        // changes run in later phases).
        val ids = arrayOfNulls<EntityId>(n)
        val index = HashMap<EntityId, Int>(n)
        val transforms = arrayOfNulls<TransformComponent>(n)
        val motions = arrayOfNulls<MotionComponent>(n)
        val masses = LongArray(n)
        val springCounts = IntArray(n)
        run {
            var i = 0
            for ((id, comp) in springComps) {
                ids[i] = id
                index[id] = i
                transforms[i] = builder.getComponent<TransformComponent>(id)
                motions[i] = builder.getComponent<MotionComponent>(id)
                masses[i] = (builder.getComponent<MaterialComponent>(id)?.mass ?: 1u).toLong()
                springCounts[i] = comp.springs.size
                i++
            }
        }

        // Accumulate each entity's total spring impulse, then apply it with a single
        // ImpulseComponent write per entity (was two writes per spring). Frac2 addition is
        // integer-exact and order-independent, so the accumulated sum is bit-identical to
        // folding each spring's impulse in one at a time.
        val impulse = arrayOfNulls<Frac2>(n)

        var ai = 0
        for ((id, comp) in springComps) {
            val aIdx = ai++
            if (comp.springs.isEmpty()) continue
            val transformA = transforms[aIdx] ?: continue
            val motionA = motions[aIdx] ?: continue
            val massA = masses[aIdx]
            val countA = comp.springs.size

            for (spring in comp.springs) {
                val other = spring.other
                // Solve each pair exactly once, from the smaller-id endpoint.
                if (other.value <= id.value) continue
                // Cached fast path when `other` carries the component; fall back to a direct
                // lookup for a one-sided spring (`other` not in the index).
                val bIdx = index[other]
                val transformB: TransformComponent
                val motionB: MotionComponent
                val massB: Long
                val otherCount: Int
                if (bIdx != null) {
                    transformB = transforms[bIdx] ?: continue
                    motionB = motions[bIdx] ?: continue
                    massB = masses[bIdx]
                    otherCount = springCounts[bIdx]
                } else {
                    transformB = builder.getComponent<TransformComponent>(other) ?: continue
                    motionB = builder.getComponent<MotionComponent>(other) ?: continue
                    massB = (builder.getComponent<MaterialComponent>(other)?.mass ?: 1u).toLong()
                    otherCount = builder.getComponent<SpringConstraintComponent>(other)?.springs?.size ?: 1
                }

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
                val relaxation = maxOf(countA, otherCount, 1)
                val closingSpeed = rawClosingSpeed / relaxation

                val totalMass = massA + massB
                if (totalMass <= 0L) continue
                // Lighter body moves more: A's share is weighted by B's mass.
                val weightA = Frac(massB, totalMass.toInt())
                val weightB = Frac(massA, totalMass.toInt())

                val speedA = closingSpeed * weightA
                val speedB = closingSpeed * weightB

                // A accelerates toward B (+normal); B toward A (-normal). A is always cached
                // (it owns the springs); B is batched when cached, else written through.
                impulse[aIdx] = (normal * speedA) + impulse[aIdx]
                if (bIdx != null) {
                    impulse[bIdx] = -(normal * speedB) + impulse[bIdx]
                } else {
                    builder.update<ImpulseComponent>(other) { ImpulseComponent(vel = -(normal * speedB)) + it }
                }
            }
        }

        for (k in 0 until n) {
            val imp = impulse[k] ?: continue
            builder.update<ImpulseComponent>(ids[k]!!) { ImpulseComponent(vel = imp) + it }
        }
    }
}
