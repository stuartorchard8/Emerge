package org.emerge.render.torus

import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
    private var vertexFloatBuffer: FloatBuffer = ByteBuffer.allocateDirect(12 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun vertices(): FloatArray = if (aspectRatio < 1f) {
        verticesForLayoutX()
    } else {
        verticesForLayoutY()
    }
    private fun verticesForLayoutY(): FloatArray = floatArrayOf(
        worldMinX, worldMinY,
        worldMinX, worldMaxY,
        worldMaxX, worldMinY,
        worldMaxX, worldMaxY,
        guiMaxX, guiMinY,
        guiMaxX, guiMaxY,
    )
    private fun verticesForLayoutX(): FloatArray = floatArrayOf(
        worldMinX, worldMaxY,
        worldMaxX, worldMaxY,
        worldMinX, worldMinY,
        worldMaxX, worldMinY,
        guiMinX, guiMinY,
        guiMaxX, guiMinY,
    )

    fun getWorldCenter(): Vec2 = Vec2(
        (worldMinX+worldMaxX)/2f,
        (worldMinY+worldMaxY)/2f,
    )

    fun putVerts(vbo: Int) {
        val verts = vertices()
        vertexFloatBuffer.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(0)
        // Capture the VBO binding into the VAO's attrib state.
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, vertexFloatBuffer, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

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
