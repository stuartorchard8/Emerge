package org.emerge.demo.drockets

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
    val sheet: SpriteSheet,
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
enum class SpriteSheet(
    val columnsPerRow: Int,
    val totalRows: Int,
    val frameWidths: Array<Int>,
    val frameHeights: Array<Int>,
    val animations: Array<SpriteAnimationDef>,
) {
    DROCKET(
        columnsPerRow = 3,
        totalRows = 1,
        frameWidths = arrayOf(16,16,16),
        frameHeights = arrayOf(16,16,16),
        animations = arrayOf(
            SpriteAnimationDef("idle", frames = arrayOf(0), ticksPerFrame = 1),
            SpriteAnimationDef("idle", frames = arrayOf(0), ticksPerFrame = 1),
            SpriteAnimationDef("walk", frames = arrayOf(1, 0), ticksPerFrame = 15, loop = true),
            SpriteAnimationDef("walk", frames = arrayOf(1, 0), ticksPerFrame = 15, loop = true),
            SpriteAnimationDef("fire", frames = arrayOf(2), ticksPerFrame = 1),
        ),
    ),
    KNIGHT(
        columnsPerRow = 8,
        totalRows = 11,
        frameWidths = Array(8*11) { 32 },
        frameHeights = Array(8*11) { 32 },
        animations = arrayOf(
            SpriteAnimationDef("idle_right", frames = arrayOf(0,1), ticksPerFrame = 24, loop = true),
            SpriteAnimationDef("idle_left", frames = arrayOf(2,3), ticksPerFrame = 24, loop = true),
            SpriteAnimationDef("walk_right", frames = arrayOf(8,9,10,11,12,13,14,15), ticksPerFrame = 8, loop = true),
            SpriteAnimationDef("walk_left", frames = arrayOf(24,25,26,27,28,29,30,31), ticksPerFrame = 8, loop = true),
        ),
    ),
    ;

    val frameSizeU: Float = 1f / columnsPerRow
    val frameSizeV: Float = 1f / totalRows

    fun frameUV(frameIndex: Int): Pair<Float, Float> {
        val col = frameIndex % columnsPerRow
        val row = frameIndex / columnsPerRow
        return Pair(col * frameSizeU, row * frameSizeV)
    }

    fun frameWH(frameIndex: Int): Pair<Float, Float> {
        val w = frameWidths[frameIndex]
        val h = frameHeights[frameIndex]
        return Pair(w/16f, h/16f)
    }
}


const val ANIM_IDLE_RIGHT = 0
const val ANIM_IDLE_LEFT = 1
const val ANIM_WALK_RIGHT = 2
const val ANIM_WALK_LEFT = 3
const val ANIM_FIRE = 4
