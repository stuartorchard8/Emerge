package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimInput
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
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
 *
 * **Component caching.** Per-entity components are cached into flat arrays under a dense
 * index once per tick (as [ContactSystem] does), so the inner loop reads positions/
 * velocities/mass by index rather than per-spring table lookups. Reads are valid for the
 * whole phase: nothing writes Transform/Motion/Material before the solver.
 *
 * **Parallelism.** With [executor] present and entity count `>= [PARALLEL_THRESHOLD]`, the
 * lower-id-owner loop is sliced into contiguous chunks. Each worker accumulates impulses
 * into its own full-width buffer (so two workers never write the same slot), and the main
 * thread sums the buffers and applies one [ImpulseComponent] write per entity. Because
 * [Frac2] addition is integer-exact and order-independent, the merged totals are
 * bit-identical to the sequential solve regardless of how entities are chunked. Workers
 * never touch the builder; a rare one-sided spring (`other` carries no component, so it
 * isn't in the index) is deferred to a sequential post-pass where builder access is safe.
 */
class SpringConstraintSystem(
    private val executor: ParallelExecutor? = null,
) : EcsSystem<PhysicsTuning, SimState, SimInput> {

    override fun update(
        cfg: PhysicsTuning,
        builder: SimBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        val springComps = builder.entries<SpringConstraintComponent>()
        if (springComps.isEmpty()) return
        val n = springComps.size

        val ids = arrayOfNulls<EntityId>(n)
        val index = HashMap<EntityId, Int>(n)
        val transforms = arrayOfNulls<TransformComponent>(n)
        val motions = arrayOfNulls<MotionComponent>(n)
        val masses = LongArray(n)
        val springCounts = IntArray(n)
        val springLists = arrayOfNulls<List<SpringConstraint>>(n)
        run {
            var i = 0
            for ((id, comp) in springComps) {
                ids[i] = id
                index[id] = i
                transforms[i] = builder.getComponent<TransformComponent>(id)
                motions[i] = builder.getComponent<MotionComponent>(id)
                masses[i] = (builder.getComponent<MaterialComponent>(id)?.mass ?: 1u).toLong()
                springCounts[i] = comp.springs.size
                springLists[i] = comp.springs
                i++
            }
        }
        val cache = Cache(ids, index, transforms, motions, masses, springCounts, springLists)

        // Total impulse per entity, applied with one write each at the end. Out-of-index
        // (one-sided) springs are collected here and resolved on the main thread afterwards.
        val impulse = arrayOfNulls<Frac2>(n)
        val deferred = ArrayList<Deferred>()

        if (executor != null && n >= PARALLEL_THRESHOLD) {
            val chunkCount = executor.parallelism.coerceAtMost(n).coerceAtLeast(1)
            val step = (n + chunkCount - 1) / chunkCount
            val buffers = arrayOfNulls<Array<Frac2?>>(chunkCount)
            val deferrals = arrayOfNulls<MutableList<Deferred>>(chunkCount)
            val tasks = ArrayList<() -> Unit>(chunkCount)
            for (c in 0 until chunkCount) {
                val start = c * step
                val end = ((c + 1) * step).coerceAtMost(n)
                if (start >= end) continue
                tasks += {
                    val buf = arrayOfNulls<Frac2>(n)
                    val def = ArrayList<Deferred>()
                    collectRange(cache, start, end, buf, def)
                    buffers[c] = buf
                    deferrals[c] = def
                }
            }
            executor.invokeAll(tasks)
            for (c in 0 until chunkCount) {
                val buf = buffers[c] ?: continue
                for (k in 0 until n) {
                    val v = buf[k] ?: continue
                    impulse[k] = v + impulse[k]
                }
                deferrals[c]?.let { deferred.addAll(it) }
            }
        } else {
            collectRange(cache, 0, n, impulse, deferred)
        }

        // Resolve one-sided springs sequentially (builder access is safe here).
        for (d in deferred) resolveDeferred(builder, cache, d, impulse)

        for (k in 0 until n) {
            val imp = impulse[k] ?: continue
            builder.update<ImpulseComponent>(ids[k]!!) { ImpulseComponent(vel = imp) + it }
        }
    }

    /** Bundled per-tick caches, all read-only during the parallel phase. */
    private class Cache(
        val ids: Array<EntityId?>,
        val index: HashMap<EntityId, Int>,
        val transforms: Array<TransformComponent?>,
        val motions: Array<MotionComponent?>,
        val masses: LongArray,
        val springCounts: IntArray,
        val springLists: Array<List<SpringConstraint>?>,
    )

    /** A spring whose `other` endpoint wasn't in the index — resolved on the main thread. */
    private class Deferred(val aIdx: Int, val spring: SpringConstraint)

    /**
     * Solves every spring owned by entities in `[aStart, aEnd)` from their lower-id end,
     * adding the equal-and-opposite impulses into [out] (indexed by the dense entity index).
     * Touches only [out] and [deferred] — never the builder — so it is safe to run on a
     * worker thread over a disjoint `aIdx` slice.
     */
    private fun collectRange(
        c: Cache,
        aStart: Int,
        aEnd: Int,
        out: Array<Frac2?>,
        deferred: MutableList<Deferred>,
    ) {
        for (aIdx in aStart until aEnd) {
            val springs = c.springLists[aIdx] ?: continue
            if (springs.isEmpty()) continue
            val id = c.ids[aIdx]!!
            val transformA = c.transforms[aIdx] ?: continue
            val motionA = c.motions[aIdx] ?: continue
            val massA = c.masses[aIdx]
            val countA = springs.size

            for (spring in springs) {
                val other = spring.other
                // Solve each pair exactly once, from the smaller-id endpoint.
                if (other.value <= id.value) continue
                val bIdx = c.index[other]
                if (bIdx == null) {
                    // One-sided spring: needs a component lookup we can't do off-thread.
                    deferred.add(Deferred(aIdx, spring))
                    continue
                }
                val transformB = c.transforms[bIdx] ?: continue
                val motionB = c.motions[bIdx] ?: continue
                val pair = solvePair(
                    transformA, motionA, massA, countA,
                    transformB, motionB, c.masses[bIdx], c.springCounts[bIdx], spring,
                ) ?: continue
                out[aIdx] = pair.aImpulse + out[aIdx]
                out[bIdx] = pair.bImpulse + out[bIdx]
            }
        }
    }

    private fun resolveDeferred(builder: SimBuilder, c: Cache, d: Deferred, impulse: Array<Frac2?>) {
        val aIdx = d.aIdx
        val other = d.spring.other
        val transformA = c.transforms[aIdx] ?: return
        val motionA = c.motions[aIdx] ?: return
        val countA = c.springLists[aIdx]?.size ?: return
        val transformB = builder.getComponent<TransformComponent>(other) ?: return
        val motionB = builder.getComponent<MotionComponent>(other) ?: return
        val massB = (builder.getComponent<MaterialComponent>(other)?.mass ?: 1u).toLong()
        val otherCount = builder.getComponent<SpringConstraintComponent>(other)?.springs?.size ?: 1
        val pair = solvePair(
            transformA, motionA, c.masses[aIdx], countA,
            transformB, motionB, massB, otherCount, d.spring,
        ) ?: return
        impulse[aIdx] = pair.aImpulse + impulse[aIdx]
        builder.update<ImpulseComponent>(other) { ImpulseComponent(vel = pair.bImpulse) + it }
    }

    private class PairImpulse(val aImpulse: Frac2, val bImpulse: Frac2)

    /** The shared per-pair math; null when the pair is coincident or massless (skip). */
    private fun solvePair(
        transformA: TransformComponent,
        motionA: MotionComponent,
        massA: Long,
        countA: Int,
        transformB: TransformComponent,
        motionB: MotionComponent,
        massB: Long,
        otherCount: Int,
        spring: SpringConstraint,
    ): PairImpulse? {
        val delta = transformB.pos - transformA.pos // Frac2, A -> B
        val dist = delta.len
        if (dist.raw == 0L) return null
        val normal = delta.norm

        // +ve = stretched (too far apart).
        val lengthError = dist - spring.restLength
        // +ve = separating along the normal.
        val separationSpeed = (motionB.vel - motionA.vel).dot(normal)

        // Desired closing speed this tick: pull in proportional to the stretch, plus damp
        // out the current separation velocity.
        val rawClosingSpeed = lengthError * spring.stiffness + separationSpeed * spring.damping
        // Under-relax by connectivity so a clustered body's many springs don't sum to an
        // unstable over-correction (Jacobi stability). Lone springs: ÷1.
        val relaxation = maxOf(countA, otherCount, 1)
        val closingSpeed = rawClosingSpeed / relaxation

        val totalMass = massA + massB
        if (totalMass <= 0L) return null
        // Lighter body moves more: A's share is weighted by B's mass.
        val weightA = Frac(massB, totalMass.toInt())
        val weightB = Frac(massA, totalMass.toInt())

        // A accelerates toward B (+normal); B toward A (-normal).
        return PairImpulse(
            aImpulse = normal * (closingSpeed * weightA),
            bImpulse = -(normal * (closingSpeed * weightB)),
        )
    }

    companion object {
        /** Below this entity count, fork/join dispatch overhead outweighs the parallel win. */
        private const val PARALLEL_THRESHOLD = 256
    }
}
