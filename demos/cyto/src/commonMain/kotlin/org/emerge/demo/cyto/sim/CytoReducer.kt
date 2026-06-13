package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.sim.systems.CytoBiologySystem
import org.emerge.demo.cyto.sim.systems.CytoConnectionMaintenanceSystem
import org.emerge.demo.cyto.sim.systems.CytoContactSystem
import org.emerge.demo.cyto.sim.systems.CytoGrabSystem
import org.emerge.demo.cyto.sim.systems.CytoInteractionSystem
import org.emerge.demo.cyto.sim.systems.CytoLifecycleSystem
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.runSequential
import org.emerge.sim.core.physics.systems.ContactSystem
import org.emerge.sim.core.physics.systems.ImpulseResetSystem
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.physics.systems.SpringConstraintSystem
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Native (Box2D-free) Cyto reducer. Composes engine physics systems (impulse reset,
 * contacts, spring solver, integration) with Cyto's own systems into one deterministic
 * fixed-point pipeline:
 *
 *   interact -> reset -> contacts -> biology -> connections -> forces -> lifecycle -> integrate
 *
 * Order matters: contacts feed touch into biology this tick; biology grows radii before
 * connection maintenance refreshes spring rest lengths; the spring solver runs after; and
 * structural changes (weld/divide/destroy) apply last, taking effect next tick.
 *
 * Connection maintenance and the spring solver — the two heaviest phases at scale — take
 * the optional [executor] and fan their own per-cell loops across worker threads
 * (intra-system data parallelism). Phases stay [runSequential]: the pipeline is a hard
 * dependency chain (contacts→biology→connections→forces), so there's nothing to overlap
 * *between* phases. Contacts stays sequential — cyto's broadphase is cheap, so a parallel
 * split doesn't pay. NB: the parallel speedup needs real CPU headroom; on a power-throttled
 * CPU (laptop on battery) the worker fan-out can be a net loss, and on JS the executor is a
 * no-op (sequential).
 */
class CytoReducer(
    /** Opt-in per-phase timing. Null in production (zero overhead); set by benchmarks. */
    private val profiler: PipelineProfiler? = null,
    /** Worker pool for the parallel contact/spring systems. Null → fully sequential. */
    private val executor: ParallelExecutor? = null,
) : SimReducer<CytoConfig, SimState, CytoInput> {
    private val pipeline: Pipeline<CytoConfig, SimState, CytoInput> = listOf(
        Phase("interact", CytoInteractionSystem),
        Phase("reset", ImpulseResetSystem),
        Phase("contacts", ContactSystem(), CytoContactSystem),
        Phase("biology", CytoBiologySystem),
        Phase("connections", CytoConnectionMaintenanceSystem(executor)),
        // Grab runs before the constraint solver: it deposits the grabbed cell's pull, then the
        // solver reads that impulse and propagates it through the connections in the same tick, so a
        // dragged organism follows as one body (no lag-stretch, no grab special-casing).
        Phase("forces", CytoGrabSystem, SpringConstraintSystem()),
        Phase("lifecycle", CytoLifecycleSystem),
        Phase("integrate", IntegrationSystem),
    )

    override fun reduce(cfg: CytoConfig, state: SimState, inputs: Map<PlayerId, CytoInput>): SimState {
        val builder = SimBuilder(state)
        runSequential(cfg, builder, inputs, pipeline, profiler)
        return builder.build()
    }

    // Single-player, no lockstep patching.
    override fun patchState(state: SimState, delta: SimState): SimState = state
}
