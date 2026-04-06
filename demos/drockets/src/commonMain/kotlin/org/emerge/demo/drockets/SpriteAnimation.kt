package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId

/**
 * Defines a sprite animation as a sequence of frames in a texture atlas grid.
 *
 * @param name Human-readable name for debugging
 * @param startFrame Index of the first frame in the atlas (left-to-right, top-to-bottom)
 * @param frameCount Number of frames in this animation
 * @param ticksPerFrame How many simulation ticks each frame is displayed
 * @param loop Whether the animation loops or stops on the last frame
 */
data class SpriteAnimationDef(
    val name: String,
    val startFrame: Int,
    val frameCount: Int,
    val ticksPerFrame: Int,
    val loop: Boolean = true,
)

/**
 * Per-entity animation state tracked alongside the simulation.
 */
data class SpriteAnimationState(
    val animationIndex: Int = 0,
    val currentFrame: Int = 0,
    val tickCounter: Int = 0,
)

/**
 * Defines a sprite sheet: a grid of equally-sized frames.
 *
 * @param columnsPerRow Number of frames per row in the atlas
 * @param totalRows Number of rows in the atlas
 * @param animations Available animations defined on this sheet
 */
data class SpriteSheet(
    val columnsPerRow: Int,
    val totalRows: Int,
    val animations: List<SpriteAnimationDef>,
) {
    val frameSizeU: Float = 1f / columnsPerRow
    val frameSizeV: Float = 1f / totalRows

    fun frameUV(frameIndex: Int): Pair<Float, Float> {
        val col = frameIndex % columnsPerRow
        val row = frameIndex / columnsPerRow
        return Pair(col * frameSizeU, row * frameSizeV)
    }
}

/**
 * Advances animation state by one tick, cycling frames according to the
 * active animation definition.
 */
object SpriteAnimationSystem {
    fun tick(
        animStates: MutableMap<EntityId, SpriteAnimationState>,
        sheet: SpriteSheet,
    ) {
        for ((entityId, state) in animStates) {
            val anim = sheet.animations.getOrNull(state.animationIndex) ?: continue
            val nextTick = state.tickCounter + 1
            if (nextTick >= anim.ticksPerFrame) {
                val nextFrame = state.currentFrame + 1
                if (nextFrame >= anim.frameCount) {
                    animStates[entityId] = if (anim.loop) {
                        state.copy(currentFrame = 0, tickCounter = 0)
                    } else {
                        state.copy(currentFrame = anim.frameCount - 1, tickCounter = 0)
                    }
                } else {
                    animStates[entityId] = state.copy(currentFrame = nextFrame, tickCounter = 0)
                }
            } else {
                animStates[entityId] = state.copy(tickCounter = nextTick)
            }
        }
    }

    fun setAnimation(
        animStates: MutableMap<EntityId, SpriteAnimationState>,
        entityId: EntityId,
        animationIndex: Int,
    ) {
        val current = animStates[entityId]
        if (current == null || current.animationIndex != animationIndex) {
            animStates[entityId] = SpriteAnimationState(
                animationIndex = animationIndex,
                currentFrame = 0,
                tickCounter = 0,
            )
        }
    }

    fun currentAtlasFrame(
        state: SpriteAnimationState,
        sheet: SpriteSheet,
    ): Int {
        val anim = sheet.animations.getOrNull(state.animationIndex) ?: return 0
        return anim.startFrame + state.currentFrame
    }
}

/**
 * Drocket sprite sheet definition matching the 3-frame atlas packed from the Godot PNGs:
 * Column 0 = idle (drocket.png)
 * Column 1 = walk (drocket_walk.png)
 * Column 2 = fire (drocket_fire.png)
 *
 * Animations cycle between these atlas frames to reproduce the Godot SpriteFrames behavior:
 * - idle: frame 0
 * - walk: frames 1,0 (walk pose then idle pose), looping at 4 fps → 15 ticks/frame
 * - fire: frame 2
 * - rawr: frames 0,2,0 (idle, fire, idle), looping at 4 fps → 15 ticks/frame
 */
val DROCKET_SPRITE_SHEET = SpriteSheet(
    columnsPerRow = 3,
    totalRows = 1,
    animations = listOf(
        SpriteAnimationDef("idle", startFrame = 0, frameCount = 1, ticksPerFrame = 1),
        SpriteAnimationDef("walk", startFrame = 1, frameCount = 2, ticksPerFrame = 15, loop = true),
        SpriteAnimationDef("fire", startFrame = 2, frameCount = 1, ticksPerFrame = 1),
        SpriteAnimationDef("rawr", startFrame = 0, frameCount = 3, ticksPerFrame = 15, loop = true),
    ),
)

const val ANIM_IDLE = 0
const val ANIM_WALK = 1
const val ANIM_FIRE = 2
const val ANIM_RAWR = 3
