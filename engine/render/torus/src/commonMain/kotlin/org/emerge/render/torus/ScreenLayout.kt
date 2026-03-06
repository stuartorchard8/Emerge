package org.emerge.render.torus

import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Frac2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class ScreenLayout(
    val worldPxMin: Vec2,
    val worldPxMax: Vec2,
    val guiPxMin: Vec2,
    val guiPxMax: Vec2,
    val resolution: Vec2,
) {
    val worldSegmentation: Int = 2
    val worldSliceSizeUv: Float = 1f/worldSegmentation
    val worldVertexCount: Int = 4*worldSegmentation*worldSegmentation
    val circleVertexCount: Int = 4
    val triVertexCount: Int = 3
    val guiVertexOffset: Int = worldVertexCount
    val circleVertexOffset: Int = worldVertexCount+circleVertexCount

    private var vertexFloatBuffer: FloatBuffer = ByteBuffer.allocateDirect((worldVertexCount+circleVertexCount+triVertexCount) * VERTEX_DIM * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun vertices(): FloatArray {
        val worldVertices = buildList {
            for (x in 0..<worldSegmentation) {
                for (y in 0..<worldSegmentation) {
                    addAll(getWorldVertices(x, y))
                }
            }
        }
        return floatArrayOf(
            // world
            *worldVertices.toFloatArray(),
            // gui
            xPxToUv(guiPxMin.x), yPxToUv(guiPxMin.y),
            xPxToUv(guiPxMin.x), yPxToUv(guiPxMax.y),
            xPxToUv(guiPxMax.x), yPxToUv(guiPxMin.y),
            xPxToUv(guiPxMax.x), yPxToUv(guiPxMax.y),
            // circle
            -1.7320508f, -1f,
            0f, 2f,
            1.7320508f, -1f,
        )
    }

    fun getWorldVertices(xSeg: Int, ySeg: Int): Array<Float> {
        val worldUvMin = pxToUv(worldPxMin)
        val worldUvMax = pxToUv(worldPxMax)
        val worldUvSize = worldUvMax - worldUvMin
        val segMinX =  xSeg   *worldSliceSizeUv*worldUvSize.x
        val segMaxX = (xSeg+1)*worldSliceSizeUv*worldUvSize.x
        val segMinY =  ySeg   *worldSliceSizeUv*worldUvSize.y
        val segMaxY = (ySeg+1)*worldSliceSizeUv*worldUvSize.y
        return arrayOf(
            worldUvMin.x+segMinX, worldUvMin.y+segMinY,
            worldUvMin.x+segMinX, worldUvMin.y+segMaxY,
            worldUvMin.x+segMaxX, worldUvMin.y+segMinY,
            worldUvMin.x+segMaxX, worldUvMin.y+segMaxY,
        )
    }

    fun pxToNdc(px: Vec2): Vec2 = pxToUv(px)

    private fun xPxToUv(px: Float): Float = px*2f/resolution.x - 1f
    private fun yPxToUv(px: Float): Float = px*2f/resolution.y - 1f
    private fun pxToUv(px: Vec2): Vec2 = px*2f/resolution - 1f

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
        fun compute(resolution: Vec2, contentScale: Vec2): ScreenLayout {
            val guiSizeDp = 80f
            val useYLayout = (resolution.x < resolution.y)
            val guiSizePx = if (useYLayout) guiSizeDp*contentScale.y else guiSizeDp*contentScale.x
            return ScreenLayout(
                worldPxMin = Vec2(
                    0f,
                    if (useYLayout) guiSizePx else 0f,
                ),
                worldPxMax = Vec2(
                    if (useYLayout) resolution.x else resolution.x - guiSizePx,
                    resolution.y,
                ),
                guiPxMin = Vec2(
                    if (useYLayout) 0f else resolution.x - guiSizePx,
                    0f,
                ),
                guiPxMax = Vec2(
                    resolution.x,
                    if (useYLayout) guiSizePx else resolution.y,
                ),
                resolution,
            )
        }

        private const val VERTEX_DIM = 2
        private const val FLOAT_SIZE = 4
    }
}
