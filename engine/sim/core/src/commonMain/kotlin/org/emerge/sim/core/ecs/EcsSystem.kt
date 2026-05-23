package org.emerge.sim.core.ecs

import org.emerge.sim.core.PlayerId

/**
 * A per-tick unit of logic that reads and writes component tables via an [EcsBuilder].
 *
 * Systems are domain-agnostic: the state type [S] and config [C] are supplied by whoever
 * assembles the pipeline. A typical physics-domain system declares itself as
 * `EcsSystem<PhysicsTuning, PhysicsState, PhysicsInput>`, receiving an
 * `EcsBuilder<PhysicsState>` (a.k.a. `PhysicsBuilder`) on each update.
 *
 * [C] and [I] are declared `in` (contravariant): a system that only needs to read
 * the engine-side [org.emerge.sim.core.physics.model.PhysicsTuning] contract can be
 * dropped into a pipeline whose concrete config is a demo subtype.
 *
 * ### Parallel-safety contract (forward-looking)
 *
 * The current [Pipeline] executor runs all systems sequentially on a single thread, so
 * systems can freely use the staged view ([EcsBuilder.entries] / [EcsBuilder.getComponent])
 * without worrying about data races. A future parallel executor will run systems within
 * the same [Phase] concurrently; at that point systems in a parallelised phase must obey:
 *
 *  - reads from `builder.initial` only (the frozen start-of-frame view — the
 *    [BypassesStagedView] opt-in announces this explicitly);
 *  - writes to disjoint component types, OR writes to additive/commutative buffers that
 *    merge deterministically at the phase barrier;
 *  - entity creation/removal deferred through a command buffer rather than mutating the
 *    shared [EcsWorld] mid-phase;
 *  - deterministic PRNG sub-streams rather than the shared `nextRandomInt()` counter.
 *
 * Until that infrastructure exists, systems can be written in the current "staged read,
 * direct write" style; the phase grouping simply documents which systems will eventually
 * be candidates for parallel execution.
 */
fun interface EcsSystem<in C, S, in I> {
    fun update(cfg: C, builder: EcsBuilder<S>, inputs: Map<PlayerId, I>)
}
