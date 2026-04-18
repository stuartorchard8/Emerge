package org.emerge.sim.core.ecs

import kotlin.time.TimeSource
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
 * Runs every phase of [pipeline] in registration order on the calling thread.
 * Within each phase, the [Phase.concurrency] mode decides whether systems share the
 * builder ([PhaseConcurrency.Sequential]) or each run on their own fork with writes
 * merged at the barrier ([PhaseConcurrency.Isolated]).
 *
 * Single-threaded regardless of concurrency mode — isolated phases dispatch their
 * forks sequentially. Use this as the reference implementation. [runParallel] produces
 * bit-identical output while dispatching isolated phases across worker threads.
 *
 * Pass a [profiler] to accumulate per-phase wall-time samples.
 */
fun <C, S, I> runSequential(
    cfg: C,
    builder: EcsBuilder<S>,
    inputs: Map<PlayerId, I>,
    pipeline: Pipeline<C, S, I>,
    profiler: PipelineProfiler? = null,
) {
    for (phase in pipeline) {
        profiler.timePhase(phase.name) {
            when (phase.concurrency) {
                PhaseConcurrency.Sequential -> runPhaseSequential(cfg, builder, inputs, phase)
                PhaseConcurrency.Isolated -> runPhaseIsolatedSequential(cfg, builder, inputs, phase)
            }
        }
    }
}

/**
 * Like [runSequential], but dispatches the systems of each [PhaseConcurrency.Isolated]
 * phase across [executor]. [PhaseConcurrency.Sequential] phases always run on the
 * calling thread because they depend on intra-phase write visibility.
 *
 * Isolated phases remain deterministic:
 *  - Every fork is built from a single frozen snapshot captured *before* dispatch.
 *  - Forks mutate only their own write-log and tombstones; shared-resource access
 *    (entity world, PRNG seed, domain scratch delegated via [EcsBuilder.parent])
 *    is serialised by the per-root-builder lock on [EcsBuilder.rootLock].
 *  - At the phase barrier every fork's write-log is replayed on the parent in
 *    system-registration order, exactly like the sequential runner.
 *
 * That means `runParallel` and [runSequential] produce the same final state given
 * the same inputs, modulo domain-level PRNG ordering inside a phase (draws from
 * different forks interleave under the root lock in the order they hit it; this is
 * by design — see the PRNG kdoc on `PhysicsBuilder.kt`).
 *
 * Caller owns [executor] and is responsible for its [ParallelExecutor.close].
 */
fun <C, S, I> runParallel(
    cfg: C,
    builder: EcsBuilder<S>,
    inputs: Map<PlayerId, I>,
    pipeline: Pipeline<C, S, I>,
    executor: ParallelExecutor,
    profiler: PipelineProfiler? = null,
) {
    for (phase in pipeline) {
        profiler.timePhase(phase.name) {
            when (phase.concurrency) {
                PhaseConcurrency.Sequential -> runPhaseSequential(cfg, builder, inputs, phase)
                PhaseConcurrency.Isolated -> runPhaseIsolatedParallel(cfg, builder, inputs, phase, executor)
            }
        }
    }
}

private inline fun PipelineProfiler?.timePhase(name: String, block: () -> Unit) {
    if (this == null) {
        block()
        return
    }
    val start = TimeSource.Monotonic.markNow()
    block()
    recordPhase(name, start.elapsedNow().inWholeNanoseconds)
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

private fun <C, S, I> runPhaseIsolatedSequential(
    cfg: C,
    builder: EcsBuilder<S>,
    inputs: Map<PlayerId, I>,
    phase: Phase<C, S, I>,
) {
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

private fun <C, S, I> runPhaseIsolatedParallel(
    cfg: C,
    builder: EcsBuilder<S>,
    inputs: Map<PlayerId, I>,
    phase: Phase<C, S, I>,
    executor: ParallelExecutor,
) {
    val systems = phase.systems
    // Degenerate case: one system in an isolated phase is equivalent to sequential
    // (no forks needed, no parallelism possible). Skip the fork/merge dance.
    if (systems.size <= 1) {
        for (system in systems) system.update(cfg, builder, inputs)
        return
    }
    // One frozen view shared by every fork in this phase. Built before dispatch on
    // the calling thread so workers see a stable, read-only snapshot.
    val forkInitial = builder.build()
    val forks = ArrayList<EcsBuilder<S>>(systems.size)
    for (i in systems.indices) forks += builder.forkFrom(forkInitial)
    val tasks = ArrayList<() -> Unit>(systems.size)
    for (i in systems.indices) {
        val system = systems[i]
        val fork = forks[i]
        tasks += { system.update(cfg, fork, inputs) }
    }
    executor.invokeAll(tasks)
    // Merge in registration order so non-commutative replays stay deterministic.
    for (fork in forks) builder.mergeFork(fork)
}
