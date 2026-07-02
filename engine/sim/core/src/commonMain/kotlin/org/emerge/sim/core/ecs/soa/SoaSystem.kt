package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.PlayerId
import kotlin.reflect.KClass

/**
 * A per-tick unit of logic for the SOA pipeline. Hot systems implement this trait and operate
 * directly on raw column field arrays via [SoaBuilder] (which wraps [SoaCompat] for convenience).
 *
 * Cold systems that need entity lifecycle or fork/merge use [EcsSystem] instead — they run
 * inside [SoaPhase.Isolated] phases that materialize the world to [SimState] at the phase barrier.
 *
 * @param C configuration type (e.g. game tuning parameters)
 */
fun interface SoaSystem<in C> {
    fun update(cfg: C, builder: SoaBuilder, inputs: Map<PlayerId, *>)
}
