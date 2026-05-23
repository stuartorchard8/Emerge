package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder

import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.nextRandomInt
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Drives autonomous drocket entities through a walk → charge → thrust → fly → land cycle.
 *
 * Godot reference values (mapped to fixed-point equivalents):
 * - Walk duration: 2–10 seconds (120–600 ticks at 60 tps)
 * - Pause: 1.4 seconds (84 ticks)
 */
object KnightAISystem : EcsSystem<DrocketsConfig, PhysicsState, PhysicsInput> {

    override fun update(
        cfg: DrocketsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val states = LinkedHashMap(builder.entries<KnightStateComponent>())
        if (states.isEmpty()) return
        val animationStates = LinkedHashMap(builder.entries<SpriteAnimationState>())

        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        val nextStates = LinkedHashMap<EntityId, KnightStateComponent>()
        val landings = LinkedHashMap(builder.entries<LandingAttachmentComponent>())

        for ((entityId, state) in states) {
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue

            when (state.phase) {
                KnightPhase.WALKING -> {
                    // Walking is handled by WalkSystem; here we only manage the timer.
                    val remaining = state.ticksRemaining - 1
                    if (remaining <= 0) {
                        // Transition to IDLE
                        nextStates[entityId] = state.copy(
                            phase = KnightPhase.IDLE,
                            ticksRemaining = IDLE_TICKS,
                        )
                    } else {
                        nextStates[entityId] = state.copy(ticksRemaining = remaining)
                    }
                }

                KnightPhase.IDLE -> {
                    val remaining = state.ticksRemaining - 1
                    if (remaining <= 0) {
                        // Transition to WALKING
                        val newDirection = -state.walkDirection
                        val walkTicks = MIN_WALK_TICKS + builder.nextRandomInt(until = MAX_WALK_TICKS - MIN_WALK_TICKS)
                        nextStates[entityId] = state.copy(
                            phase = KnightPhase.WALKING,
                            ticksRemaining = walkTicks,
                            walkDirection = newDirection,
                        )
                    } else {
                        nextStates[entityId] = state.copy(ticksRemaining = remaining)
                    }
                }
            }
        }

        for ((entityId, newState) in nextStates) {
            val oldState = states[entityId]
            states[entityId] = newState
            if (oldState == null || oldState.phase != newState.phase) {
                val animIndex = when (newState.phase) {
                    KnightPhase.WALKING -> if (newState.walkDirection == 1) ANIM_WALK_RIGHT else ANIM_WALK_LEFT
                    KnightPhase.IDLE -> if (newState.walkDirection == 1) ANIM_IDLE_RIGHT else ANIM_IDLE_LEFT
                }
                SpriteAnimationSystem.setAnimation(
                    animStates = animationStates,
                    entityId = entityId,
                    sheet = SpriteSheet.KNIGHT,
                    animationIndex = animIndex,
                )
            }
        }

        for ((entityId, impulse) in impulses) {
            builder.update<ImpulseComponent>(entityId) { impulse + it }
        }
        builder.setTable<LandingAttachmentComponent>(landings)
        builder.setTable<KnightStateComponent>(states)
        builder.setTable<SpriteAnimationState>(animationStates)
    }

    // 180 ticks ≈ 3 seconds at 60 tps
    private const val IDLE_TICKS = 180
    // Walk 2–10 seconds → 120–600 ticks
    private const val MIN_WALK_TICKS = 120
    private const val MAX_WALK_TICKS = 600
}
