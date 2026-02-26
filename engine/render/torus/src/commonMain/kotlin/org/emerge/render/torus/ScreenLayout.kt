package org.emerge.render.torus

import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i

data class ScreenLayout(
    val worldMinX: Float,
    val worldMaxX: Float,
    val worldMinY: Float,
    val worldMaxY: Float,
    val guiMinX: Float,
    val guiMaxX: Float,
    val guiMinY: Float,
    val guiMaxY: Float,
    val resolution: Vec2i,
    val aspectRatio: Float,
) {
    fun getWorldVerts(): FloatArray = floatArrayOf(
        worldMinX, worldMinY,
        worldMaxX, worldMinY,
        worldMinX, worldMaxY,
        worldMaxX, worldMaxY,
    )
    fun getGuiVerts(): FloatArray = floatArrayOf(
        guiMinX, guiMinY,
        guiMaxX, guiMinY,
        guiMinX, guiMaxY,
        guiMaxX, guiMaxY,
    )
    fun getWorldCenter(): Vec2 = Vec2(
        (worldMinX+worldMaxX)/2f,
        (worldMinY+worldMaxY)/2f,
    )

    companion object {
        fun compute(resolution: Vec2i): ScreenLayout {
            val aspectRatio = (resolution.x.toFloat() / resolution.y.toFloat())
            return ScreenLayout(
                worldMinX = -1f,
                worldMaxX = if (aspectRatio < 1f) 1f else 0.9f,
                worldMinY = if (aspectRatio < 1f) -0.9f else -1f,
                worldMaxY = 1f,
                guiMinX = if (aspectRatio < 1f) -1f else 0.9f,
                guiMaxX = 1f,
                guiMinY = -1f,
                guiMaxY = if (aspectRatio < 1f) -0.9f else 1f,
                resolution,
                aspectRatio,
            )
        }
    }
}
