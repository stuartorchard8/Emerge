package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Drives autonomous drocket entities through a walk → charge → thrust → fly → land cycle.
 *
 * Godot reference values (mapped to fixed-point equivalents):
 * - Walk duration: 2–10 seconds (120–600 ticks at 60 tps)
 * - Charge spin-up: 0.3 seconds (18 ticks)
 * - Fuel: 1.4 seconds (84 ticks)
 * - Thrust force: 12000 * impulse / delta (mapped to fixed-point impulse per tick)
 */
object DrocketAISystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val drocketStates = LinkedHashMap(state.raw.components.getTable<DrocketStateComponent>().asMap())
        if (drocketStates.isEmpty()) return
        val animationStates = LinkedHashMap(state.raw.components.getTable<SpriteAnimationState>().asMap())

        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        val nextStates = LinkedHashMap<EntityId, DrocketStateComponent>()
        val landings = LinkedHashMap(state.raw.landings.asMap())

        for ((entityId, ds) in drocketStates) {
            val transform = state.raw.transforms[entityId] ?: continue

            when (ds.phase) {
                DrocketPhase.WALKING -> {
                    // Walking is handled by WalkSystem; here we only manage the timer.
                    val remaining = ds.ticksRemaining - 1
                    if (remaining <= 0) {
                        // Transition to CHARGING: spin up before launch
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.CHARGING,
                            ticksRemaining = CHARGE_TICKS,
                        )
                        // Detatch from planet and roll forwards
                        landings.remove(entityId)
                        val spinDir = CHARGE_SPIN_SPEED*ds.walkDirection
                        impulses[entityId] = ImpulseComponent(
                            angVel = spinDir,
                        )
                    } else {
                        nextStates[entityId] = ds.copy(ticksRemaining = remaining)
                    }
                }

                DrocketPhase.CHARGING -> {
                    val remaining = ds.ticksRemaining - 1
                    if (remaining <= 0) {
                        // Start thrusting
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.THRUSTING,
                            fuel = FUEL_TICKS,
                            ticksRemaining = 0,
                        )
                    } else {
                        nextStates[entityId] = ds.copy(ticksRemaining = remaining)
                    }
                }

                DrocketPhase.THRUSTING -> {
                    val fuelLeft = ds.fuel - 1
                    // Apply thrust in the entity's forward direction
                    var forward = Norm.fromAngle(transform.ang).cw90
                    if (ds.walkDirection < 0) {
                        forward = -forward
                    }
                    val thrustImpulse = forward * THRUST_STRENGTH
                    impulses[entityId] = ImpulseComponent(vel = thrustImpulse)

                    if (fuelLeft <= 0) {
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.FLYING,
                            fuel = 0,
                        )
                    } else {
                        nextStates[entityId] = ds.copy(fuel = fuelLeft)
                    }
                }

                DrocketPhase.FLYING -> {
                    // Check if the entity has landed (collision system attached it)
                    val landing = state.raw.landings[entityId]
                    if (landing != null) {
                        // Determine new walk direction (reverse from previous)
                        val newDirection = -ds.walkDirection
                        val walkTicks = MIN_WALK_TICKS + state.nextRandomInt(until = MAX_WALK_TICKS - MIN_WALK_TICKS)
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.WALKING,
                            planetId = landing.parentEntityId,
                            walkDirection = newDirection,
                            ticksRemaining = walkTicks,
                        )
                    } else {
                        nextStates[entityId] = ds
                    }
                }
            }
        }

        for ((entityId, newState) in nextStates) {
            val oldState = drocketStates[entityId]
            drocketStates[entityId] = newState
            if (oldState == null || oldState.phase != newState.phase) {
                val animIndex = when (newState.phase) {
                    DrocketPhase.WALKING -> ANIM_WALK
                    DrocketPhase.CHARGING -> ANIM_IDLE
                    DrocketPhase.THRUSTING -> ANIM_FIRE
                    DrocketPhase.FLYING -> ANIM_IDLE
                }
                SpriteAnimationSystem.setAnimation(
                    animationStates, entityId, animIndex,
                )
            }
        }

        SpriteAnimationSystem.tick(animationStates, DROCKET_SPRITE_SHEET)

        if (impulses.isNotEmpty()) {
            state.addImpulses(impulses)
        }
        state.setLandings(ComponentTable.fromMap(landings))
        state.setComponents(
            state.raw.components.update {
                set(ComponentTable.fromMap(drocketStates))
                set(ComponentTable.fromMap(animationStates))
            }
        )
    }

    private const val CHARGE_TICKS = 18
    // 84 ticks ≈ 1.4 seconds at 60 tps
    private const val FUEL_TICKS = 84
    // Walk 2–10 seconds → 120–600 ticks
    private const val MIN_WALK_TICKS = 120
    private const val MAX_WALK_TICKS = 600
    private val CHARGE_SPIN_SPEED = Frac(1,32)
    // Thrust tuned relative to the engine's gravity to achieve orbital velocity
    private val THRUST_STRENGTH = Frac(1, 1024 * 32)
}
