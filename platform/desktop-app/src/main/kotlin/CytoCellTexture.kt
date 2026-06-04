package org.emerge.desktop

import org.emerge.render.torus.GPU
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Loads Cyto's soft cell-blob sprite (`assets/cyto/cell.png`) from the classpath and
 * uploads it as a single RGBA8 texture. Linear filtering keeps the membrane edges
 * smooth, matching the original LibGDX `TextureFilter.Linear`.
 */
object CytoCellTexture {
    private const val PATH = "assets/cyto/cell.png"

    fun load(): Int {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(PATH)
            ?: error("Missing Cyto asset: $PATH")
        val img: BufferedImage = stream.use { ImageIO.read(it) }
        val width = img.width
        val height = img.height

        val data = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = img.getRGB(x, y)
                val a = (pixel shr 24) and 0xFF
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val base = (y * width + x) * 4
                data[base + 0] = r.toByte()
                data[base + 1] = g.toByte()
                data[base + 2] = b.toByte()
                data[base + 3] = a.toByte()
            }
        }

        val textureId = GPU.genTextures()
        GPU.activeTexture(0)
        GPU.bindTexture2D(textureId)
        GPU.configureTexture2DRepeatLinear()
        GPU.uploadTextureRGBA8(width, height, data)
        GPU.bindTexture2D(0)
        return textureId
    }
}
