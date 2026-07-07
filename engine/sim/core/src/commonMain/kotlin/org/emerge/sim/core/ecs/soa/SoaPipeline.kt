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
 * Named group of SOA systems executed between pipeline barriers.
 *
 * Systems within a phase run one-after-another on the shared mutable world: writes by earlier
 * systems are visible to later ones. This is the hot path — allocation-free, cache-friendly, and
 * deterministic by construction. Phase boundaries are synchronisation points: everything a phase
 * writes is visible to every subsequent phase.
 *
 * @param C configuration type (e.g. game tuning parameters)
 * @param W world type — typically a domain wrapper around [SoaWorld] (e.g. `CytoWorld`)
 */
class SoaPhase<C, W>(
    val name: String,
    val systems: List<SoaSystem<C, W>>,
) {
    constructor(name: String, vararg systems: SoaSystem<C, W>) :
        this(name, systems.toList())
}

/**
 * Ordered list of [SoaPhase]s. Phases run sequentially, with a synchronisation barrier
 * between each.
 *
 * @param C configuration type
 * @param W world type
 */
typealias SoaPipeline<C, W> = List<SoaPhase<C, W>>

/**
 * Runs every phase of [pipeline] in registration order on the calling thread. Systems run
 * one-after-another on the shared mutable world — the hot path.
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
            for (system in phase.systems) {
                system.update(cfg, world, inputs)
            }
        }
    }
}
