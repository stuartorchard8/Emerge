package org.emerge.desktop

import org.emerge.render.torus.GPU
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Loads the Drockets sprite frames from PNG files on the classpath and
 * packs them into a single RGBA texture atlas uploaded to the GPU.
 *
 * Atlas layout: 3 columns x 1 row, each cell 16x16.
 * Frame 0 = idle (drocket_idle.png)
 * Frame 1 = walk (drocket_walk.png)
 * Frame 2 = fire (drocket_fire.png)
 */
object DrocketsSpriteAtlas {
    const val FRAME_SIZE_X = 16
    const val FRAME_SIZE_Y = 16
    const val COLUMNS = 3
    const val ROWS = 1
    const val ATLAS_WIDTH = FRAME_SIZE_X * COLUMNS // 48
    const val ATLAS_HEIGHT = FRAME_SIZE_Y * ROWS   // 16

    private val frameFiles = listOf(
        "assets/drockets/drocket_idle.png",
        "assets/drockets/drocket_walk.png",
        "assets/drockets/drocket_fire.png",
    )

    fun load(): Int {
        val atlasData = ByteArray(ATLAS_WIDTH * ATLAS_HEIGHT * 4)

        for ((index, path) in frameFiles.withIndex()) {
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
                ?: error("Missing sprite asset: $path")
            val img: BufferedImage = ImageIO.read(stream)
            stream.close()

            val colOffset = index * FRAME_SIZE_X
            for (y in 0 until FRAME_SIZE_Y.coerceAtMost(img.height)) {
                for (x in 0 until FRAME_SIZE_X.coerceAtMost(img.width)) {
                    val pixel = img.getRGB(x, y)
                    val a = (pixel shr 24) and 0xFF
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val atlasX = colOffset + x
                    val atlasY = y
                    val baseIdx = (atlasY * ATLAS_WIDTH + atlasX) * 4
                    atlasData[baseIdx + 0] = r.toByte()
                    atlasData[baseIdx + 1] = g.toByte()
                    atlasData[baseIdx + 2] = b.toByte()
                    atlasData[baseIdx + 3] = a.toByte()
                }
            }
        }

        val textureId = GPU.genTextures()
        GPU.activeTexture(1)
        GPU.bindTexture2D(textureId)
        GPU.configureTexture2DClampNearest()
        GPU.uploadTextureRGBA8(ATLAS_WIDTH, ATLAS_HEIGHT, atlasData)
        GPU.bindTexture2D(0)
        return textureId
    }
}
