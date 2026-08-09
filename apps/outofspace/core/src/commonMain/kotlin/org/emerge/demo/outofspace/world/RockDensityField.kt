package org.emerge.demo.outofspace.world

import org.emerge.render.torus.GPU

/**
 * GPU texture mirror of [RockSpawner]'s density field, kept in sync with its chunk window so the nav
 * view can sample it in one textured quad with hardware bilinear filtering instead of drawing one rect
 * per chunk (see [org.emerge.render.torus.ui.CanvasBuilder.image]).
 *
 * Density is a pure function of chunk coordinates (see [RockSpawner.densityAt]) — spawning a chunk
 * never changes its value, only [RockSpawner]'s window sliding does. So the only thing that can make
 * this texture stale is [RockSpawner.windowBaseChunkX]/[RockSpawner.windowBaseChunkY] moving, and
 * [textureId] checks exactly that before re-uploading.
 *
 * The only GPU resource in the `world` package — created lazily on first [textureId] call, so the pure
 * simulation (spawning, physics; tested with no GL context) never touches it.
 */
object RockDensityField {
    private var textureId: Int = -1

    /** The texture id for [RockSpawner]'s current chunk window, rebuilding it first if the window moved
     *  since the last call. Row-major, one byte per chunk: row 0 is [RockSpawner.windowBaseChunkY]. */
    fun textureId(): Int {
        val tex = if (textureId >= 0) textureId else GPU.genTextures().also {
            textureId = it
            GPU.bindTexture2D(it)
            GPU.configureTexture2DClampLinear()
        }

        GPU.bindTexture2D(tex)
        GPU.uploadTextureRGBA8(RockSpawner.WINDOW_BUFFER_SIZE, RockSpawner.WINDOW_BUFFER_SIZE, RockSpawner.abundanceBytes)
        return tex
    }
}
