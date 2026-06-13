package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.max

/**
 * Keeps connection springs in sync with the cells they join: refreshes each spring's rest
 * length to the (now possibly grown) touching distance rA+rB, accumulates stretch-stress
 * damage and breaks over-stressed connections (porting `Cell.update`'s damage loop), and
 * applies the connected-cell velocity drag ("shielding") from the original.
 *
 * Runs after biology (radii are final) and before the spring solver (so it uses fresh
 * rest lengths).
 *
 * **Parallelism.** With [executor] present and cell count `>= [PARALLEL_THRESHOLD]`, the
 * per-cell work is sliced into contiguous chunks. A worker computes, for each cell in its
 * slice, both the rebuild decision (step 1) and the drag impulse (step 3) — neither reads
 * another cell's *written* result, only the start-of-phase caches — into its own disjoint
 * output slots plus a thread-local break set. The main thread then merges the break sets
 * and applies every builder write (step 1 component writes, step 2 far-side break removal,
 * step 3 impulses) sequentially, so the result is identical to the single-threaded pass.
 * Workers never touch the builder.
 */
class CytoConnectionMaintenanceSystem(
    private val executor: ParallelExecutor? = null,
) : EcsSystem<CytoConfig, SimState, CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        val springs = builder.entries<SpringConstraintComponent>()
        if (springs.isEmpty()) return
        val n = springs.size
        // A grabbed cell's connections must not accrue stress damage: the lag-stretch from dragging is
        // player manipulation, not organic stress, and would otherwise break the organism apart under a
        // soft drag. Its springs heal instead while held.
        val grabbed = inputs.values.firstOrNull()?.grab?.entity

        // Cache each spring-bearing cell's components into flat arrays under a dense index,
        // so the per-spring inner loops read transform/radius/motion/damage/springs by array
        // index instead of hitting the component tables (same approach as the spring solver
        // and ContactSystem). Springs are symmetric in cyto, so every neighbour is in the
        // index; a neighbour that isn't (a removed cell) is treated as gone — exactly what
        // the prior null-component check did. The reads are valid for the whole phase:
        // biology (the only prior writer of radius) has already run, and nothing writes
        // transform/motion until integration in a later phase.
        val ids = arrayOfNulls<EntityId>(n)
        val index = HashMap<EntityId, Int>(n)
        val transforms = arrayOfNulls<TransformComponent>(n)
        val radii = arrayOfNulls<Frac>(n)
        val motions = arrayOfNulls<MotionComponent>(n)
        val damages = arrayOfNulls<Map<EntityId, Float>>(n)
        val springLists = arrayOfNulls<List<SpringConstraint>>(n)
        run {
            var i = 0
            for ((id, comp) in springs) {
                ids[i] = id
                index[id] = i
                transforms[i] = builder.getComponent<TransformComponent>(id)
                radii[i] = builder.getComponent<ColliderComponent>(id)?.radius
                motions[i] = builder.getComponent<MotionComponent>(id)
                damages[i] = builder.getComponent<ConnectionStateComponent>(id)?.damage
                springLists[i] = comp.springs
                i++
            }
        }
        val cache = Cache(ids, index, transforms, radii, motions, damages, springLists)

        // Per-cell outputs (disjoint slots, so workers never collide). A non-null component
        // entry means "write this"; the drag impulse is applied with `+ it` onto whatever the
        // contact phase already deposited.
        val newSpringComp = arrayOfNulls<SpringConstraintComponent>(n)
        val newDamageComp = arrayOfNulls<ConnectionStateComponent>(n)
        val dragImpulse = arrayOfNulls<Frac2>(n)
        val drag = -cfg.connectedDrag

        val broken: HashMap<EntityId, MutableSet<EntityId>>
        if (executor != null && n >= PARALLEL_THRESHOLD) {
            val chunkCount = executor.parallelism.coerceAtMost(n).coerceAtLeast(1)
            val step = (n + chunkCount - 1) / chunkCount
            val brokenLocals = arrayOfNulls<HashMap<EntityId, MutableSet<EntityId>>>(chunkCount)
            val tasks = ArrayList<() -> Unit>(chunkCount)
            for (c in 0 until chunkCount) {
                val start = c * step
                val end = ((c + 1) * step).coerceAtMost(n)
                if (start >= end) continue
                tasks += {
                    val local = HashMap<EntityId, MutableSet<EntityId>>()
                    processRange(cache, cfg, drag, grabbed, start, end, newSpringComp, newDamageComp, dragImpulse, local)
                    brokenLocals[c] = local
                }
            }
            executor.invokeAll(tasks)
            broken = HashMap()
            for (c in 0 until chunkCount) {
                val local = brokenLocals[c] ?: continue
                for ((k, v) in local) broken.getOrPut(k) { HashSet() }.addAll(v)
            }
        } else {
            broken = HashMap()
            processRange(cache, cfg, drag, grabbed, 0, n, newSpringComp, newDamageComp, dragImpulse, broken)
        }

        // 1. apply the rebuilt springs/damage for cells that changed.
        for (k in 0 until n) {
            val springComp = newSpringComp[k] ?: continue
            val id = ids[k]!!
            builder.update<SpringConstraintComponent>(id) { springComp }
            builder.update<ConnectionStateComponent>(id) { newDamageComp[k] ?: ConnectionStateComponent(emptyMap()) }
        }

        // 2. apply breaks on the far side too (reads the post-step-1 builder state).
        for ((id, others) in broken) {
            builder.update<SpringConstraintComponent>(id) { cur ->
                SpringConstraintComponent((cur?.springs ?: emptyList()).filter { it.other !in others })
            }
            builder.update<ConnectionStateComponent>(id) { cur ->
                ConnectionStateComponent((cur?.damage ?: emptyMap()).filterKeys { it !in others })
            }
        }

        // 3. apply the connected-cell drag impulse.
        for (k in 0 until n) {
            val imp = dragImpulse[k] ?: continue
            builder.update<ImpulseComponent>(ids[k]!!) { ImpulseComponent(vel = imp) + it }
        }
    }

    /** Bundled per-tick caches, all read-only during the parallel phase. */
    private class Cache(
        val ids: Array<EntityId?>,
        val index: HashMap<EntityId, Int>,
        val transforms: Array<TransformComponent?>,
        val radii: Array<Frac?>,
        val motions: Array<MotionComponent?>,
        val damages: Array<Map<EntityId, Float>?>,
        val springLists: Array<List<SpringConstraint>?>,
    )

    /**
     * For each cell in `[aStart, aEnd)`: detect whether its springs/damage changed and, if
     * so, build the replacement components (step 1) and collect any over-stressed breaks;
     * and compute its velocity-shielding drag impulse (step 3). Writes only its own disjoint
     * output slots and the local [broken] map — never the builder — so it is worker-safe.
     */
    private fun processRange(
        c: Cache,
        cfg: CytoConfig,
        drag: Frac,
        grabbed: EntityId?,
        aStart: Int,
        aEnd: Int,
        newSpringComp: Array<SpringConstraintComponent?>,
        newDamageComp: Array<ConnectionStateComponent?>,
        dragImpulse: Array<Frac2?>,
        broken: HashMap<EntityId, MutableSet<EntityId>>,
    ) {
        for (aIdx in aStart until aEnd) {
            val springs = c.springLists[aIdx] ?: continue
            if (springs.isEmpty()) continue
            val transformA = c.transforms[aIdx] ?: continue
            val id = c.ids[aIdx]!!

            // --- step 1: detect change, rebuild if needed --------------------------------
            val radiusA = c.radii[aIdx]
            if (radiusA != null) {
                val damageState = c.damages[aIdx] ?: emptyMap()
                var springsChanged = false
                var damageChanged = false
                for (spring in springs) {
                    val bIdx = c.index[spring.other]
                    val transformB = if (bIdx != null) c.transforms[bIdx] else null
                    val radiusB = if (bIdx != null) c.radii[bIdx] else null
                    if (transformB == null || radiusB == null) { springsChanged = true; continue }
                    val rest = radiusA + radiusB
                    if (rest != spring.restLength ||
                        spring.stiffness != cfg.springStiffness ||
                        spring.damping != cfg.springDamping
                    ) springsChanged = true
                    val dist = (transformB.pos - transformA.pos).len
                    val stretch = CytoUnits.toLogical(dist) - CytoUnits.toLogical(rest)
                    val stress = if (id == grabbed || spring.other == grabbed) -0.25f
                        else max(0f, stretch * cfg.connectionStressScale) - 0.25f
                    val prior = damageState[spring.other] ?: 0f
                    val damage = max(0f, prior + stress)
                    if (damage > cfg.connectionBreakDamage) springsChanged = true
                    else if (damage != prior) damageChanged = true
                }
                if (springsChanged || damageChanged) {
                    val newSprings = ArrayList<SpringConstraint>(springs.size)
                    val newDamage = HashMap<EntityId, Float>()
                    for (spring in springs) {
                        val other = spring.other
                        val bIdx = c.index[other]
                        val transformB = if (bIdx != null) c.transforms[bIdx] else null
                        val radiusB = if (bIdx != null) c.radii[bIdx] else null
                        if (transformB == null || radiusB == null) continue // neighbour gone — drop

                        val rest = radiusA + radiusB
                        val dist = (transformB.pos - transformA.pos).len
                        val stretch = CytoUnits.toLogical(dist) - CytoUnits.toLogical(rest)
                        // Stress only when stretched; relaxed connections heal by 0.25/tick. A grabbed
                        // cell's connections never accrue stress (drag is not organic stress) — they heal.
                        val stress = if (id == grabbed || other == grabbed) -0.25f
                            else max(0f, stretch * cfg.connectionStressScale) - 0.25f
                        val damage = max(0f, (damageState[other] ?: 0f) + stress)

                        if (damage > cfg.connectionBreakDamage) {
                            broken.getOrPut(id) { HashSet() }.add(other)
                            broken.getOrPut(other) { HashSet() }.add(id)
                        } else {
                            newSprings.add(spring.copy(restLength = rest, stiffness = cfg.springStiffness, damping = cfg.springDamping))
                            newDamage[other] = damage
                        }
                    }
                    newSpringComp[aIdx] = SpringConstraintComponent(newSprings)
                    newDamageComp[aIdx] = ConnectionStateComponent(newDamage)
                }
            }

            // --- step 3: connected-cell drag ("velocity shielding") ----------------------
            // Damp the part of the cell's velocity that isn't moving toward a connected
            // neighbour. Uses the start-of-phase springs/positions, independent of step 1.
            val motion = c.motions[aIdx] ?: continue
            var unshielded = motion.vel.asFrac2()
            for (spring in springs) {
                val bIdx = c.index[spring.other] ?: continue
                val transformB = c.transforms[bIdx] ?: continue
                val normal = (transformB.pos - transformA.pos).norm // id -> other
                val towardOther = unshielded.dot(normal)
                if (towardOther.raw > 0L) {
                    unshielded = unshielded - normal * towardOther
                }
            }
            dragImpulse[aIdx] = unshielded * drag
        }
    }

    companion object {
        /** Below this cell count, fork/join dispatch overhead outweighs the parallel win. */
        private const val PARALLEL_THRESHOLD = 256
    }
}
