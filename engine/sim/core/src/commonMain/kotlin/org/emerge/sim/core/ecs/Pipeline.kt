package org.emerge.sim.core.ecs

import org.emerge.sim.core.PlayerId

/**
 * How systems within a [Phase] share state with each other.
 *
 *  - [Sequential]: systems run one after another on the shared builder. Writes by
 *    earlier systems are visible to later ones. This is the classical ECS tick and
 *    matches pre-refactor behaviour exactly.
 *
 *  - [Isolated]: each system runs on its own fork of the builder (see [fork]),
 *    seeing a snapshot of the phase's starting state plus that system's own writes
 *    only — never writes made by other systems in the same phase. At the phase
 *    barrier the forks' write-logs are replayed on the parent in system-registration
 *    order. This is the execution model a parallel dispatcher will use: isolated
 *    reads make each system safe to run on its own thread; ordered replay keeps
 *    the merged result deterministic.
 *
 *    Today [Isolated] still runs its forks sequentially on the calling thread. The
 *    point of using it before adding threads is to (a) validate that a phase's
 *    systems really are fork-safe — i.e. don't silently depend on another system's
 *    intra-phase writes — and (b) fix the semantics so the later thread-pool
 *    executor is a drop-in replacement.
 */
enum class PhaseConcurrency { Sequential, Isolated }

/**
 * Named group of systems executed between pipeline barriers.
 *
 * See [PhaseConcurrency] for how [concurrency] changes the read/write semantics
 * systems see within the phase. Phase boundaries are always synchronisation points:
 * everything a phase writes is visible to every subsequent phase.
 *
 * A [Phase] does not currently carry read/write manifests. Those will be added
 * alongside thread dispatch, and enforced as a precondition for flagging a phase as
 * [PhaseConcurrency.Isolated] in a stricter mode than today's voluntary contract.
 */
data class Phase<C, S, I>(
    val name: String,
    val systems: List<EcsSystem<C, S, I>>,
    val concurrency: PhaseConcurrency = PhaseConcurrency.Sequential,
) {
    constructor(name: String, vararg systems: EcsSystem<C, S, I>) : this(name, systems.toList())
}

/**
 * Marks [this] phase as [PhaseConcurrency.Isolated]. Each system inside will run on
 * its own fork, with writes replayed on the parent in registration order at the phase
 * barrier.
 */
fun <C, S, I> Phase<C, S, I>.isolated(): Phase<C, S, I> =
    copy(concurrency = PhaseConcurrency.Isolated)

/**
 * Ordered list of [Phase]s. Phases run sequentially, with a synchronisation barrier
 * between each. See [Phase] for what phase membership means.
 */
typealias Pipeline<C, S, I> = List<Phase<C, S, I>>

/**
 * Runs every phase of [pipeline] in registration order. Within each phase, the
 * [Phase.concurrency] mode decides whether systems share the builder ([PhaseConcurrency.Sequential])
 * or each run on their own fork with writes merged at the barrier ([PhaseConcurrency.Isolated]).
 *
 * Today this is single-threaded regardless of concurrency mode; isolated phases still
 * dispatch forks sequentially. A future `runParallel` will keep the same pipeline but
 * dispatch isolated phases across worker threads, producing bit-identical output to
 * this reference implementation.
 */
fun <C, S, I> runSequential(
    cfg: C,
    builder: EcsBuilder<S>,
    inputs: Map<PlayerId, I>,
    pipeline: Pipeline<C, S, I>,
) {
    for (phase in pipeline) {
        when (phase.concurrency) {
            PhaseConcurrency.Sequential -> runPhaseSequential(cfg, builder, inputs, phase)
            PhaseConcurrency.Isolated -> runPhaseIsolated(cfg, builder, inputs, phase)
        }
    }
}

private fun <C, S, I> runPhaseSequential(
    cfg: C,
    builder: EcsBuilder<S>,
    inputs: Map<PlayerId, I>,
    phase: Phase<C, S, I>,
) {
    for (system in phase.systems) {
        system.update(cfg, builder, inputs)
    }
}

private fun <C, S, I> runPhaseIsolated(
    cfg: C,
    builder: EcsBuilder<S>,
    inputs: Map<PlayerId, I>,
    phase: Phase<C, S, I>,
) {
    // Materialise the parent's current state once, up front — every fork in the phase
    // shares this as its frozen read view. A future parallel dispatcher can build this
    // once and hand the same reference to each worker thread.
    val forkInitial = builder.build()
    val forks = ArrayList<EcsBuilder<S>>(phase.systems.size)
    for (system in phase.systems) {
        val fork = builder.forkFrom(forkInitial)
        system.update(cfg, fork, inputs)
        forks += fork
    }
    for (fork in forks) {
        builder.mergeFork(fork)
    }
}
