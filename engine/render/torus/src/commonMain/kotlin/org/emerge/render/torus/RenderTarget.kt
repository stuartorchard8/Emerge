package org.emerge.render.torus

/**
 * An off-screen colour buffer: a framebuffer with an RGBA8 texture attached, so a pass can draw into a
 * texture instead of the screen.
 *
 * The motivating use is tiling a torus world. One period of the world is drawn into a target once, and the
 * resulting texture is then repeated across the screen — so zooming out past the world's edges shows the
 * world recurring without redrawing its contents once per repeat. The texture is configured `GL_REPEAT` +
 * linear precisely so that tiling costs nothing beyond letting UVs run past `[0, 1]`.
 *
 * **No mipmaps, deliberately.** They would have to be regenerated every frame, and they would filter across
 * the wrap seam and break it. The tiling caller avoids needing them by sizing the target so one texel maps
 * to about one screen pixel, which leaves no minification to filter.
 *
 * Not thread-safe and GL-affine: every method must run on the render thread with a current context.
 */
class RenderTarget {
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    private var fbo: Int = 0
    private var texture: Int = 0

    /** The colour texture, or 0 before the first successful [resize]. Valid to sample once a pass has ended. */
    val textureId: Int get() = texture

    /**
     * Ensure the target is [w]×[h], reallocating its storage only when the size actually changes — callers
     * that vary size continuously (e.g. with zoom) should quantise before calling, since each real change
     * reallocates the texture.
     *
     * Returns false if the size is not usable or the framebuffer did not come out complete, in which case
     * the target must not be drawn into and the caller should fall back to drawing on-screen.
     */
    fun resize(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (w == width && h == height && texture != 0) return true

        if (texture == 0) texture = GPU.genTextures()
        if (fbo == 0) fbo = GPU.genFramebuffers()

        GPU.activeTexture(0)
        GPU.bindTexture2D(texture)
        GPU.allocateTextureRGBA8(w, h)
        // GL_REPEAT is what makes tiling free: the blit just lets its UVs run past [0, 1].
        GPU.configureTexture2DRepeatLinear()

        GPU.bindFramebuffer(fbo)
        GPU.framebufferColorTexture2D(texture)
        val complete = GPU.isFramebufferComplete()
        GPU.bindFramebuffer(0)
        GPU.bindTexture2D(0)

        if (!complete) {
            width = 0; height = 0
            return false
        }
        width = w; height = h
        return true
    }

    /**
     * Direct subsequent draws into this target and set the viewport to cover it. Clears to transparent
     * black first: what a pass does not cover must not show the previous frame's pixels.
     */
    fun begin() {
        GPU.bindFramebuffer(fbo)
        GPU.setViewport(0, 0, width, height)
        GPU.setClearColor(0f, 0f, 0f, 0f)
        GPU.clearColorBuffer()
    }

    /** Return drawing to the screen, restoring the viewport to [screenW]×[screenH] framebuffer pixels. */
    fun end(screenW: Int, screenH: Int) {
        GPU.bindFramebuffer(0)
        GPU.setViewport(0, 0, screenW, screenH)
    }

    fun delete() {
        if (fbo != 0) { GPU.deleteFramebuffers(fbo); fbo = 0 }
        if (texture != 0) { GPU.deleteTextures(texture); texture = 0 }
        width = 0; height = 0
    }
}
