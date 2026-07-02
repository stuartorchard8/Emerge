package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.PlayerId
import kotlin.reflect.KClass

/**
 * A per-tick unit of logic for the SOA pipeline. Hot systems implement this trait and operate
 * directly on a domain-specific world type (e.g. [CytoWorld]) — the world type [W] is generic
 * so the pipeline is domain-agnostic.
 *
 * Cold systems that need entity lifecycle or fork/merge use [EcsSystem] instead — they run
 * inside [SoaPhase.Isolated] phases that materialize the world to [SimState] at the phase barrier.
 *
 * @param C configuration type (e.g. game tuning parameters)
 * @param W world type — typically a domain wrapper around [SoaWorld] (e.g. [CytoWorld])
 */
fun interface SoaSystem<in C, in W> {
    fun update(cfg: C, world: W, inputs: Map<PlayerId, *>)
}
