package org.emerge.sim.core.ecs

import org.emerge.sim.core.PlayerId

/**
 * Named group of systems executed between pipeline barriers.
 *
 * In the current sequential executor, phase membership is **descriptive only** — every
 * system in every phase runs in order on a single thread, and systems may use any
 * [EcsBuilder] API they like. The grouping exists to:
 *
 *  1. Document the dependency structure of a pipeline (which systems share state, which
 *     could in principle run independently).
 *  2. Give us a place to attach a parallel executor later without changing system
 *     implementations. When that happens, systems inside a phase marked parallel-safe
 *     will dispatch concurrently; phase barriers become the synchronisation points.
 *
 * A [Phase] does not currently carry read/write manifests. Those will be added alongside
 * the parallel executor, and enforced as a precondition for flagging a phase as
 * parallel-safe.
 */
data class Phase<C, S, I>(
    val name: String,
    val systems: List<EcsSystem<C, S, I>>,
) {
    constructor(name: String, vararg systems: EcsSystem<C, S, I>) : this(name, systems.toList())
}

/**
 * Ordered list of [Phase]s. Phases run sequentially, with a synchronisation barrier
 * between each. See [Phase] for what phase membership means.
 */
typealias Pipeline<C, S, I> = List<Phase<C, S, I>>

/**
 * Runs every phase of [pipeline] in registration order, and every system within each
 * phase in registration order. Single-threaded.
 *
 * A future `runParallel` will dispatch independent systems within a phase across worker
 * threads, producing bit-identical output to this sequential reference (validated by
 * test). Call sites can switch between executors without changing their pipelines.
 */
fun <C, S, I> runSequential(
    cfg: C,
    builder: EcsBuilder<S>,
    inputs: Map<PlayerId, I>,
    pipeline: Pipeline<C, S, I>,
) {
    for (phase in pipeline) {
        for (system in phase.systems) {
            system.update(cfg, builder, inputs)
        }
    }
}
