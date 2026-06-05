package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.sim.systems.CytoBiologySystem
import org.emerge.demo.cyto.sim.systems.CytoConnectionMaintenanceSystem
import org.emerge.demo.cyto.sim.systems.CytoContactSystem
import org.emerge.demo.cyto.sim.systems.CytoGrabSystem
import org.emerge.demo.cyto.sim.systems.CytoInteractionSystem
import org.emerge.demo.cyto.sim.systems.CytoLifecycleSystem
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
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
 */
class CytoReducer : SimReducer<CytoConfig, SimState, CytoInput> {
    private val pipeline: Pipeline<CytoConfig, SimState, CytoInput> = listOf(
        Phase("interact", CytoInteractionSystem),
        Phase("reset", ImpulseResetSystem),
        Phase("contacts", ContactSystem(), CytoContactSystem),
        Phase("biology", CytoBiologySystem),
        Phase("connections", CytoConnectionMaintenanceSystem),
        Phase("forces", SpringConstraintSystem, CytoGrabSystem),
        Phase("lifecycle", CytoLifecycleSystem),
        Phase("integrate", IntegrationSystem),
    )

    override fun reduce(cfg: CytoConfig, state: SimState, inputs: Map<PlayerId, CytoInput>): SimState {
        val builder = SimBuilder(state)
        runSequential(cfg, builder, inputs, pipeline)
        return builder.build()
    }

    // Single-player, no lockstep patching.
    override fun patchState(state: SimState, delta: SimState): SimState = state
}
