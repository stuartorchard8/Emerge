package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.PlayerId
import kotlin.reflect.KClass

/**
 * A per-tick unit of logic for the SOA pipeline. Systems implement this trait and operate
 * directly on a domain-specific world type (e.g. `CytoWorld`) — the world type [W] is generic
 * so the pipeline is domain-agnostic. Systems run sequentially on the shared mutable world
 * (see [SoaPhase]).
 *
 * @param C configuration type (e.g. game tuning parameters)
 * @param W world type — typically a domain wrapper around [SoaWorld] (e.g. `CytoWorld`)
 */
fun interface SoaSystem<in C, in W> {
    fun update(cfg: C, world: W, inputs: Map<PlayerId, *>)
}
