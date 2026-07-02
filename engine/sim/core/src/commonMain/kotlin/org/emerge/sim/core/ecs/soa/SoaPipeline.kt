package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.PipelineProfiler
import kotlin.time.TimeSource

private inline fun PipelineProfiler?.timePhase(name: String, block: () -> Unit) {
    if (this == null) {
        block()
        return
    }
    val start = TimeSource.Monotonic.markNow()
    block()
    recordPhase(name, start.elapsedNow().inWholeNanoseconds)
}

/**
 * How systems within a [SoaPhase] share state.
 *
 *  - [Sequential]: systems run one-after-another on the shared mutable world.
 *    Writes by earlier systems are visible to later ones. This is the hot path — allocation-free,
 *    cache-friendly, and deterministic by construction. All hot systems (physics, biology, etc.)
 *    should run in this mode.
 *
 *  - [Isolated]: materialise the world to `SimState`, run each system on a forked snapshot,
 *    then merge back at the phase barrier. Not yet implemented.
 */
enum class SoaPhaseKind {
    /** Hot path: systems share the mutable world. */
    Sequential,
    /** Cold path: each system gets a forked SimState snapshot. */
    Isolated,
}

/**
 * Named group of SOA systems executed between pipeline barriers.
 *
 * See [SoaPhaseKind] for how [kind] changes the execution model. Phase boundaries are always
 * synchronisation points — everything a phase writes is visible to every subsequent phase.
 *
 * A typical pipeline has one or more sequential phases for hot systems (physics, AI, biology)
 * and (in the future) isolated phases for cold systems that need entity lifecycle.
 *
 * @param C configuration type (e.g. game tuning parameters)
 * @param W world type — typically a domain wrapper around [SoaWorld] (e.g. [CytoWorld])
 */
class SoaPhase<C, W>(
    val name: String,
    val systems: List<SoaSystem<C, W>>,
    val kind: SoaPhaseKind = SoaPhaseKind.Sequential,
) {
    constructor(name: String, vararg systems: SoaSystem<C, W>) :
        this(name, systems.toList())

    fun copy(
        name: String = this.name,
        systems: List<SoaSystem<C, W>> = this.systems,
        kind: SoaPhaseKind = this.kind,
    ): SoaPhase<C, W> = SoaPhase(name, systems, kind)
}

/**
 * Marks [this] phase as [SoaPhaseKind.Isolated]. Systems run on forked `SimState` snapshots
 * and merge back at the phase barrier. Not yet implemented.
 */
fun <C, W> SoaPhase<C, W>.isolated(): SoaPhase<C, W> =
    copy(kind = SoaPhaseKind.Isolated)

/**
 * Ordered list of [SoaPhase]s. Phases run sequentially, with a synchronisation barrier
 * between each.
 *
 * @param C configuration type
 * @param W world type
 */
typealias SoaPipeline<C, W> = List<SoaPhase<C, W>>

/**
 * Runs every phase of [pipeline] in registration order on the calling thread.
 * Systems run one-after-another on the shared mutable world — this is the hot path.
 *
 * Pass a [profiler] to accumulate per-phase wall-time samples.
 */
fun <C, W> runSoa(
    cfg: C,
    world: W,
    inputs: Map<PlayerId, *>,
    pipeline: SoaPipeline<C, W>,
    profiler: PipelineProfiler? = null,
) {
    for (phase in pipeline) {
        profiler.timePhase(phase.name) {
            runPhaseSequential(cfg, world, inputs, phase)
        }
    }
}

/**
 * Like [runSoa], but dispatches the systems of each [SoaPhaseKind.Isolated] phase across
 * [executor]. [SoaPhaseKind.Sequential] phases always run on the calling thread because they
 * depend on intra-phase write visibility.
 *
 * Caller owns [executor] and is responsible for its [ParallelExecutor.close].
 */
fun <C, W> runSoaParallel(
    cfg: C,
    world: W,
    inputs: Map<PlayerId, *>,
    pipeline: SoaPipeline<C, W>,
    executor: org.emerge.sim.core.ecs.ParallelExecutor,
    profiler: PipelineProfiler? = null,
) {
    for (phase in pipeline) {
        profiler.timePhase(phase.name) {
            when (phase.kind) {
                SoaPhaseKind.Sequential -> runPhaseSequential(cfg, world, inputs, phase)
                SoaPhaseKind.Isolated -> error(
                    "Isolated phases not yet implemented. Use Sequential phases with SoaSystem for hot paths. " +
                    "Isolated phases (cold systems with EcsSystem) will be added in a future iteration.",
                )
            }
        }
    }
}

private fun <C, W> runPhaseSequential(
    cfg: C,
    world: W,
    inputs: Map<PlayerId, *>,
    phase: SoaPhase<C, W>,
) {
    for (system in phase.systems) {
        system.update(cfg, world, inputs)
    }
}
