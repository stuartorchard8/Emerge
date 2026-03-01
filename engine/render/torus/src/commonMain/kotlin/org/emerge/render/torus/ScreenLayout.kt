package org.emerge.render.torus

import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class ScreenLayout(
    val worldPxMin: Vec2,
    val worldPxMax: Vec2,
    val guiPxMin: Vec2,
    val guiPxMax: Vec2,
    private val resolution: Vec2i,
) {
    private var vertexFloatBuffer: FloatBuffer = ByteBuffer.allocateDirect(12 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun vertices(): FloatArray = if (resolution.x < resolution.y) {
        verticesForLayoutY()
    } else {
        verticesForLayoutX()
    }
    private fun verticesForLayoutX(): FloatArray = floatArrayOf(
        worldPxMin.x*2f/resolution.x - 1f, worldPxMin.y*2f/resolution.y - 1f,
        worldPxMin.x*2f/resolution.x - 1f, worldPxMax.y*2f/resolution.y - 1f,
        worldPxMax.x*2f/resolution.x - 1f, worldPxMin.y*2f/resolution.y - 1f,
        worldPxMax.x*2f/resolution.x - 1f, worldPxMax.y*2f/resolution.y - 1f,
        guiPxMax.x*2f/resolution.x - 1f, guiPxMin.y*2f/resolution.y - 1f,
        guiPxMax.x*2f/resolution.x - 1f, guiPxMax.y*2f/resolution.y - 1f,
    )
    private fun verticesForLayoutY(): FloatArray = floatArrayOf(
        worldPxMin.x*2f/resolution.x - 1f, worldPxMax.y*2f/resolution.y - 1f,
        worldPxMax.x*2f/resolution.x - 1f, worldPxMax.y*2f/resolution.y - 1f,
        worldPxMin.x*2f/resolution.x - 1f, worldPxMin.y*2f/resolution.y - 1f,
        worldPxMax.x*2f/resolution.x - 1f, worldPxMin.y*2f/resolution.y - 1f,
        guiPxMin.x*2f/resolution.x - 1f, guiPxMin.y*2f/resolution.y - 1f,
        guiPxMax.x*2f/resolution.x - 1f, guiPxMin.y*2f/resolution.y - 1f,
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
        fun compute(resolution: Vec2i, contentScale: Vec2): ScreenLayout {
            val guiSizeDp = 80f
            val useYLayout = (resolution.x < resolution.y)
            val guiSizePx = if (useYLayout) guiSizeDp*contentScale.y else guiSizeDp*contentScale.x
            return ScreenLayout(
                worldPxMin = Vec2(
                    0f,
                    if (useYLayout) guiSizePx else 0f,
                ),
                worldPxMax = Vec2(
                    if (useYLayout) resolution.x.toFloat() else resolution.x - guiSizePx,
                    resolution.y.toFloat(),
                ),
                guiPxMin = Vec2(
                    if (useYLayout) 0f else resolution.x - guiSizePx,
                    0f,
                ),
                guiPxMax = Vec2(
                    resolution.x.toFloat(),
                    if (useYLayout) guiSizePx else resolution.y.toFloat(),
                ),
                resolution,
            )
        }
    }
}
