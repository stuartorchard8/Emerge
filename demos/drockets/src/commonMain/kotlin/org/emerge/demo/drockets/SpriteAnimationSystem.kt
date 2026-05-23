package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsBuilder

import org.emerge.sim.core.physics.model.PhysicsState


/**
 * Advances animation state by one tick, cycling frames according to the
 * active animation definition.
 */
object SpriteAnimationSystem : EcsSystem<DrocketsConfig, PhysicsState, DrocketsInput> {

    override fun update(
        cfg: DrocketsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, DrocketsInput>,
    ) {
        val animStates = LinkedHashMap(builder.entries<SpriteAnimationState>())
        if (animStates.isEmpty()) return

        for ((entityId, state) in animStates) {
            val anim = state.sheet.animations.getOrNull(state.animationIndex) ?: continue
            val nextTick = state.tickCounter + 1
            if (nextTick >= anim.ticksPerFrame) {
                val nextFrame = state.currentFrame + 1
                if (nextFrame >= anim.frames.size) {
                    animStates[entityId] = if (anim.loop) {
                        state.copy(currentFrame = 0, tickCounter = 0)
                    } else {
                        state.copy(currentFrame = anim.frames.size - 1, tickCounter = 0)
                    }
                } else {
                    animStates[entityId] = state.copy(currentFrame = nextFrame, tickCounter = 0)
                }
            } else {
                animStates[entityId] = state.copy(tickCounter = nextTick)
            }
        }

        builder.setTable<SpriteAnimationState>(animStates)
    }

    fun setAnimation(
        animStates: MutableMap<EntityId, SpriteAnimationState>,
        entityId: EntityId,
        sheet: SpriteSheet,
        animationIndex: Int,
    ) {
        val current = animStates[entityId]
        if (current == null || current.animationIndex != animationIndex) {
            animStates[entityId] = SpriteAnimationState(
                sheet = sheet,
                animationIndex = animationIndex,
                currentFrame = 0,
                tickCounter = 0,
            )
        }
    }

    fun currentAtlasFrame(
        state: SpriteAnimationState,
    ): Int {
        val anim = state.sheet.animations.getOrNull(state.animationIndex) ?: return 0
        return anim.frames[state.currentFrame]
    }
}
