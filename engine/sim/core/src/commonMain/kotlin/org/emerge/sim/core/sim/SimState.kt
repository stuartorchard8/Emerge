package org.emerge.sim.core.sim

import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.primitives.Contact

/**
 * Engine-side simulation snapshot. Domain-agnostic: contains only the ECS world, component
 * tables, the per-tick contact list, and the deterministic PRNG seed. Game-specific frame
 * state (respawn queues, audio events, player-entity indexes, etc.) lives in demo-side
 * wrapper states.
 */
data class SimState(
    val world: EcsWorld = EcsWorld.EMPTY,
    val components: ComponentStore = ComponentStore(),
    val contacts: List<Contact> = emptyList(),
    /**
     * Deterministic PRNG state carried across ticks.
     * Must be kept in sync across all lockstep peers — never seed from platform Random.
     * Serialized alongside the snapshot for Welcome/Resync.
     */
    val randomSeed: Long = 0,
    /**
     * Deterministic monotonic tick counter — the simulation's own clock, advanced by the
     * reducer once per [reduce][org.emerge.sim.core.SimReducer.reduce]. Demos that need a
     * deterministic in-sim time source read it instead of wall-clock (which would desync
     * lockstep peers). Like [randomSeed], it must be kept in sync across peers and serialized
     * for Welcome/Resync. Default 0; left at 0 by reducers that don't need it.
     */
    val tick: Long = 0,
)
