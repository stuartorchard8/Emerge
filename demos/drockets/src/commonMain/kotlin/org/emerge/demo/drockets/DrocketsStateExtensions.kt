package org.emerge.demo.drockets

import org.emerge.sim.core.ecs.BypassesStagedView
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Typed-getter extensions used by Drockets's demo-side rendering and pick code. Engine
 * [SimState] dropped these convenience getters in Move 5; demos define their own
 * over whatever component types they actually read.
 */
internal val SimState.transforms get() = components.getTable<TransformComponent>()
internal val SimState.colliders get() = components.getTable<ColliderComponent>()
internal val SimState.renderShapes get() = components.getTable<RenderShapeComponent>()
internal val SimState.particles get() = components.getTable<ParticleComponent>()
internal val SimState.atmosphereSources get() = components.getTable<AtmosphereSourceComponent>()
internal val SimState.forceFields get() = components.getTable<ForceFieldComponent>()

/**
 * Deterministic in-sim clock. Drockets timing (maturity, gestation) is expressed in
 * milliseconds at the engine's 60-ticks-per-second rate; [SimState.tick] is the synced,
 * lockstep-safe replacement for wall-clock time. `tick * 1000 / 60` lands exactly on 10_000 ms
 * at tick 600, so the 10 s defaults stay meaningful. (Render code may call this with the live
 * tick to display maturity; sim systems read the start-of-tick clock via [SimBuilder.nowMs].)
 */
internal fun nowMsForTick(tick: Long): Long = tick * 1000L / 60L

/** The start-of-tick deterministic clock in ms, for sim systems during [reduce]. */
@OptIn(BypassesStagedView::class)
internal val SimBuilder.nowMs: Long get() = nowMsForTick(initial.tick)
