package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId

/**
 * Side-channel storage for drocket-specific state that doesn't fit into
 * the engine's fixed PhysicsSnapshot component tables.
 *
 * This is mutated in-place by the DrocketAISystem and WalkSystem each tick.
 * Since Drockets is single-player with no networking, deterministic
 * serialization of this state is not required.
 */
object DrocketsRegistry {
    val drocketStates: MutableMap<EntityId, DrocketStateComponent> = LinkedHashMap()
    val animationStates: MutableMap<EntityId, SpriteAnimationState> = LinkedHashMap()

    fun clear() {
        drocketStates.clear()
        animationStates.clear()
    }
}
