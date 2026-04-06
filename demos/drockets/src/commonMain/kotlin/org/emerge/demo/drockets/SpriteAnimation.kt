package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId

/**
 * Defines a sprite animation as a sequence of frames in a texture atlas grid.
 *
 * @param name Human-readable name for debugging
 * @param frames Indexes of the frames in the atlas (left-to-right, top-to-bottom)
 * @param ticksPerFrame How many simulation ticks each frame is displayed
 * @param loop Whether the animation loops or stops on the last frame
 */
data class SpriteAnimationDef(
    val name: String,
    val frames: Array<Int>,
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
        return anim.frames[state.currentFrame]
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
 */
val DROCKET_SPRITE_SHEET = SpriteSheet(
    columnsPerRow = 3,
    totalRows = 1,
    animations = listOf(
        SpriteAnimationDef("idle", frames = arrayOf(ANIM_IDLE), ticksPerFrame = 1),
        SpriteAnimationDef("walk", frames = arrayOf(ANIM_WALK, ANIM_IDLE), ticksPerFrame = 15, loop = true),
        SpriteAnimationDef("fire", frames = arrayOf(ANIM_FIRE), ticksPerFrame = 1),
    ),
)

const val ANIM_IDLE = 0
const val ANIM_WALK = 1
const val ANIM_FIRE = 2
